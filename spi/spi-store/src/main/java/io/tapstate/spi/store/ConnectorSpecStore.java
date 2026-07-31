package io.tapstate.spi.store;

import java.util.Optional;

/**
 * The connector spec sources, keyed by the content hash of the bytes themselves. Normalization into a
 * {@link ConnectorCatalogStore} row is lossy by design — it keeps the fields a safe connection form
 * consumes and drops the rest — so the source is kept beside the row for consumers that need what the
 * row does not carry. Keying by content hash means identical specs across ids or re-registrations are
 * stored once, and the hash a catalog row already records as provenance is directly dereferenceable.
 *
 * <p>Distinct from {@link ConnectorRegistry}, which holds whole artifacts: this holds one small
 * document extracted from an artifact. A pure interface (rule R2); it carries no connector-framework
 * or store-driver types.
 */
public interface ConnectorSpecStore {

    /**
     * Stores the spec bytes under their content hash. Content-addressed, so storing bytes whose hash is
     * already present is a no-op rather than a conflict — the stored bytes are the same bytes.
     */
    void put(String contentHash, byte[] spec);

    /** The spec bytes stored under a content hash, or empty if none is stored. */
    Optional<byte[]> get(String contentHash);
}
