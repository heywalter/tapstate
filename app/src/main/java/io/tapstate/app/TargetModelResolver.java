package io.tapstate.app;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a sink's write-side target model from the source model discovery persisted for a connection. The
 * table structure a sink creates and the key an upsert matches on come from the upstream source's discovered
 * model, not from the events flowing through - so a target table is built by reading the persisted model for
 * the pipeline's source and mapping the discovered {@link SourceTable} onto a {@link TargetTable}.
 *
 * <p>L1 shape: a pipeline reads a single source of a single table, so the resolved target is that table. When
 * the source's schema has never been discovered the target is absent, and the sink falls back to a bare table
 * id and lets the connector infer structure and keying.
 */
final class TargetModelResolver {

    private final StorePort storePort;

    TargetModelResolver(StorePort storePort) {
        this.storePort = Objects.requireNonNull(storePort, "storePort");
    }

    /**
     * Resolves the write-side target model for the source a sink reads: that source's single table looked up
     * in its persisted model and mapped to a target table. The caller names the source, because which source
     * feeds a sink is a topology question - resolving it here from the pipeline's source list would bind a
     * sink to whichever source merely happens to have been discovered first.
     *
     * <p>The source table travels back with the model whether or not one was discovered, since sink-side
     * rename rules key off the table name alone. {@code target} is null when that source's schema was never
     * discovered, or when the discovered model does not carry that table; the sink then falls back to a bare
     * table id and leaves structure and keying to the connector.
     */
    ResolvedTarget resolve(String sourceId) {
        SourceResource source = StoredArtifacts.requireSource(storePort.artifacts(), sourceId);
        String table = SourceCaptureResolution.of(source).table();
        return new ResolvedTarget(
                table, discoveredTable(sourceId, table).map(TargetModelResolver::toTargetTable).orElse(null));
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

    static TargetTable toTargetTable(SourceTable source, RenameSpec rename) {
        return rename(toTargetTable(source), source.name(), rename);
    }

    static TargetTable rename(TargetTable target, String sourceName, RenameSpec rename) {
        if (rename == null) {
            return target;
        }
        List<TargetField> fields = target == null ? List.of() : target.fields();
        return new TargetTable(TableRename.apply(sourceName, rename), fields);
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
