package io.tapstate.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.common.TapstateType;
import io.tapstate.core.dsl.DslError;
import io.tapstate.core.dsl.DslException;
import io.tapstate.core.model.Resource;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SchemaStore;
import io.tapstate.spi.store.SourceField;
import io.tapstate.spi.store.SourceModel;
import io.tapstate.spi.store.SourceTable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

/**
 * Apply judges a batch's row expressions against the columns its sources were discovered to hold.
 * This is where the type check has anything to work with: the offline check sees only the document,
 * while apply can reach the schema store and read what discovery resolved each column to.
 *
 * <p>The refusal has to land before anything is written. An apply that refused the pipeline but
 * stored it anyway would leave exactly the artifact the refusal exists to keep out.
 */
class ApplyServiceRowExpressionTypeTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-15T10:15:30Z"), ZoneOffset.UTC);

    private static final String SOURCE = """
            version: tapstate/v1
            kind: source
            id: src_orders
            connector: mysql
            config: { host: 10.10.0.5, username: u, password: p }
            mode: cdc
            tables: [ orders ]
            """;

    private final InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
    private final InMemorySchemaStore schemas = new InMemorySchemaStore();
    private final ApplyService service = new ApplyService(
            TapstateCatalog::load, artifacts, new AuditGate(record -> { }, FIXED_CLOCK), schemas);

    private static String pipeline(String expr) {
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

    private List<ArtifactDraft> batch(String expr) {
        return List.of(new ArtifactDraft("src_orders.tap.yml", SOURCE),
                new ArtifactDraft("orders_out.tap.yml", pipeline(expr)));
    }

    /** Records the discovery of one table's columns for a connection, as discovery would have. */
    private void discovered(String connectionId, String table, Map<String, TapstateType> columns) {
        discovered(connectionId, "mysql", table, columns);
    }

    /** The same, for a discovery that ran through a named connector rather than the source's own. */
    private void discovered(
            String connectionId, String connectorId, String table, Map<String, TapstateType> columns) {
        List<SourceField> fields = new ArrayList<>();
        columns.forEach((name, type) -> fields.add(new SourceField(name, name + "_native", type)));
        schemas.save(new DiscoveredSourceModel(connectionId, connectorId, 0L,
                new SourceModel(List.of(new SourceTable(table, fields, List.of(), List.of())))));
    }

    @Test
    @DisplayName("an expression computing on a column whose type cannot survive it is refused by apply")
    void applyRefusesAnUnsupportedColumnType() {
        discovered("src_orders", "orders", Map.of("amount", TapstateType.DECIMAL));

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> service.apply("tester", batch("after.amount * 2 > 0")));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED);
        assertThat(thrown.args()).containsEntry("column", "amount");
    }

    @Test
    @DisplayName("a refused apply writes nothing, so the refused pipeline is not left stored")
    void aRefusedApplyStoresNothing() {
        discovered("src_orders", "orders", Map.of("amount", TapstateType.DECIMAL));

        catchThrowableOfType(DslException.class, () -> service.apply("tester", batch("after.amount * 2 > 0")));

        assertThat(artifacts.get("orders_out")).isEmpty();
        assertThat(artifacts.get("src_orders")).isEmpty();
        assertThat(artifacts.saved).isEmpty();
    }

    /**
     * The write-free validate verb plans the batch, so it reaches this check too and an author asking
     * "would this apply?" is told the same no. The verb answers with a diagnostic rather than letting
     * the refusal escape, which is what keeps a refused expression a reported verdict instead of a
     * server fault. Moving the check onto the write path alone would silently take it out of this
     * answer, so the case is pinned on the verb rather than only on apply.
     */
    @Test
    @DisplayName("the write-free validate verb reports the same refusal, as a diagnostic and not a throw")
    void validateReportsTheRefusalAsADiagnostic() {
        discovered("src_orders", "orders", Map.of("amount", TapstateType.DECIMAL));

        ArtifactValidationResult result = service.validate(batch("after.amount * 2 > 0"));

        assertThat(result.valid()).isFalse();
        assertThat(result.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED.code());
            assertThat(diagnostic.params()).containsEntry("column", "amount");
        });
        assertThat(artifacts.saved).isEmpty();
    }

    @Test
    @DisplayName("the same expression over a lossless numeric column applies and is stored")
    void applyAcceptsALosslessColumnType() {
        discovered("src_orders", "orders", Map.of("amount", TapstateType.INT64));

        assertThatCode(() -> service.apply("tester", batch("after.amount * 2 > 0")))
                .doesNotThrowAnyException();
        assertThat(artifacts.get("orders_out")).isPresent();
    }

    @Test
    @DisplayName("reading a row field from a source apply cannot find a model for is refused, naming it")
    void anUndiscoveredSourceIsRefused() {
        DslException thrown = catchThrowableOfType(DslException.class,
                () -> service.apply("tester", batch("after.amount * 2 > 0")));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY);
        assertThat(thrown.args()).containsEntry("source", "src_orders");
        assertThat(artifacts.saved).isEmpty();
    }

    @Test
    @DisplayName("an expression reading no row field applies without any discovery")
    void anExpressionWithoutRowAccessNeedsNoDiscovery() {
        assertThatCode(() -> service.apply("tester", batch("op == 'i'"))).doesNotThrowAnyException();
    }

    /**
     * The column here is one the expression survives, so a model that was consulted would let this
     * apply through. It is refused instead, which is the only outcome that shows the model was not
     * consulted at all - the assertion would pass on a green run if it merely named some other code.
     *
     * <p>A model carries the types the connector that produced it declares, and a different connector
     * spells its types differently. So a model discovered through one connector says nothing about a
     * source now configured to read through another, even where both kept the connection's id.
     */
    @Test
    @DisplayName("a model discovered through a different connector does not count as this source's")
    void aModelDiscoveredThroughAnotherConnectorIsNotConsulted() {
        discovered("src_orders", "mongodb", "orders", Map.of("amount", TapstateType.INT64));

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> service.apply("tester", batch("after.amount * 2 > 0")));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY);
        assertThat(thrown.args()).containsEntry("source", "src_orders");
        assertThat(artifacts.saved).isEmpty();
    }

    @Test
    @DisplayName("one column discovered as two types across a source's tables is refused as unresolved")
    void conflictingTypesAcrossTablesAreRefused() {
        List<SourceTable> tables = List.of(
                new SourceTable("orders",
                        List.of(new SourceField("amount", "bigint", TapstateType.INT64)), List.of(), List.of()),
                new SourceTable("orders_archive",
                        List.of(new SourceField("amount", "varchar", TapstateType.STRING)), List.of(), List.of()));
        schemas.save(new DiscoveredSourceModel("src_orders", "mysql", 0L, new SourceModel(tables)));

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> service.apply("tester", batch("after.amount > 0")));

        assertThat(thrown.code()).isEqualTo(DslError.ROW_EXPRESSION_TYPE_UNKNOWN);
    }

    // ---- doubles -------------------------------------------------------------------------

    private static final class InMemoryArtifactStore implements ArtifactStore {
        final Map<String, Resource> byId = new LinkedHashMap<>();
        final List<Resource> saved = new ArrayList<>();

        @Override
        public void saveAll(List<Resource> artifacts) {
            saved.addAll(artifacts);
            artifacts.forEach(r -> byId.put(r.id(), r));
        }

        @Override
        public Optional<Resource> get(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Resource> list() {
            return List.copyOf(byId.values());
        }
    }

    private static final class InMemorySchemaStore implements SchemaStore {
        private final Map<String, DiscoveredSourceModel> byConnection = new HashMap<>();

        @Override
        public void save(DiscoveredSourceModel discovered) {
            byConnection.put(discovered.connectionId(), discovered);
        }

        @Override
        public Optional<DiscoveredSourceModel> get(String connectionId) {
            return Optional.ofNullable(byConnection.get(connectionId));
        }
    }
}
