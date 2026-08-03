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
 * Judging a batch's row expressions against the columns their sources were discovered to hold. This
 * runs where a source model exists — never in the offline check, which has none — and refuses an
 * expression that cannot be evaluated on what the columns actually are, rather than letting it reach
 * the runtime and fail there.
 *
 * <p>These cases are all about the type verdict, so every source they read has been discovered. What
 * happens when one has not is the discovery obligation's own concern.
 */
class RowExpressionTypeRulesTest {

    private static final String SOURCE = """
            version: tapstate/v1
            kind: source
            id: src_orders
            connector: mysql
            config: { host: 10.10.0.5, username: u, password: p }
            mode: cdc
            tables: [ orders ]
            """;

    private static List<Resource> batch(String... documents) {
        DslParser parser = new DslParser();
        List<Resource> resources = new ArrayList<>();
        resources.add(parser.parse(SOURCE));
        for (String document : documents) {
            resources.add(parser.parse(document));
        }
        return resources;
    }

    /** A pipeline whose one filter step carries {@code expr}. */
    private static String filterPipeline(String expr) {
        return """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_orders
                transforms:
                  - { id: keep, from: [orders], type: filter, expr: "%s" }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_orders, write_mode: upsert } ]
                """.formatted(expr);
    }

    private static Map<String, Map<String, TapstateType>> model(Object... columnsAndTypes) {
        Map<String, TapstateType> columns = new LinkedHashMap<>();
        for (int i = 0; i < columnsAndTypes.length; i += 2) {
            columns.put((String) columnsAndTypes[i], (TapstateType) columnsAndTypes[i + 1]);
        }
        return Map.of("src_orders", columns);
    }

    // ---- a column whose type cannot survive the expression ------------------------------

    @Test
    @DisplayName("computing on an exact fixed-point column is refused, naming the column and the type")
    void decimalComputationIsRefused() {
        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(filterPipeline("after.amount * 2 > 0")), model("amount", TapstateType.DECIMAL)));

        assertThat(thrown.code())
                .isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED);
        assertThat(thrown.args())
                .containsEntry("column", "amount")
                .containsEntry("type", "DECIMAL");
        assertThat(thrown.path()).isEqualTo("transforms[0].expr");
    }

    @Test
    @DisplayName("a lossless numeric column passes, so the gate is not refusing numbers as a class")
    void losslessNumericPasses() {
        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(filterPipeline("after.amount * 2 > 0")), model("amount", TapstateType.INT64)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("carrying a fixed-point column through unchanged is not a loss and passes")
    void decimalCarriedThroughPasses() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_orders
                transforms:
                  - id: project
                    from: [orders]
                    type: map
                    fields: { total: "=after.amount" }
                serve:
                  from: project
                  sync: [ { id: out, source: src_orders, write_mode: upsert } ]
                """;

        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(pipeline), model("amount", TapstateType.DECIMAL))).doesNotThrowAnyException();
    }

    // ---- a column whose type nothing resolved -------------------------------------------

    @Test
    @DisplayName("any reference to a column with no resolved type is refused, not only computing on it")
    void unresolvedColumnTypeIsRefusedOnAnyReference() {
        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(filterPipeline("has(after.amount)")), model("amount", TapstateType.UNKNOWN)));

        assertThat(thrown.code())
                .isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNKNOWN);
        assertThat(thrown.args()).containsEntry("column", "amount");
    }

    @Test
    @DisplayName("one column resolving to two types across sources is as good as unresolved, and refused")
    void conflictingTypesAcrossSourcesAreRefused() {
        String secondSource = """
                version: tapstate/v1
                kind: source
                id: src_archive
                connector: mysql
                config: { host: 10.10.0.6, username: u, password: p }
                mode: cdc
                tables: [ archive ]
                """;
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: [ src_orders, src_archive ]
                transforms:
                  - { id: keep, from: [orders, archive], type: filter, expr: "after.amount > 0" }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_orders, write_mode: upsert } ]
                """;
        Map<String, Map<String, TapstateType>> columns = new LinkedHashMap<>();
        columns.put("src_orders", Map.of("amount", TapstateType.INT64));
        columns.put("src_archive", Map.of("amount", TapstateType.STRING));

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> RowExpressionTypeRules.validate(batch(secondSource, pipeline), columns));

        assertThat(thrown.code())
                .isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNKNOWN);
    }

    // ---- what the gate must leave alone --------------------------------------------------

    @Test
    @DisplayName("a column the model does not carry stays dyn, since a sampled model may simply miss it")
    void columnAbsentFromTheModelPasses() {
        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(filterPipeline("after.absent * 2 > 0")), model("amount", TapstateType.DECIMAL)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an expression reading no row field is judged without any source model at all")
    void expressionWithoutRowAccessNeedsNoModel() {
        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(filterPipeline("op == 'i'")), Map.of())).doesNotThrowAnyException();
    }

    // ---- every place an expression can hide ----------------------------------------------

    @Test
    @DisplayName("a computed field of a map projection is judged")
    void mapProjectionFieldIsJudged() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_orders
                transforms:
                  - id: project
                    from: [orders]
                    type: map
                    fields: { doubled: "=after.amount * 2" }
                serve:
                  from: project
                  sync: [ { id: out, source: src_orders, write_mode: upsert } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(pipeline), model("amount", TapstateType.DECIMAL)));

        assertThat(thrown.code())
                .isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED);
        assertThat(thrown.path()).isEqualTo("transforms[0].fields.doubled");
    }

    @Test
    @DisplayName("a push element's whole-payload projection is judged")
    void pushFormatIsJudged() {
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_orders
                transforms:
                  - { id: keep, from: [orders], type: filter, expr: "op == 'i'" }
                serve:
                  from: keep
                  push: [ { id: topic_out, source: src_orders, topic: t, format: "=after.amount * 2" } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(pipeline), model("amount", TapstateType.DECIMAL)));

        assertThat(thrown.code())
                .isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED);
        assertThat(thrown.path()).isEqualTo("serve.push[0].format");
    }

    @Test
    @DisplayName("an expression inside a reused transform definition is judged where the pipeline uses it")
    void reusedTransformDefinitionIsJudged() {
        String definition = """
                version: tapstate/v1
                kind: transform
                id: keep_big
                type: filter
                expr: "after.amount > 0"
                """;
        String pipeline = """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_orders
                transforms:
                  - { id: keep, from: [orders], use: keep_big }
                serve:
                  from: keep
                  sync: [ { id: out, source: src_orders, write_mode: upsert } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class, () -> RowExpressionTypeRules.validate(
                batch(definition, pipeline), model("amount", TapstateType.DECIMAL)));

        assertThat(thrown.code())
                .isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED);
    }

    @Test
    @DisplayName("a transform definition no pipeline uses has no source to judge it against, and is left alone")
    void unusedTransformDefinitionIsLeftAlone() {
        String definition = """
                version: tapstate/v1
                kind: transform
                id: keep_big
                type: filter
                expr: "after.amount * 2 > 0"
                """;

        assertThatCode(() -> RowExpressionTypeRules.validate(
                batch(definition), model("amount", TapstateType.DECIMAL))).doesNotThrowAnyException();
    }
}
