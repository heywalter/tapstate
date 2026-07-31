package io.tapstate.app;

import io.tapstate.core.lifecycle.ObservationFailure;
import io.tapstate.core.lifecycle.PipelineState;
import io.tapstate.core.lifecycle.StateJson;
import io.tapstate.runtime.scheduler.ConvergeResult;
import io.tapstate.runtime.scheduler.ConvergeStatus;
import io.tapstate.runtime.scheduler.ObservationPublisher;
import io.tapstate.runtime.scheduler.PipelineConverger;
import io.tapstate.spi.store.DesiredStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodically reconciles every pipeline's actual state toward its desired intent, then publishes each
 * pipeline's observation for the read faces to serve. Spring's scheduler lives here in the assembly
 * layer, not in the runtime ring where the framework is banned, so this thin driver ticks the
 * framework-free {@link PipelineConverger} and {@link ObservationPublisher}. Each pipeline is handled
 * independently: a failure on one is logged and does not stop the pass, so one bad pipeline cannot
 * starve the rest.
 */
final class ConvergenceDriver {

    private static final Logger LOG = LoggerFactory.getLogger(ConvergenceDriver.class);

    private final PipelineConverger converger;
    private final DesiredStore desired;
    private final ObservationPublisher publisher;

    // Consecutive failed-reconcile passes per pipeline, so a pipeline that keeps throwing surfaces as a
    // climbing errorCount rather than an empty read face. Reconcile runs on a single scheduler thread with a
    // fixed delay (passes never overlap), so a plain map needs no synchronization.
    private final Map<String, Long> reconcileFailures = new HashMap<>();

    // The reason each still-FAILED pipeline's job died, kept across ticks. The converger reports the cause
    // only on the one pass that drives RUNNING -> FAILED; every later pass while the checkpoint stays FAILED
    // returns a bare CONVERGED with no cause attached (it did not just fail again, it is still failed from
    // before). Without this the observation would go reasonless on the very next tick despite the pipeline
    // being no more recovered than it was a second ago. Single scheduler thread, same as reconcileFailures.
    private final Map<String, ObservationFailure> lastFailure = new HashMap<>();

    ConvergenceDriver(PipelineConverger converger, DesiredStore desired, ObservationPublisher publisher) {
        this.converger = converger;
        this.desired = desired;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${tapstate.converge.interval-ms:1000}")
    void reconcile() {
        List<String> pipelineIds = desired.pipelineIds();
        for (String pipelineId : pipelineIds) {
            // Attribute every line logged while reconciling this pipeline to it, so the logs read face can
            // tail per pipeline. Cleared per pipeline so the slot never leaks onto the next one or an idle tick.
            MDC.put(PipelineLogAppender.PIPELINE_ID_MDC_KEY, pipelineId);
            try {
                ConvergeResult result = converger.converge(pipelineId);
                ObservationFailure failure;
                if (result.status() == ConvergeStatus.FAILED) {
                    // The job died on its own; the converge side moved the pipeline to the observable FAILED
                    // state. This is the only pass that holds the cause, so it goes two ways: coded onto the
                    // observation, so a read face can say why, and into the log with its code and stack. Kept
                    // so the reason survives the ticks after this one, where the converger reports nothing.
                    failure = PipelineFailures.of(pipelineId, result.failure().orElse(null));
                    lastFailure.put(pipelineId, failure);
                    LOG.warn("Pipeline {} entered FAILED [{}]: its data-plane job died", pipelineId,
                            failure.code(), result.failure().orElse(null));
                } else if (stillFailed(result)) {
                    // Unchanged from a previous pass's FAILED: not a fresh failure, so the converger returns a
                    // bare CONVERGED with no cause attached. Keep reporting the reason it died of rather than
                    // going reasonless on every tick after the first.
                    failure = lastFailure.get(pipelineId);
                } else {
                    // Recovered, or never failed this pass at all: nothing stale to carry forward.
                    failure = null;
                    lastFailure.remove(pipelineId);
                }
                publisher.publish(pipelineId, failure);
                // A clean pass ends the failure streak; the next throw starts counting from one again.
                reconcileFailures.remove(pipelineId);
            } catch (RuntimeException e) {
                // A pass that keeps throwing never reaches publish(), so the read face would stay empty and a
                // permanently broken pipeline would look identical to a slow one. Count the consecutive
                // failures and publish them as an error observation so the failure is observable, not just
                // logged. Isolate this pipeline: one bad pipeline must not starve the rest of the pass.
                long failures = reconcileFailures.merge(pipelineId, 1L, Long::sum);
                LOG.warn("Reconcile pass for pipeline {} failed {} time(s) in a row; retrying on the next tick",
                        pipelineId, failures, e);
                try {
                    publisher.publishReconcileFailure(pipelineId, failures);
                } catch (RuntimeException unpublishable) {
                    // The read face is unreachable too (e.g. the same store backs both), so nothing can be
                    // surfaced this tick; the warning above is the only record.
                    LOG.warn("Could not publish the reconcile failure for pipeline {}", pipelineId, unpublishable);
                }
            } finally {
                MDC.remove(PipelineLogAppender.PIPELINE_ID_MDC_KEY);
            }
        }
        // Forget streaks for pipelines that are no longer desired, so a deleted-while-failing pipeline does
        // not leak a counter that nothing will ever clear.
        reconcileFailures.keySet().retainAll(pipelineIds);
        lastFailure.keySet().retainAll(pipelineIds);
    }

    /** Whether this pass landed on (or left untouched) a checkpoint that was already FAILED. */
    private static boolean stillFailed(ConvergeResult result) {
        return result.status() == ConvergeStatus.CONVERGED
                && result.checkpoint().map(c -> StateJson.parse(c.stateJson()) == PipelineState.FAILED)
                        .orElse(false);
    }
}
