package io.tapstate.control.core;

import java.util.Objects;

/**
 * The truth-layer view of one stored artifact returned by a read: its id, kind, canonical form as held
 * by the store, and the content hash of exactly those canonical bytes. This is what a face shows for the
 * artifact read verbs — the server, not a local draft, is the source of what an artifact is
 * (server-as-truth). The read peer of {@link PreparedArtifact}.
 *
 * <p>The hash travels with the read because it is the precondition an edit or a removal must supply, and
 * not every caller can compute it: a remote model driving the tool surface cannot take a SHA-256 of the
 * text it just received. Handing it over here is what makes read-then-remove a closed loop on every face
 * rather than only on the ones that can hash locally. It is the same value the write side issues for the
 * same bytes, so the two never need reconciling.
 */
public record StoredArtifact(String id, String kind, String canonicalForm, String contentHash) {

    public StoredArtifact {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonicalForm, "canonicalForm");
        Objects.requireNonNull(contentHash, "contentHash");
    }
}
