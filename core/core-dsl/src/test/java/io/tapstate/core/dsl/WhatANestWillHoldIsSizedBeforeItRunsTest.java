package io.tapstate.core.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.core.common.TapstateType;
import io.tapstate.core.model.Resource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A nest keeps a bounded number of entries in memory and reads the rest back from the layer behind it,
 * so a level whose working set dwarfs that budget spends its life on storage round trips. Nothing about
 * a running pipeline says so: the events are consumed, the queues are short and the throughput is what
 * the source can supply — the cost lands on latency per event, which no backpressure signal carries.
 *
 * <p>So it is said while the pipeline is being written, from the row counts its sources reported when
 * they were discovered. Being over budget is by design and says nothing on its own; the line is a
 * multiple of the budget, so what is reported is a level that will miss memory nearly every time
 * rather than one that will miss it sometimes.
 *
 * <p>A level is reported by the path the author wrote it at, never by the name of the map behind it:
 * the author has never seen that name and cannot act on it.
 *
 * <p>A table nobody counted is not a table with no rows. Sizing a level from a count that was never
 * taken would let an unmeasured table pass as an empty one, so the estimate says how much of the level
 * it could actually see.
 */
class WhatANestWillHoldIsSizedBeforeItRunsTest {

    private static final long DEPLOYMENT_BUDGET = 100_000L;

    private final DslParser parser = new DslParser();

    /** customers → policies → claims: the middle level has children, so it holds state of its own. */
    private static String threeLevels(String capacity) {
        return """
                version: tapstate/v1
                kind: pipeline
                id: p
                source: src
                transforms:
                  - id: doc
                    type: nest
                    from: { c: customers, pol: policies, cl: claims }
                """
                + capacity
                + """
                    root:
                      from: c
                      key: [customer_id]
                      embed:
                        - from: pol
                          on: { customer_id: customer_id }
                          as: array
                          path: policies
                          arrayKey: [policy_id]
                          embed:
                            - from: cl
                              on: { policy_id: policy_id }
                              as: array
                              path: claims
                              arrayKey: [claim_id]
                serve:
                  from: doc
                  query: [ { type: rest } ]
                """;
    }

    private List<Resource> batch(String yaml) {
        return List.of(parser.parse(yaml));
    }

    /** The tables of source {@code src}, each with the row count discovery reported for it. */
    private static Map<String, List<DiscoveredTable>> counted(Object... nameThenCount) {
        List<DiscoveredTable> tables = new java.util.ArrayList<>();
        for (int i = 0; i < nameThenCount.length; i += 2) {
            Map<String, TapstateType> columns = new LinkedHashMap<>();
            columns.put("customer_id", TapstateType.STRING);
            columns.put("policy_id", TapstateType.STRING);
            columns.put("claim_id", TapstateType.STRING);
            tables.add(new DiscoveredTable(
                    (String) nameThenCount[i], columns, (Long) nameThenCount[i + 1]));
        }
        return Map.of("src", tables);
    }

    private static List<Advisory> withCode(List<Advisory> findings, NestSizingError code) {
        return findings.stream().filter(f -> f.code() == code).toList();
    }

    private static Advisory at(List<Advisory> findings, NestSizingError code, String embedPath) {
        return withCode(findings, code).stream()
                .filter(f -> embedPath.equals(f.params().get("embedPath")))
                .findFirst()
                .orElse(null);
    }

    @Test
    void aLevelThatFitsInMemoryIsNotWorthSaying() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", 1_000L, "policies", 4_000L, "claims", 5_000_000L),
                DEPLOYMENT_BUDGET);

        // claims is far past the line and still says nothing: a leaf holds no state of its own, so its
        // rows are elements inside a document rather than entries of a level. Its count is deliberately
        // over the line rather than merely over the budget, so that this stays a claim about leaves and
        // does not pass for the unrelated reason that nothing here was big enough to report.
        assertThat(findings).isEmpty();
    }

    @Test
    void aLevelNeedingFarMoreThanTheBudgetIsReportedAtThePathItWasWrittenAt() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", 5_000_000L, "policies", 1_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        Advisory root = at(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET, "$root");
        assertThat(root).as("the root level is reported").isNotNull();
        assertThat(root.params())
                .containsEntry("pipeline", "p")
                .containsEntry("estimatedEntries", 5_000_000L)
                .containsEntry("budget", DEPLOYMENT_BUDGET)
                .containsEntry("multiple", 50L);
    }

    @Test
    void aLevelIsNamedByTheAuthorsPathAndNeverByTheMapBehindIt() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", 1_000L, "policies", 5_000_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        assertThat(at(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET, "policies"))
                .as("the middle level is reported at the path the author wrote").isNotNull();
        // The map name carries the pipeline and node ids; it is a runtime detail the author never sees.
        assertThat(findings.toString()).doesNotContain("nest.p.doc");
    }

    @Test
    void aLeafEmbedIsNotALevelOfItsOwn() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", 1_000L, "policies", 1_000L, "claims", 5_000_000L),
                DEPLOYMENT_BUDGET);

        // A leaf's rows live inside the entry of the level above it, so they are bounded by how many
        // elements one document may hold, not by how many entries a level keeps in memory.
        assertThat(withCode(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET)).isEmpty();
    }

    @Test
    void theBudgetTheAuthorWroteIsTheOneSizedAgainst() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("    entries_in_memory: 10000000\n")),
                counted("customers", 5_000_000L, "policies", 1_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        // The same 5,000,000 rows that were far over the deployment's budget fit inside the one the
        // pipeline wrote for itself, so there is nothing to say about them.
        assertThat(withCode(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET)).isEmpty();
    }

    @Test
    void aTableNobodyCountedIsNotSizedAsAnEmptyOne() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", null, "policies", 1_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        Advisory incomplete = at(findings, NestSizingError.CAPACITY_ESTIMATE_INCOMPLETE, "$root");
        assertThat(incomplete).as("the level nobody could size is reported as unsized").isNotNull();
        assertThat(incomplete.params()).containsEntry("counted", 0L).containsEntry("total", 1L);
        // And it is never reported as fitting: a count that was never taken is not a count of zero.
        assertThat(withCode(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET)).isEmpty();
    }

    @Test
    void aLevelThatCouldBeSizedIsNotReportedAsUnsized() {
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", 1_000L, "policies", 1_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        assertThat(withCode(findings, NestSizingError.CAPACITY_ESTIMATE_INCOMPLETE)).isEmpty();
    }

    @Test
    void aLevelMerelyOverItsBudgetIsNotWorthSaying() {
        // Nine times the budget, and deliberately silent. Everything past the budget is kept on the layer
        // behind it by design, so a level larger than its budget is a correctly configured deployment —
        // reporting those would put a warning on nearly every pipeline, which is how a channel stops
        // being read. Only the line makes this rule different from one that warns on "over".
        List<Advisory> findings = NestSizingRules.review(
                batch(threeLevels("")),
                counted("customers", 900_000L, "policies", 1_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        assertThat(withCode(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET)).isEmpty();
    }

    @Test
    void aLevelJustPastTheLineIsReportedAndOneJustShortOfItIsNot() {
        // Either side of the same line, so what is pinned is the line rather than the direction. Exactly
        // at the budget's multiple is still silent: the line is what it takes to be past, not to reach.
        long line = DEPLOYMENT_BUDGET * NestSizingRules.FAR_EXCEEDS_MULTIPLE;

        assertThat(withCode(NestSizingRules.review(batch(threeLevels("")),
                counted("customers", line, "policies", 1_000L, "claims", 1_000L), DEPLOYMENT_BUDGET),
                NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET))
                .as("at the line").isEmpty();
        assertThat(withCode(NestSizingRules.review(batch(threeLevels("")),
                counted("customers", line + 1, "policies", 1_000L, "claims", 1_000L), DEPLOYMENT_BUDGET),
                NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET))
                .as("one row past the line").hasSize(1);
    }

    @Test
    void anUpstreamThatCannotBeNarrowedToOneTableIsAGapRatherThanNoRows() {
        // A regex names its tables only once there is a connection to resolve it against, so which tables
        // this level reads is not known offline — and neither is how much they hold. Sizing it as nothing
        // would report the widest levels there are as the ones with least to worry about.
        String regexRoot = """
                version: tapstate/v1
                kind: pipeline
                id: p
                source: src
                transforms:
                  - id: doc
                    type: nest
                    from: { c: /cust.*/, pol: policies, cl: claims }
                    root:
                      from: c
                      key: [customer_id]
                      embed:
                        - from: pol
                          on: { customer_id: customer_id }
                          as: array
                          path: policies
                          arrayKey: [policy_id]
                          embed:
                            - from: cl
                              on: { policy_id: policy_id }
                              as: array
                              path: claims
                              arrayKey: [claim_id]
                serve:
                  from: doc
                  query: [ { type: rest } ]
                """;

        List<Advisory> findings = NestSizingRules.review(
                batch(regexRoot),
                counted("customers", 1_000L, "policies", 1_000L, "claims", 1_000L),
                DEPLOYMENT_BUDGET);

        Advisory gap = at(findings, NestSizingError.CAPACITY_ESTIMATE_INCOMPLETE, "$root");
        assertThat(gap).as("the level whose tables are not known offline says so").isNotNull();
        assertThat(gap.params()).containsEntry("counted", 0L);
    }

    @Test
    void aLevelSizedFromSomeOfItsTablesSaysHowManyItCouldSee() {
        // Two sources can supply the same table name, so one level reads two tables and only one of them
        // was ever counted. What it found still stands and is still worth reporting — it is a floor, and a
        // floor already past the line is past it. Saying nothing until every table is counted would go
        // quiet on exactly the wide, half-discovered sources worth being loud about.
        String twoSources = """
                version: tapstate/v1
                kind: pipeline
                id: p
                source: [ src_a, src_b ]
                transforms:
                  - id: doc
                    type: nest
                    from: { c: customers, pol: policies, cl: claims }
                    root:
                      from: c
                      key: [customer_id]
                      embed:
                        - from: pol
                          on: { customer_id: customer_id }
                          as: array
                          path: policies
                          arrayKey: [policy_id]
                serve:
                  from: doc
                  query: [ { type: rest } ]
                """;
        Map<String, TapstateType> columns = Map.of("customer_id", TapstateType.STRING);
        Map<String, List<DiscoveredTable>> tables = Map.of(
                "src_a", List.of(new DiscoveredTable("customers", columns, 5_000_000L)),
                "src_b", List.of(new DiscoveredTable("customers", columns, null)));

        List<Advisory> findings = NestSizingRules.review(batch(twoSources), tables, DEPLOYMENT_BUDGET);

        Advisory gap = at(findings, NestSizingError.CAPACITY_ESTIMATE_INCOMPLETE, "$root");
        assertThat(gap).isNotNull();
        assertThat(gap.params()).containsEntry("counted", 1L).containsEntry("total", 2L);
        Advisory over = at(findings, NestSizingError.STATE_FAR_EXCEEDS_MEMORY_BUDGET, "$root");
        assertThat(over).as("a floor already past the line is still past it").isNotNull();
        assertThat(over.params()).containsEntry("estimatedEntries", 5_000_000L);
    }

    @Test
    void aPipelineWithoutANestIsNotSizedAtAll() {
        String plain = """
                version: tapstate/v1
                kind: pipeline
                id: p
                source: src
                transforms:
                  - id: keep
                    type: filter
                    from: [customers]
                    expr: 'true'
                serve:
                  from: keep
                  query: [ { type: rest } ]
                """;

        assertThat(NestSizingRules.review(batch(plain), counted("customers", null), DEPLOYMENT_BUDGET))
                .isEmpty();
    }
}
