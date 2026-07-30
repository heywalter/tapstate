package io.tapstate.control.core;

import java.util.List;

/** The write-free result of validating and planning an artifact batch. */
public record ArtifactValidationResult(
        boolean valid,
        List<ArtifactOutcome> outcomes,
        List<ValidationDiagnostic> diagnostics) {

    public ArtifactValidationResult {
        outcomes = List.copyOf(outcomes);
        diagnostics = List.copyOf(diagnostics);
    }
}
