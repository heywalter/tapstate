package io.tapstate.control.core;

import java.nio.charset.StandardCharsets;
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
import io.tapstate.spi.store.ConnectorRegistry;
import io.tapstate.spi.store.ConnectorSpecStore;

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
    private final ConnectorSpecStore specStore;
    private final ConnectorRegistry registry;

    public ConnectorCatalogView(
            TapstateCatalog bundled, ConnectorCatalogStore store, ConnectorSpecStore specStore,
            ConnectorRegistry registry) {
        this.bundled = Objects.requireNonNull(bundled, "bundled");
        this.store = Objects.requireNonNull(store, "store");
        this.specStore = Objects.requireNonNull(specStore, "specStore");
        this.registry = Objects.requireNonNull(registry, "registry");
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

    /**
     * Whether this connector could actually be loaded here: a registration exists AND its bytes are
     * still in the store. Not the same question as {@code origin} — a bundled row is catalog metadata
     * that says nothing about a jar being present, and a registration whose bytes went missing is
     * registered but unrunnable. Answered without fetching the artifact.
     *
     * <p>Scoped to the connector being asked about. Deciding this from the whole registry listing would
     * make every read of every connector — bundled ones included, which have no registration at all —
     * fail on a single entry that cannot be reconstructed.
     */
    private boolean runnable(String connectorId) {
        return registry.find(connectorId)
                .map(registration -> registry.hasArtifact(registration.contentHash()))
                .orElse(false);
    }

    /**
     * Dereferences the hash a row already records as provenance into the stored spec source. The row is
     * a lossy projection, so a consumer needing what the projection dropped reads this; when nothing is
     * stored the absence is stated rather than left as a bare nothing.
     */
    private SpecSource specSourceFor(ConnectorCatalogEntry entry) {
        String hash = entry.provenance().specContentHash();
        if (hash == null) {
            return SpecSource.unavailable(null, SpecSource.NOT_STORED);
        }
        return specStore.get(hash)
                .map(bytes -> SpecSource.of(hash, new String(bytes, StandardCharsets.UTF_8)))
                .orElseGet(() -> SpecSource.unavailable(hash, SpecSource.NOT_STORED));
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
        return ConnectorDetail.of(
                entry, registeredOrigin ? REGISTERED : BUNDLED, specSourceFor(entry), runnable(id));
    }
}
