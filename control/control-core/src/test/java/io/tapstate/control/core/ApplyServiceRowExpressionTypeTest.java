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
        List<SourceField> fields = new ArrayList<>();
        columns.forEach((name, type) -> fields.add(new SourceField(name, name + "_native", type)));
        schemas.save(new DiscoveredSourceModel(connectionId, "mysql", 0L,
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

    @Test
    @DisplayName("the same expression over a lossless numeric column applies and is stored")
    void applyAcceptsALosslessColumnType() {
        discovered("src_orders", "orders", Map.of("amount", TapstateType.INT64));

        assertThatCode(() -> service.apply("tester", batch("after.amount * 2 > 0")))
                .doesNotThrowAnyException();
        assertThat(artifacts.get("orders_out")).isPresent();
    }

    @Test
    @DisplayName("a source that has never been discovered refuses nothing at this gate")
    void anUndiscoveredSourceIsNotRefusedHere() {
        assertThatCode(() -> service.apply("tester", batch("after.amount * 2 > 0")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an expression reading no row field applies without any discovery")
    void anExpressionWithoutRowAccessNeedsNoDiscovery() {
        assertThatCode(() -> service.apply("tester", batch("op == 'i'"))).doesNotThrowAnyException();
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
