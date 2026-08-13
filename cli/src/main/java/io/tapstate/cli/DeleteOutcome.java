package io.tapstate.cli;

import java.util.Map;

/**
 * The outcome of a remote {@code DELETE /api/artifacts/{id}}. Either the resource was removed, the
 * server refused with a coded reason, or it could not be reached. Sealed so the caller renders each
 * branch without try/catch, mirroring the never-throw seam.
 *
 * <p>A refusal carries the server's named parameters as well as its rendered message: the two grounds a
 * removal is refused on each name what to do next — which resources still reference this one, and what
 * state the pipeline is actually in — and dropping them would leave the caller a sentence it cannot act
 * on without asking the server a second question.
 */
sealed interface DeleteOutcome {

    /** The resource was removed. */
    record Removed(String id) implements DeleteOutcome {
    }

    /** The server refused the removal with a coded reason already rendered to a message. */
    record Rejected(String code, String message, Map<String, Object> params) implements DeleteOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements DeleteOutcome {
    }
}
