package io.tapstate.control.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectorCatalogStore;
import io.tapstate.spi.store.ConnectorRegistration;
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
     * Whether this connector could actually be loaded here: exactly one registration exists AND its bytes
     * are still in the store. Not the same question as {@code origin} — a bundled row is catalog metadata
     * that says nothing about a jar being present, and a registration whose bytes went missing is
     * registered but unrunnable. Answered without fetching the artifact.
     *
     * <p>Two registrations under one id answer false, not true. Loading a connector refuses that state
     * outright, so reporting it as available would promise a connector that every operation actually
     * using it — a connection test, a schema discovery, a pipeline start — then refuses.
     */
    private boolean runnable(List<ConnectorRegistration> registrations) {
        return registrations.size() == 1 && registry.hasArtifact(registrations.get(0).contentHash());
    }

    /**
     * Dereferences the hash a row already records as provenance into the stored spec source. The row is
     * a lossy projection, so a consumer needing what the projection dropped reads this; whenever the
     * source cannot be produced the absence is stated, with which absence it is, rather than left as a
     * bare nothing.
     */
    private SpecSource specSourceFor(ConnectorCatalogEntry entry, boolean derivedHere, boolean registeredHere) {
        String hash = entry.provenance().specContentHash();
        if (hash == null) {
            return SpecSource.unavailable(null, SpecSource.NOT_STORED);
        }
        if (!derivedHere && registeredHere) {
            // The row on hand is the bundled snapshot's, so this hash is the one recorded when the release
            // was built - not the one this deployment's registered artifact declares. That artifact's
            // source is stored, but the hash that would address it is what the missing derived row would
            // have carried, so nothing here can reach it. Saying "not stored" would be answering about a
            // different spec than the one the caller is asking about.
            return SpecSource.unavailable(hash, SpecSource.NOT_DERIVED);
        }
        try {
            return specStore.get(hash)
                    .map(bytes -> SpecSource.of(hash, new String(bytes, StandardCharsets.UTF_8)))
                    .orElseGet(() -> SpecSource.unavailable(hash, SpecSource.NOT_STORED));
        } catch (TapstateException unreadableSource) {
            // A damaged spec document must not take the whole read down with it. Everything else in this
            // response - the config field list a connection is authored against - is intact and was read
            // before this call, so a store that could not answer here is one whose fault is this document.
            // The field exists to state an absence; this is one, with its own reason.
            return SpecSource.unavailable(hash, SpecSource.UNREADABLE);
        }
    }

    /** One live connector row with the normalized fields a safe dynamic form consumes. */
    public ConnectorDetail detail(String id) {
        Objects.requireNonNull(id, "id");
        // Read by id rather than by listing and merging: a detail read asks about one connector, and one
        // row written by a newer build would otherwise fail every connector's read, bundled ones included.
        Optional<ConnectorCatalogEntry> derivedRow = store.get(id);
        ConnectorCatalogEntry entry = derivedRow.orElseGet(() -> bundledRow(id));
        List<ConnectorRegistration> registrations = registry.findAll(id);
        return ConnectorDetail.of(
                entry,
                derivedRow.isPresent() ? REGISTERED : BUNDLED,
                specSourceFor(entry, derivedRow.isPresent(), !registrations.isEmpty()),
                runnable(registrations));
    }

    /** The bundled snapshot's row for an id no registration derived, or a coded not-found. */
    private ConnectorCatalogEntry bundledRow(String id) {
        try {
            return bundled.byId(id);
        } catch (IllegalArgumentException error) {
            throw new TapstateException(
                    ConnectorCatalogError.NOT_FOUND, Map.of("connector", id), error);
        }
    }
}
