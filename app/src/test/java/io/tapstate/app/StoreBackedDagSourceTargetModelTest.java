package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.function.SupplierEx;
import io.tapstate.core.model.FromClause;
import io.tapstate.core.model.FromRef;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.RenameCase;
import io.tapstate.core.model.RenameSpec;
import io.tapstate.core.model.ServeBlock;
import io.tapstate.core.model.SourceMode;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.Step;
import io.tapstate.core.model.SyncElement;
import io.tapstate.core.model.TableRef;
import io.tapstate.core.model.TransformBody;
import io.tapstate.spi.sink.SinkWriter;
import io.tapstate.spi.sink.TargetField;
import io.tapstate.spi.sink.TargetTable;
import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SourceField;
import io.tapstate.spi.store.SourceModel;
import io.tapstate.spi.store.SourceTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for how the store-backed DAG source feeds a sink's resolved target model: the write-side target
 * table resolved from the pipeline source's discovered model is what reaches the sink binder, so the sink
 * creates the target by that model and keys an upsert on its primary key. When no model has been discovered
 * the sink is bound with no target and falls back to a bare table id.
 */
class StoreBackedDagSourceTargetModelTest {

    @Test
    void feeds_the_resolved_target_model_to_the_sink_binder() {
        InMemoryStorePort store = seededPipeline();
        store.schemas().save(discovered("orders_src", "mysql", new SourceTable(
                "orders",
                List.of(new SourceField("id", "INT"), new SourceField("amount", "DECIMAL")),
                List.of("id"),
                List.of())));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(new TargetTable("orders", List.of(
                new TargetField("id", "INT", true),
                new TargetField("amount", "DECIMAL", false))));
    }

    @Test
    void binds_a_null_target_when_the_source_schema_was_never_discovered() {
        InMemoryStorePort store = seededPipeline();
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly((TargetTable) null);
    }

    @Test
    void applies_explicit_rename_without_a_discovered_model() {
        InMemoryStorePort store = seededPipeline(new SyncElement(
                "sync_1", "orders_dest", null,
                new RenameSpec(Map.of("orders", "player_address"), null, null, null), null, null));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(new TargetTable("player_address", List.of()));
    }

    @Test
    void gives_each_sync_its_own_renamed_target_model() {
        InMemoryStorePort store = seededPipeline(
                new SyncElement("mongo", "orders_dest", null,
                        new RenameSpec(Map.of("orders", "player_address"), null, null, null), null, null),
                new SyncElement("warehouse", "orders_dest", null,
                        new RenameSpec(null, RenameCase.LOWER, "ods_", null), null, null));
        store.schemas().save(discovered("orders_src", "mysql", new SourceTable(
                "orders", List.of(new SourceField("id", "INT")), List.of("id"), List.of())));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(
                new TargetTable("player_address", List.of(new TargetField("id", "INT", true))),
                new TargetTable("ods_orders", List.of(new TargetField("id", "INT", true))));
    }

    @Test
    void renames_with_the_table_of_the_source_the_serve_block_reads() {
        InMemoryStorePort store = seededMultiSourcePipeline(FromRef.literal("address_src"));
        store.schemas().save(discovered("address_src", "mysql", new SourceTable(
                "PlayerAddress", List.of(new SourceField("id", "INT")), List.of("id"), List.of())));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(new TargetTable(
                "player_address", List.of(new TargetField("id", "INT", true))));
    }

    @Test
    void binds_the_model_of_the_source_the_serve_block_reads_not_the_first_discovered_one() {
        InMemoryStorePort store = seededMultiSourcePipeline(FromRef.literal("address_src"));
        store.schemas().save(discovered("orders_src", "mysql", new SourceTable(
                "orders", List.of(new SourceField("total", "DECIMAL")), List.of("total"), List.of())));
        store.schemas().save(discovered("address_src", "mysql", new SourceTable(
                "PlayerAddress", List.of(new SourceField("id", "INT")), List.of("id"), List.of())));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(new TargetTable(
                "player_address", List.of(new TargetField("id", "INT", true))));
    }

    @Test
    void renames_with_the_table_a_serve_block_reaches_through_a_transform_chain() {
        InMemoryStorePort store = seededMultiSourcePipeline(FromRef.literal("keep_recent"),
                Step.inline("keep_recent", FromClause.list(FromRef.literal("address_src")),
                        new TransformBody.Filter("true"), null, null));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(new TargetTable("player_address", List.of()));
    }

    @Test
    void renames_with_the_table_a_serve_block_names_directly() {
        InMemoryStorePort store = seededMultiSourcePipeline(FromRef.literal("PlayerAddress"));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        assertThat(bound).containsExactly(new TargetTable("player_address", List.of()));
    }

    @Test
    void falls_back_to_the_first_source_when_a_step_merges_several_upstreams() {
        InMemoryStorePort store = seededMultiSourcePipeline(FromRef.literal("merged"),
                Step.inline("merged", FromClause.list(FromRef.literal("orders"), FromRef.literal("PlayerAddress")),
                        new TransformBody.Union(), null, null));
        List<TargetTable> bound = new ArrayList<>();

        new StoreBackedDagSource(store, capturingBinder(bound)).dagFor("p");

        // orders_src leads the source list and its table is not in the rename map, so the name stays put.
        assertThat(bound).containsExactly(new TargetTable("orders", List.of()));
    }

    /**
     * The remaining shapes that name no single source. Each is asked of the decision directly rather than
     * through a built DAG: the linear L1 builder refuses a regex reference and an unresolved token outright,
     * so no pipeline carrying one ever reaches a sink binding to assert on.
     */
    @Test
    void names_no_source_for_a_reference_that_does_not_narrow_to_one() {
        PipelineResource merging = pipelineOf(FromRef.literal("merged"),
                Step.inline("merged", FromClause.list(FromRef.literal("orders"), FromRef.literal("PlayerAddress")),
                        new TransformBody.Union(), null, null));
        Map<String, String> byTable = Map.of("orders", "orders_src", "PlayerAddress", "address_src");

        assertThat(StoreBackedDagSource.servedSourceId(pipelineOf(FromRef.regex(".*")), byTable))
                .as("a regex spans the universe").isEqualTo("orders_src");
        assertThat(StoreBackedDagSource.servedSourceId(pipelineOf(FromRef.literal("nowhere")), byTable))
                .as("a token naming neither a source nor a step, with no transforms to search").isEqualTo("orders_src");
        assertThat(StoreBackedDagSource.servedSourceId(merging, byTable))
                .as("a union merges several upstreams").isEqualTo("orders_src");
        assertThat(StoreBackedDagSource.servedSourceId(
                pipelineOf(FromRef.literal("nowhere"), (Step) merging.transforms().getFirst()), byTable))
                .as("a token matching none of the steps searched").isEqualTo("orders_src");
    }

    // ---- fixtures ----------------------------------------------------------------------

    private static InMemoryStorePort seededPipeline() {
        return seededPipeline(new SyncElement("sync_1", "orders_dest", null, null, null, null));
    }

    private static InMemoryStorePort seededPipeline(SyncElement... syncElements) {
        InMemoryStorePort store = new InMemoryStorePort();
        store.artifacts().save(new SourceResource("orders_src", null, "mysql", Map.of("host", "h"),
                SourceMode.CDC, List.of(TableRef.literal("orders")), null, null, null));
        store.artifacts().save(new SourceResource("orders_dest", null, "mongodb", Map.of("uri", "u"),
                null, null, null, null, null));
        store.artifacts().save(new PipelineResource("p", null, List.of("orders_src"), null, null,
                new ServeBlock.Inline(null, FromRef.literal("orders_src"),
                        List.of(syncElements), null, null),
                null, null));
        OpenRingGenerations.forSources(store, "orders_src");
        return store;
    }

    /**
     * A two-source pipeline whose sink renames {@code PlayerAddress}, so which source the serve block is
     * pointed at decides the bound target: only {@code address_src} carries that table.
     */
    private static InMemoryStorePort seededMultiSourcePipeline(FromRef serveFrom, Step... transforms) {
        InMemoryStorePort store = new InMemoryStorePort();
        store.artifacts().save(new SourceResource("orders_src", null, "mysql", Map.of("host", "h"),
                SourceMode.CDC, List.of(TableRef.literal("orders")), null, null, null));
        store.artifacts().save(new SourceResource("address_src", null, "mysql", Map.of("host", "h"),
                SourceMode.CDC, List.of(TableRef.literal("PlayerAddress")), null, null, null));
        store.artifacts().save(new SourceResource("orders_dest", null, "mongodb", Map.of("uri", "u"),
                null, null, null, null, null));
        store.artifacts().save(pipelineOf(serveFrom, transforms));
        return store;
    }

    /** That pipeline on its own: two sources, and one sink renaming {@code PlayerAddress}. */
    private static PipelineResource pipelineOf(FromRef serveFrom, Step... transforms) {
        return new PipelineResource("p", null, List.of("orders_src", "address_src"),
                transforms.length == 0 ? null : List.of(transforms), null,
                new ServeBlock.Inline(null, serveFrom, List.of(new SyncElement(
                        "sync_1", "orders_dest", null,
                        new RenameSpec(Map.of("PlayerAddress", "player_address"), null, null, null), null, null)),
                        null, null),
                null, null);
    }

    /** A binder that records the target it is handed and returns a sink supplier the build never opens. */
    private static StoreBackedDagSource.SinkWriterBinder capturingBinder(List<TargetTable> bound) {
        return (connectorId, settings, writeMode, ddl, target) -> {
            bound.add(target);
            return (SupplierEx<SinkWriter>) () -> null;
        };
    }

    private static DiscoveredSourceModel discovered(String connectionId, String connectorId, SourceTable table) {
        return new DiscoveredSourceModel(connectionId, connectorId, 0L, new SourceModel(List.of(table)));
    }
}
