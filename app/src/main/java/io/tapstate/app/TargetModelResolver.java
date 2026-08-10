package io.tapstate.app;

import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.RenameSpec;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.TableRename;
import io.tapstate.spi.sink.TargetField;
import io.tapstate.spi.sink.TargetTable;
import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SourceField;
import io.tapstate.spi.store.SourceTable;
import io.tapstate.spi.store.StorePort;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a sink's write-side target model from the source model discovery persisted for a connection. The
 * table structure a sink creates and the key an upsert matches on come from the upstream source's discovered
 * model, not from the events flowing through - so a target table is built by reading the persisted model for
 * the source the sink reads and mapping the discovered {@link SourceTable} onto a {@link TargetTable}.
 *
 * <p>A source may select several tables. When a table's schema has never been discovered, it is absent from
 * the resolved map and the sink falls back to a bare table id for that stream.
 */
final class TargetModelResolver {

    private final StorePort storePort;

    TargetModelResolver(StorePort storePort) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
    }

    /** Resolves one target model per selected source table across the pipeline, in source and discovery order. */
    Optional<TargetTable> resolve(PipelineResource pipeline) {
        return resolveAll(pipeline).values().stream().findFirst();
    }

    /** Resolves one target model per selected source table, preserving source and discovery order. */
    Map<String, TargetTable> resolveAll(PipelineResource pipeline) {
        Map<String, TargetTable> targets = new LinkedHashMap<>();
        for (String sourceId : pipeline.sources()) {
            SourceResource source = StoredArtifacts.requireSource(storePort.artifacts(), sourceId);
            SourceCaptureResolution resolution = SourceCaptureResolution.of(source, discoveredModel(sourceId));
            for (String table : resolution.tables()) {
                discoveredTable(sourceId, table).map(TargetModelResolver::toTargetTable)
                        .ifPresent(target -> targets.putIfAbsent(table, target));
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(targets));
    }

    /** Resolves one target model per selected table of the source that feeds a sink. */
    Map<String, TargetTable> resolveAll(String sourceId) {
        Map<String, TargetTable> targets = new LinkedHashMap<>();
        SourceResource source = StoredArtifacts.requireSource(storePort.artifacts(), sourceId);
        SourceCaptureResolution resolution = SourceCaptureResolution.of(source, discoveredModel(sourceId));
        for (String table : resolution.tables()) {
            discoveredTable(sourceId, table).map(TargetModelResolver::toTargetTable)
                    .ifPresent(target -> targets.put(table, target));
        }
        return Collections.unmodifiableMap(targets);
    }

    /** Resolves the first selected table for callers that still require a single target. */
    ResolvedTarget resolve(String sourceId) {
        SourceResource source = StoredArtifacts.requireSource(storePort.artifacts(), sourceId);
        String table = SourceCaptureResolution.of(source, discoveredModel(sourceId)).table();
        return new ResolvedTarget(table, resolveAll(sourceId).get(table));
    }

    /** One source's table paired with the target model discovered for it, or a null model when none was. */
    record ResolvedTarget(String sourceTable, TargetTable target) {
    }

    /** The named table in the source's persisted discovery model, or empty when neither is present. */
    private Optional<SourceTable> discoveredTable(String connectionId, String table) {
        return storePort.schemas().get(connectionId)
                .map(DiscoveredSourceModel::model)
                .flatMap(model -> model.tables().stream().filter(t -> t.name().equals(table)).findFirst());
    }

    private io.tapstate.spi.store.SourceModel discoveredModel(String sourceId) {
        return storePort.schemas().get(sourceId).map(DiscoveredSourceModel::model).orElse(null);
    }

    /**
     * Maps one discovered source table onto the write-side target table a sink writes: each field carries over
     * with its source-declared type, and a field named in the table's primary key is flagged so the sink keys
     * an upsert on it. The sink keys the upsert in target-field order, so the key columns lead in the source's
     * key order and the remaining fields follow in source order.
     */
    static TargetTable toTargetTable(SourceTable source) {
        List<String> primaryKey = source.primaryKey();
        List<TargetField> fields = new ArrayList<>(source.fields().size());
        for (String keyColumn : primaryKey) {
            SourceField field = field(source, keyColumn);
            fields.add(new TargetField(field.name(), field.dataType(), true));
        }
        for (SourceField field : source.fields()) {
            if (!primaryKey.contains(field.name())) {
                fields.add(new TargetField(field.name(), field.dataType(), false));
            }
        }
        return new TargetTable(source.name(), fields);
    }

    /**
     * The target table one sync element writes: the resolved model under the name its rename gives the source
     * table. A rename with no model behind it still names a table, so the sink is pointed at the renamed one
     * rather than at whatever table id the first row happens to carry.
     */
    static TargetTable rename(TargetTable target, String sourceName, RenameSpec rename) {
        if (rename == null) {
            return target;
        }
        List<TargetField> fields = target == null ? List.of() : target.fields();
        return new TargetTable(TableRename.apply(sourceName, rename), fields);
    }

    /** Applies one sync element's rename rules to every source table that can reach that sink. */
    static Map<String, TargetTable> renameAll(
            Map<String, TargetTable> targets, Iterable<String> sourceTables, RenameSpec rename) {
        if (rename == null) {
            return targets;
        }
        Map<String, TargetTable> renamed = new LinkedHashMap<>();
        for (String sourceTable : sourceTables) {
            renamed.put(sourceTable, rename(targets.get(sourceTable), sourceTable, rename));
        }
        return Collections.unmodifiableMap(renamed);
    }

    /** The discovered field a key column names; a key naming no discovered field is a broken source model. */
    private static SourceField field(SourceTable source, String name) {
        for (SourceField field : source.fields()) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        throw new IllegalStateException(
                "primary key column '" + name + "' is not among the fields of discovered table '" + source.name() + "'");
    }
}
