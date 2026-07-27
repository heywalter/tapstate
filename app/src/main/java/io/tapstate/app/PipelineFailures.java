package io.tapstate.app;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.lifecycle.ObservationFailure;
import io.tapstate.runtime.engine.EngineError;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the Throwable that killed a pipeline's run to the coded failure its observation carries, so a dead
 * job reports a reason the read faces can render rather than a bare state change with the cause left in a
 * log line. This lives in the assembly layer because it is the one place that sees both the engine's codes
 * and the observation the scheduler publishes; neither ring depends on the other.
 *
 * <p>A cause the product already coded at its throw site keeps that code: it is more specific than any
 * wrapper around it and is the one that tells the user what to fix. Everything else reports the generic
 * engine failure — a reason is always named, but an uncoded fault is never laundered into a specific code
 * it does not deserve.
 */
final class PipelineFailures {

    /** How deep to look for a coded cause before giving up; a chain longer than this is a cycle or a bug. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private PipelineFailures() {
    }

    /** The coded failure to publish for {@code pipelineId}, given the throwable that ended its run. */
    static ObservationFailure of(String pipelineId, Throwable failure) {
        TapstateException coded = codedCause(failure);
        if (coded != null) {
            Map<String, String> params = new LinkedHashMap<>();
            coded.args().forEach((name, value) -> params.put(name, String.valueOf(value)));
            return new ObservationFailure(coded.code().code(), params);
        }
        return new ObservationFailure(EngineError.JOB_FAILED.code(),
                Map.of("pipeline", pipelineId, "cause", describe(failure)));
    }

    /**
     * The first coded exception in the throwable's cause chain, or {@code null} when nothing in it carries a
     * code. The data plane wraps what a sink or a connector threw before it reaches the converge loop, so the
     * coded fault is usually not the outermost throwable.
     */
    private static TapstateException codedCause(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof TapstateException coded) {
                return coded;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    /**
     * What the run died of, in one line: the throwable's message, or its type when it has none. A declared
     * placeholder always gets a value, and an empty string would tell the user nothing.
     */
    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
