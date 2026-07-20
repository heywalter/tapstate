package io.tapstate.control.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectorCatalogStore;

/**
 * The online catalog view: the bundled snapshot overlaid with the rows derived for registered
 * connectors, read live from the store so a runtime registration is visible without a restart. It
 * backs the connector.list read verb and feeds the online apply path its capability matrix, while the
 * offline CLI keeps reading only the bundled snapshot (its honest boundary).
 */
public final class ConnectorCatalogView {

    private static final String BUNDLED = "bundled";
    private static final String REGISTERED = "registered";

    private final TapstateCatalog bundled;
    private final ConnectorCatalogStore store;

    public ConnectorCatalogView(TapstateCatalog bundled, ConnectorCatalogStore store) {
        this.bundled = Objects.requireNonNull(bundled, "bundled");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** The live online catalog = the bundled snapshot with registered rows overlaid (registered shadows). */
    public TapstateCatalog merged() {
        return TapstateCatalog.merged(bundled, store.list());
    }

    /** Every connector visible online, tagged {@code bundled} or {@code registered}. */
    public List<ConnectorSummary> summaries() {
        List<ConnectorCatalogEntry> registered = store.list();
        Set<String> registeredIds = new HashSet<>();
        for (ConnectorCatalogEntry entry : registered) {
            registeredIds.add(entry.id());
        }
        List<ConnectorSummary> summaries = new ArrayList<>();
        for (ConnectorCatalogEntry entry : TapstateCatalog.merged(bundled, registered).all()) {
            summaries.add(ConnectorSummary.of(entry, registeredIds.contains(entry.id()) ? REGISTERED : BUNDLED));
        }
        return summaries;
    }

    /** One live connector row with the normalized fields a safe dynamic form consumes. */
    public ConnectorDetail detail(String id) {
        Objects.requireNonNull(id, "id");
        List<ConnectorCatalogEntry> registered = store.list();
        boolean registeredOrigin = registered.stream().anyMatch(entry -> entry.id().equals(id));
        ConnectorCatalogEntry entry;
        try {
            entry = TapstateCatalog.merged(bundled, registered).byId(id);
        } catch (IllegalArgumentException error) {
            throw new TapstateException(
                    ConnectorCatalogError.NOT_FOUND, Map.of("connector", id), error);
        }
        return ConnectorDetail.of(entry, registeredOrigin ? REGISTERED : BUNDLED);
    }
}
