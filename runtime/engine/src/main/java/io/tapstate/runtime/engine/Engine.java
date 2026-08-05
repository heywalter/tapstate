package io.tapstate.runtime.engine;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.config.ProcessingGuarantee;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.JobStatus;
import com.hazelcast.jet.core.metrics.Measurement;
import com.hazelcast.jet.core.metrics.MetricNames;
import com.hazelcast.jet.core.metrics.MetricTags;
import io.tapstate.core.common.TapstateException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/**
 * The data-plane execution engine: the lifecycle actuator that maps a pipeline's lifecycle to Jet
 * job operations. A pipeline runs as exactly one Jet job named by the pipeline id, so the actuator
 * can find that job again to suspend / resume / cancel it. The topology (the {@link DAG}) is built
 * elsewhere and handed in; this engine only submits and controls the job.
 *
 * <p>Jet is a subordinate execution layer, not the source of truth: jobs carry no durable offset
 * snapshot ({@link ProcessingGuarantee#NONE}) and may be discarded and re-submitted at any time.
 * Continuation truth lives in the store, so a resumed job re-reads its start position from there
 * rather than from a Jet snapshot. Rule R4: this depends on the kernel and Hazelcast only.
 */
public final class Engine {

    private final HazelcastInstance member;

    public Engine(HazelcastInstance member) {
        this.member = Objects.requireNonNull(member, "member");
    }

    /**
     * Submits the pipeline's topology as a Jet job named by the pipeline id. Idempotent: if a job
     * under that name is already active it is left running and returned, so a repeated convergence
     * pass does not start a second job for the same pipeline.
     *
     * <p>Clears any failure {@link JobFailureRegistry} recorded for this pipeline id first: a fresh
     * submission means a fresh run (the caller only submits after driving the pipeline back toward
     * RUNNING), so a cause an earlier run recorded must not be mistaken for this one's while it is
     * still healthy. A submission that finds the job already active clears a registry entry that was
     * never set, which is a no-op.
     */
    public void submit(String pipelineId, DAG dag) {
        JobFailureRegistry.of(member).clear(pipelineId);
        JobConfig config = new JobConfig()
                .setName(pipelineId)
                .setProcessingGuarantee(ProcessingGuarantee.NONE);
        member.getJet().newJobIfAbsent(dag, config);
    }

    /** Pauses the pipeline's running job. The job is kept so it can be resumed. */
    public void suspend(String pipelineId) {
        requireJob(pipelineId).suspend();
    }

    /**
     * Resumes the pipeline's suspended job. Under the no-snapshot guarantee this re-runs the
     * topology, so the source re-reads its start position from the store rather than a Jet snapshot.
     */
    public void resume(String pipelineId) {
        requireJob(pipelineId).resume();
    }

    /**
     * Cancels the pipeline's job. Idempotent: a pipeline with no running job is already stopped, so
     * the job side is a no-op. The pipeline-private continuation the store holds (its consumer cursor,
     * private operator state and sink watermark) is cleared separately when the source store is
     * present; this actuator owns the job side.
     *
     * <p>Always releases any failure the {@link JobFailureRegistry} recorded for this pipeline, live job
     * or not: a stop is the terminal verb for a failed pipeline, and its job is already terminal by then —
     * so the release must not hide behind the live-job guard, or the recorded exception (and its whole
     * cause graph) would stay referenced for the rest of the member's life. By the time a stop is driven,
     * the converge side has already read the failure and moved the pipeline to the observable FAILED
     * state, so nothing is lost by forgetting it here.
     */
    public void cancel(String pipelineId) {
        Job job = liveJob(pipelineId);
        if (job != null) {
            job.cancel();
        }
        JobFailureRegistry.of(member).clear(pipelineId);
    }

    /**
     * The failure of the pipeline's job if it died on its own, or empty while it runs, has no job, or
     * was ended by a cancel. A Jet job reaches FAILED both when it throws and when it is cancelled, so a
     * bare status check cannot tell a real failure from a stop; the terminal future tells them apart —
     * a cancelled job completes with a {@link CancellationException}, a failed one with its own cause.
     * The job is terminal here, so joining its future returns or throws at once without blocking.
     *
     * <p>Checks the {@link JobFailureRegistry} first: once a job's terminal result is durable, Jet tears
     * down the live context backing {@code job.getFuture()} and later reconstructs a cause-free mock
     * throwable from the failure's stored text (measured: production loses the real cause this way the
     * large majority of the time, a race of a few milliseconds wide). A processor that caught the real
     * exception records it there before that teardown can happen, so a hit is always the real cause. Only
     * a job that failed for a reason no processor recorded — a fault inside Jet itself, for one — falls
     * through to asking Jet, which after that same teardown can answer with the degraded mock instead.
     */
    public Optional<Throwable> failureOf(String pipelineId) {
        Job job = member.getJet().getJob(pipelineId);
        if (job == null || job.getStatus() != JobStatus.FAILED) {
            return Optional.empty();
        }
        Optional<Throwable> recorded = JobFailureRegistry.of(member).get(pipelineId);
        if (recorded.isPresent()) {
            return recorded;
        }
        try {
            job.getFuture().join();
            return Optional.empty();
        } catch (CancellationException cancelled) {
            return Optional.empty();
        } catch (CompletionException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            return cause instanceof CancellationException ? Optional.empty() : Optional.of(cause);
        }
    }

    /**
     * The number of records the pipeline's live job has driven to its serve sinks, or empty when it
     * has no live job. The count is the received count summed over the serve-sink vertices, so a
     * filter earlier in the chain is reflected in it. It reads the job's last collected metrics, so a
     * freshly submitted job reports a low or zero count until the first collection; a stopped pipeline
     * reports empty, matching the live-state projection the rest of the read face carries.
     */
    public OptionalLong recordCount(String pipelineId) {
        Job job = liveJob(pipelineId);
        if (job == null) {
            return OptionalLong.empty();
        }
        long reached = job.getMetrics().get(MetricNames.RECEIVED_COUNT).stream()
                .filter(Engine::isServeSink)
                .mapToLong(Measurement::value)
                .sum();
        return OptionalLong.of(reached);
    }

    /** Whether a measurement belongs to a serve-sink vertex, which the builder names by that prefix. */
    private static boolean isServeSink(Measurement measurement) {
        String vertex = measurement.tag(MetricTags.VERTEX);
        return vertex != null && vertex.startsWith(PipelineDagBuilder.SERVE_VERTEX_PREFIX);
    }

    /**
     * The pipeline's live job, or {@code null} when it has none. A completed or cancelled job is
     * kept under its name but is not live: Jet retains terminal jobs, so a bare presence check would
     * mistake a stopped pipeline for a running one.
     */
    private Job liveJob(String pipelineId) {
        Job job = member.getJet().getJob(pipelineId);
        return job == null || job.getStatus().isTerminal() ? null : job;
    }

    /** The pipeline's live job, or a coded {@code engine.no-such-job} when it has none to act on. */
    private Job requireJob(String pipelineId) {
        Job job = liveJob(pipelineId);
        if (job == null) {
            throw new TapstateException(EngineError.NO_SUCH_JOB, Map.of("pipeline", pipelineId), null);
        }
        return job;
    }
}
