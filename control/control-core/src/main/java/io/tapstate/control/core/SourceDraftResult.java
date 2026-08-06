package io.tapstate.control.core;

import java.util.Objects;

/** Canonical Source YAML rendered without creating or updating an artifact. */
public record SourceDraftResult(String yaml) {

    public SourceDraftResult {
        Objects.requireNonNull(yaml, "yaml");
        if (yaml.isBlank()) {
            throw new IllegalArgumentException("yaml must not be blank");
        }
    }

    @Override
    public String toString() {
        return "SourceDraftResult[yaml=<redacted>]";
    }
}
