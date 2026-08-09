package io.tapstate.adapters.pdk;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.SchemaDiscoverer;
import io.tapstate.spi.store.SourceField;
import io.tapstate.spi.store.SourceIndex;
import io.tapstate.spi.store.SourceModel;
import io.tapstate.spi.store.SourceTable;
import io.tapdata.entity.schema.TapIndex;
import io.tapdata.entity.schema.TapIndexField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.pdk.apis.functions.connector.source.BatchCountFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PDK implementation of the schema-discovery port: it provisions a connector, refuses it with a
 * code if it will not load or its declared API level is incompatible, drives the connector's frozen
 * {@code discoverSchema} to enumerate the source's streams, and normalizes each PDK table into a
 * {@link SourceTable} — its fields, primary key and indexes. The connector is inited once for the
 * discovery and stopped afterwards; the PDK types stay inside this class and neither the port nor the
 * model carries any of them.
 *
 * <p>A connector that throws out of {@code discoverSchema} could not complete discovery — a coded
 * connector-domain failure, distinct from an empty source. A field carries both the type the connector
 * reported it with, verbatim, and that type resolved onto the tapstate type namespace — resolved here,
 * because the connector's own declarations are what resolves it and they are unreachable once it closes.
 * An un-loadable or level-incompatible connector is refused up front by the shared open path, never
 * downgraded into an empty model.
 */
public final class PdkSchemaDiscoverer implements SchemaDiscoverer {

    private final ConnectorProvisioner provisioner;

    public PdkSchemaDiscoverer(ConnectorProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    private static final Logger LOG = LoggerFactory.getLogger(PdkSchemaDiscoverer.class);

    @Override
    public SourceModel discover(ConnectionConfig config) {
        PdkConnector connector = PdkConnector.open(
                config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
        try {
            List<TapTable> tables = drive(connector);
            return toSourceModel(tables, counts(connector, tables));
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    /**
     * How many rows each discovered table holds, for the tables the connector will answer for. Counting
     * is a capability of its own, separate from discovering: a connector registering no count function
     * contributes no counts at all, and one whose count fails contributes none for that table.
     *
     * <p>A failure here never reaches the caller as a failure. The schema is what discovery was asked
     * for and the count is taken alongside it; refusing the schema because a number could not be
     * produced would take away what the author needs over what only sizes their state, and the reader
     * of a count already has to handle its absence — no connector is obliged to offer one. The failure
     * is logged rather than swallowed, so a count that never arrives can still be explained.
     */
    private static Map<TapTable, Long> counts(PdkConnector connector, List<TapTable> tables) {
        BatchCountFunction count = connector.functions().getBatchCountFunction();
        if (count == null) {
            return Map.of();
        }
        Map<TapTable, Long> counted = new IdentityHashMap<>();
        try {
            connector.underLoader(() -> {
                for (TapTable table : tables) {
                    try {
                        counted.put(table, count.count(connector.context(), table));
                    } catch (Throwable t) {
                        // Per table, so one source refusing to be counted does not cost the rest theirs.
                        LOG.warn("connector {} could not count table {}: {}",
                                connector.connectorId(), table.getId(), detail(t));
                    }
                }
                return null;
            });
        } catch (Throwable t) {
            LOG.warn("connector {} could not be asked to count: {}", connector.connectorId(), detail(t));
        }
        return counted;
    }

    /**
     * Inits the connector once and drives its discoverSchema over every stream (an empty stream list =
     * all), collecting the reported tables. A connector that throws out of discovery could not complete
     * it — a coded connector-domain failure.
     */
    private static List<TapTable> drive(PdkConnector connector) {
        try {
            return connector.underLoader(() -> {
                connector.connector().init(connector.context());
                List<TapTable> tables = new ArrayList<>();
                connector.connector().discoverSchema(connector.context(), List.of(), Integer.MAX_VALUE, tables::addAll);
                // Resolving a column's type is the connector's own job, off its own declarations, so it
                // happens here while the connector is still open - never after the model has left.
                tables.forEach(connector::fillFieldTypes);
                return tables;
            });
        } catch (TapstateException e) {
            throw e;
        } catch (Throwable t) {
            throw new TapstateException(ConnectorError.DISCOVER_FAILED,
                    Map.of("connector", connector.connectorId(), "detail", detail(t)), t);
        }
    }

    private static SourceModel toSourceModel(List<TapTable> tables, Map<TapTable, Long> counts) {
        List<SourceTable> mapped = new ArrayList<>(tables.size());
        for (TapTable table : tables) {
            mapped.add(new SourceTable(
                    table.getId(), fields(table), primaryKey(table), indexes(table), counts.get(table)));
        }
        return new SourceModel(mapped);
    }

    private static List<SourceField> fields(TapTable table) {
        List<SourceField> fields = new ArrayList<>();
        if (table.getNameFieldMap() != null) {
            table.getNameFieldMap().forEach((name, field) ->
                    fields.add(new SourceField(name, field.getDataType(), PdkTypeMapping.of(field.getTapType()))));
        }
        return fields;
    }

    /** The primary-key column names in key order, as the table derives them from its fields. */
    private static List<String> primaryKey(TapTable table) {
        return new ArrayList<>(table.primaryKeys());
    }

    private static List<SourceIndex> indexes(TapTable table) {
        List<SourceIndex> indexes = new ArrayList<>();
        if (table.getIndexList() != null) {
            for (TapIndex index : table.getIndexList()) {
                indexes.add(new SourceIndex(index.getName(), indexFieldNames(index), Boolean.TRUE.equals(index.getUnique())));
            }
        }
        return indexes;
    }

    private static List<String> indexFieldNames(TapIndex index) {
        List<String> names = new ArrayList<>();
        if (index.getIndexFields() != null) {
            for (TapIndexField field : index.getIndexFields()) {
                names.add(field.getName());
            }
        }
        return names;
    }

    /**
     * A diagnosable one-liner for a connector's failure: the chain of causes, each named and carrying its
     * own message where it has one. A connector fault often surfaces as a wrapper with no message of its
     * own — an {@code ExceptionInInitializerError}, a reflective {@code InvocationTargetException} — and
     * reporting only that wrapper's class name buries the real fault. Walking the chain keeps the fault in
     * view. Bounded in length, and guarded against a self- or cycle-referential cause.
     */
    static String detail(Throwable t) {
        StringBuilder chain = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable each = t; each != null && seen.add(each) && chain.length() < 500; each = each.getCause()) {
            if (chain.length() > 0) {
                chain.append(" <- ");
            }
            chain.append(each.getClass().getSimpleName());
            String message = each.getMessage();
            if (message != null && !message.isBlank()) {
                chain.append(": ").append(message.strip());
            }
        }
        return chain.toString();
    }
}
