package io.tapstate.control.core;

import io.tapstate.core.dsl.Advisory;
import io.tapstate.core.dsl.DiscoveredTable;
import io.tapstate.core.dsl.NestSizingRules;
import io.tapstate.core.model.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The advisory pass that sizes every nest in a batch against the memory it would run on, reported to
 * whoever applied the batch.
 *
 * <p>The budget a nest that writes none of its own runs on is the deployment's, so it is handed in at
 * assembly rather than read here: the number belongs to the process that will run the pipeline, and
 * this layer only judges one.
 */
public final class NestSizingAdvisories implements PlanAdvisories {

    private final long entriesHeldInMemoryByDefault;

    public NestSizingAdvisories(long entriesHeldInMemoryByDefault) {
        this.entriesHeldInMemoryByDefault = entriesHeldInMemoryByDefault;
    }

    @Override
    public List<ValidationDiagnostic> review(
            List<Resource> resources, Map<String, List<DiscoveredTable>> tablesBySource) {
        List<ValidationDiagnostic> findings = new ArrayList<>();
        for (Advisory advisory
                : NestSizingRules.review(resources, tablesBySource, entriesHeldInMemoryByDefault)) {
            // The code travels as its canonical string and the arguments by name, which is what the
            // catalog renders from — the same shape a refusal takes on this endpoint.
            findings.add(new ValidationDiagnostic(advisory.code().code(), advisory.params()));
        }
        return List.copyOf(findings);
    }
}
