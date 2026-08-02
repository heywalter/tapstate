package io.tapstate.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * The connector distribution store: the single truth for which connector artifacts are registered and
 * the bytes to load them from. Registration is content-hash idempotent — the same artifact bytes
 * register once and re-registering them is a no-op — so a startup seed sweep and an explicit runtime
 * register share one path with no duplication. A pure interface (rule R2); it carries no
 * connector-framework or store-driver types.
 */
public interface ConnectorRegistry {

    /**
     * Registers the artifact if its content hash is not already registered (register-if-absent),
     * storing the bytes and a {@link ConnectorRegistration} keyed by that hash. Re-registering bytes
     * whose hash is already stored is a no-op that returns the existing registration with
     * {@code newlyRegistered} false. The content hash is computed from the bytes, so identity is the
     * store's to decide, not the caller's.
     */
    RegistrationOutcome register(String connectorId, String pdkApiVersion, RegistrationSource source, byte[] artifact);

    /** Every registered connector. */
    List<ConnectorRegistration> list();

    /**
     * The registration filed under a connector id, or empty if none is. Asking about one connector must
     * not depend on every other one being readable: a caller answering a question about a single id
     * through {@link #list()} fails whenever any one stored registration cannot be reconstructed, which
     * turns one corrupt entry into an outage across every connector. The default scans {@code list()}
     * because a registry that cannot look up by id has no better answer; a store that can query by id
     * overrides this and stays scoped to the entry asked about.
     */
    default Optional<ConnectorRegistration> find(String connectorId) {
        return list().stream()
                .filter(registration -> registration.connectorId().equals(connectorId))
                .findFirst();
    }

    /** The artifact bytes stored under a content hash, or empty if none is stored. */
    Optional<byte[]> artifact(String contentHash);

    /**
     * Whether bytes are stored under a content hash, without fetching them. A read face asking "can this
     * connector actually run here?" needs the answer, not the artifact; {@link #artifact(String)} would
     * pull tens of megabytes to compute a boolean.
     */
    boolean hasArtifact(String contentHash);
}
