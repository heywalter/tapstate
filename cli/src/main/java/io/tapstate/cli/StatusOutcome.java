package io.tapstate.cli;

/**
 * The outcome of a remote pipeline status read ({@code GET /api/pipelines/{id}/status}). Either the read
 * found the pipeline's latest published lifecycle state, or it was refused with a coded reason (a pipeline
 * that has published no observation is {@code monitor.no-observation}), or the server could not be reached.
 * Sealed so the caller renders each branch without try/catch, mirroring the never-throw transport seam. The
 * CLI carries no shared control type (rule R6: it reaches the server over HTTP only), so the state travels
 * as its wire string.
 */
sealed interface StatusOutcome {

    /**
     * The read found the pipeline's lifecycle state (its wire name, e.g. {@code RUNNING}), and the coded
     * reason its run died when the server published one ({@code failureCode} null while it is healthy).
     * The reason arrives as a code plus the message the server rendered for it — the same pair a coded
     * refusal carries, so both print through one renderer here.
     */
    record Found(String pipelineId, String state, String failureCode, String failureMessage)
            implements StatusOutcome {

        /** A status with nothing wrong to report. */
        Found(String pipelineId, String state) {
            this(pipelineId, state, null, null);
        }
    }

    /** The server refused the read with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements StatusOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements StatusOutcome {
    }
}
