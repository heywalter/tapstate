package io.tapstate.control.core;

import java.util.List;

/**
 * The write-free result of validating and planning an artifact batch.
 *
 * <p>The two diagnostic columns answer different questions and are never merged. {@code diagnostics}
 * holds why the batch was refused, and is populated only when {@code valid} is false; {@code warnings}
 * holds the advisory findings over a batch that passed, and never affects {@code valid}. Kept apart,
 * a caller decides how to react by which list it read rather than by inspecting a severity field.
 */
public record ArtifactValidationResult(
        boolean valid,
        List<ArtifactOutcome> outcomes,
        List<ValidationDiagnostic> diagnostics,
        List<ValidationDiagnostic> warnings) {

    public ArtifactValidationResult {
        outcomes = List.copyOf(outcomes);
        diagnostics = List.copyOf(diagnostics);
        warnings = List.copyOf(warnings);
    }
}
