package io.tapstate.core.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tapstate.core.common.TapstateType;
import io.tapstate.core.model.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading a row field obliges the author to have discovered the sources that feed it. Without the
 * obligation the type check would be dead weight in normal use: an author applies first and discovers
 * afterwards, so at apply time there would be no model to judge against and every expression would
 * pass unexamined.
 *
 * <p>The obligation is scoped to what can actually reach the expression, not to everything the
 * pipeline reads. A pipeline merging two sources, with an expression on a branch fed by only one of
 * them, must not demand the other be discovered — that would be telling the author to go do something
 * that has no bearing on the expression being judged.
 */
class RowExpressionDiscoveryRulesTest {

    private static final String SRC_A = """
            version: tapstate/v1
            kind: source
            id: src_a
            connector: mysql
            config: { host: 10.10.0.5, username: u, password: p }
            mode: cdc
            tables: [ orders ]
            """;

    private static final String SRC_B = """
            version: tapstate/v1
            kind: source
            id: src_b
            connector: mysql
            config: { host: 10.10.0.6, username: u, password: p }
            mode: cdc
            tables: [ archive ]
            """;

    private static List<Resource> batch(String... documents) {
        DslParser parser = new DslParser();
        List<Resource> resources = new ArrayList<>();
        resources.add(parser.parse(SRC_A));
        resources.add(parser.parse(SRC_B));
        for (String document : documents) {
            resources.add(parser.parse(document));
        }
        return resources;
    }

    /** Sources named here have been discovered; any other source in the batch has not. */
    private static Map<String, Map<String, TapstateType>> discovered(String... sourceIds) {
        Map<String, Map<String, TapstateType>> columns = new LinkedHashMap<>();
        for (String sourceId : sourceIds) {
            columns.put(sourceId, Map.of("amount", TapstateType.INT64));
        }
        return columns;
    }

    private static String singleSourcePipeline(String expr) {
        return """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_a
                transforms:
                  - { id: keep, from: [orders], type: filter, expr: "%s" }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """.formatted(expr);
    }

    // ---- the obligation ------------------------------------------------------------------

    @Test
    @DisplayName("reading a row field from a source that has never been discovered is refused")
    void readingARowFieldWithoutDiscoveryIsRefused() {
        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(singleSourcePipeline("after.amount > 0")), discovered()));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY);
        assertThat(thrown.args()).containsEntry("source", "src_a");
        assertThat(thrown.path()).isEqualTo("transforms[0].expr");
    }

    @Test
    @DisplayName("the same expression passes once that source has been discovered")
    void readingARowFieldAfterDiscoveryPasses() {
        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(singleSourcePipeline("after.amount > 0")), discovered("src_a")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an expression reading no row field is free of the obligation entirely")
    void anExpressionWithoutRowAccessNeedsNoDiscovery() {
        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(singleSourcePipeline("op == 'i'")), discovered())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a pipeline carrying no expression at all is free of it too")
    void aPipelineWithoutExpressionsNeedsNoDiscovery() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_a
                serve:
                  from: orders
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """;

        assertThatCode(() -> RowExpressionTypeRules.validate(batch(pipeline), discovered()))
                .doesNotThrowAnyException();
    }

    // ---- the obligation is scoped to what reaches the expression --------------------------

    @Test
    @DisplayName("a source the expression's branch cannot reach is not required to be discovered")
    void anUnrelatedSourceIsNotRequired() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_a, src_b ]
                transforms:
                  - { id: keep, from: [orders], type: filter, expr: "after.amount > 0" }
                  - { id: merge, from: [keep, archive], type: union }
                serve:
                  from: merge
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """;

        assertThatCode(() -> RowExpressionTypeRules.validate(batch(pipeline), discovered("src_a")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a source the branch does reach is required, and the refusal names that source")
    void theReachableSourceIsRequired() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_a, src_b ]
                transforms:
                  - { id: keep, from: [archive], type: filter, expr: "after.amount > 0" }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> RowExpressionTypeRules.validate(batch(pipeline), discovered("src_a")));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY);
        assertThat(thrown.args()).containsEntry("source", "src_b");
    }

    @Test
    @DisplayName("the obligation follows a chain of steps back to the sources that feed it")
    void theObligationFollowsAStepChain() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_a, src_b ]
                transforms:
                  - { id: first, from: [archive], type: union }
                  - { id: second, from: [first], type: filter, expr: "after.amount > 0" }
                serve:
                  from: second
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> RowExpressionTypeRules.validate(batch(pipeline), discovered("src_a")));

        assertThat(thrown.args()).containsEntry("source", "src_b");
    }

    @Test
    @DisplayName("a source-qualified reference narrows the obligation to that one source")
    void aQualifiedReferenceNarrowsToOneSource() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_a, src_b ]
                transforms:
                  - { id: keep, from: [src_a.orders], type: filter, expr: "after.amount > 0" }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """;

        assertThatCode(() -> RowExpressionTypeRules.validate(batch(pipeline), discovered("src_a")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a push format's upstream is what serve reads, not the connection it pushes to")
    void aPushFormatIsJudgedAgainstWhatServeReads() {
        // src_b is the egress connection: rows do not come from it, so it owes no discovery. Reading
        // push.source as the upstream would demand it and refuse a correct pipeline.
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_a, src_b ]
                transforms:
                  - { id: keep, from: [orders], type: union }
                serve:
                  from: keep
                  push: [ { id: out, source: src_b, topic: t, format: "=after.amount" } ]
                """;

        assertThatCode(() -> RowExpressionTypeRules.validate(batch(pipeline), discovered("src_a")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reference nothing can be proven about requires every source the pipeline reads")
    void anUnprovableReferenceRequiresEverySource() {
        // A regex selects tables that cannot be enumerated offline, so which source feeds the step is
        // unknowable; demanding all of them is the only answer that cannot let an undiscovered one
        // through.
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_a, src_b ]
                transforms:
                  - { id: keep, from: ["/^ord.*/"], type: filter, expr: "after.amount > 0" }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_a, write_mode: upsert } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> RowExpressionTypeRules.validate(batch(pipeline), discovered("src_a")));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY);
        assertThat(thrown.args()).containsEntry("source", "src_b");
    }

    // ---- the obligation runs before the type judgment --------------------------------------

    @Test
    @DisplayName("an undiscovered source is reported as such, not as a column with no resolved type")
    void discoveryIsReportedBeforeTheTypeVerdict() {
        // Both would refuse, but only one tells the author what to do next: the column has no resolved
        // type precisely because the source was never discovered.
        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(singleSourcePipeline("after.amount > 0")), discovered()));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY);
    }
}
