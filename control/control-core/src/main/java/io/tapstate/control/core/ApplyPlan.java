package io.tapstate.control.core;

import java.util.List;
import java.util.Map;

/**
 * The result of validating and canonicalizing a batch of drafts: the artifacts an apply would
 * upsert, in submission order. Producing a plan performs no writes — comparing each hash against the
 * store and upserting the changed artifacts is the caller's next step.
 *
 * <p>{@code preconditions} carries the version each draft declared it was editing, keyed by the id
 * that draft parsed to, and holds only the ids whose draft declared one. It travels with the plan
 * because the declaration belongs to the submitted draft, not to the artifact: by the time the plan
 * is written, the drafts are behind us and nothing else can say which write was version-checked.
 */
public record ApplyPlan(List<PreparedArtifact> artifacts, Map<String, String> preconditions) {

    public ApplyPlan {
        artifacts = List.copyOf(artifacts);
        preconditions = Map.copyOf(preconditions);
    }

    /** A plan whose drafts declared no version preconditions. */
    public ApplyPlan(List<PreparedArtifact> artifacts) {
        this(artifacts, Map.of());
    }

    /** The version the draft for {@code id} declared it was editing, or null when it declared none. */
    public String precondition(String id) {
        return preconditions.get(id);
    }
}
