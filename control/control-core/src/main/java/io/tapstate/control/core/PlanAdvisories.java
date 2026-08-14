package io.tapstate.control.core;

import io.tapstate.core.dsl.DiscoveredTable;
import io.tapstate.core.model.Resource;

import java.util.List;
import java.util.Map;

/**
 * The advisory pass over a batch that has already validated: findings worth telling the author about
 * that are not grounds to refuse the batch. Each finding is a coded diagnostic, and they travel in
 * their own column apart from the validation diagnostics — so a caller tells "why this was refused"
 * from "what to know about a batch that was not" by which list it read, never by inspecting a severity
 * field.
 *
 * <p>A pass runs only over a batch that planned. A batch that failed validation has no plan to review,
 * and its refusal is already the message; a rule that ran anyway would be judging resources the
 * validation stack has just declared unusable, and would bury the actual reason under advice.
 *
 * <p>A rule reports rather than throws. Refusing is the validation stack's job, and a rule that threw
 * would turn advice into a gate — which is the one thing this channel exists not to be.
 */
@FunctionalInterface
public interface PlanAdvisories {

    /**
     * The findings over one validated batch, in report order; empty when there is nothing to say.
     *
     * <p>{@code tablesBySource} is what each source in the batch was discovered to hold, keyed by the
     * source's id — the same reading the validation stack judged the batch against, handed on rather
     * than taken again. A rule that fetched it for itself would pay a second round trip for an answer
     * already in hand, and could reach a different one, so a batch would be advised about a state of
     * the world it was never judged against.
     */
    List<ValidationDiagnostic> review(
            List<Resource> resources, Map<String, List<DiscoveredTable>> tablesBySource);

    /** The pass that finds nothing — what an assembly carrying no advisory rules is wired with. */
    static PlanAdvisories none() {
        return (resources, tablesBySource) -> List.of();
    }
}
