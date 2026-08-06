package io.tapstate.control.core;

import io.tapstate.core.lifecycle.ObservationFailure;
import io.tapstate.core.lifecycle.PipelineState;
import java.util.Objects;

/**
 * The status read face: a pipeline's lifecycle state, the small stable observation dataset, plus the
 * coded reason it died when that state is a failure. Carries no counts / rates / snapshot progress, so
 * the status contract evolves with the state machine, not with the growing metric set — the failure
 * belongs here because it qualifies the state rather than measuring the run: a failed state that cannot
 * say what failed is only half an answer.
 */
public record PipelineStatus(String pipelineId, PipelineState state, ObservationFailure failure) {

    public PipelineStatus {
        Objects.requireNonNull(pipelineId, "pipelineId");
        Objects.requireNonNull(state, "state");
    }

    /** A status with nothing wrong to report — the shape every healthy read takes. */
    public PipelineStatus(String pipelineId, PipelineState state) {
        this(pipelineId, state, null);
    }
}
