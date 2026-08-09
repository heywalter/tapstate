package io.tapstate.control.core;

import java.util.List;

/**
 * The result of applying a batch of drafts: one outcome per artifact, in submission order — whether
 * each was created, updated, or an unchanged no-op — and the advisory findings over the batch.
 * Producing this has already performed the upserts; the outcomes report what the store did.
 *
 * <p>The warnings are what the author should know about a batch that was applied. They are carried
 * beside the outcomes rather than in place of them: every artifact here was written, warned or not.
 */
public record ApplyResult(List<ArtifactOutcome> outcomes, List<ValidationDiagnostic> warnings) {

    public ApplyResult {
        outcomes = List.copyOf(outcomes);
        warnings = List.copyOf(warnings);
    }
}
