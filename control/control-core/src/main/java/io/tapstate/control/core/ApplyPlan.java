package io.tapstate.control.core;

import java.util.List;

/**
 * The result of validating and canonicalizing a batch of drafts: the artifacts an apply would
 * upsert, in submission order, and the advisory findings over them. Producing a plan performs no
 * writes — comparing each hash against the store and upserting the changed artifacts is the caller's
 * next step.
 *
 * <p>The warnings are notes about a batch that validated, never reasons it did not: a plan exists only
 * for a batch that passed, and a plan carrying warnings is applied exactly like one that carries none.
 */
public record ApplyPlan(List<PreparedArtifact> artifacts, List<ValidationDiagnostic> warnings) {

    public ApplyPlan {
        artifacts = List.copyOf(artifacts);
        warnings = List.copyOf(warnings);
    }
}
