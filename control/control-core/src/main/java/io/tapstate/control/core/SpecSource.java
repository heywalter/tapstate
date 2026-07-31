package io.tapstate.control.core;

import java.util.Objects;

/**
 * A connector's spec source as a read face reports it: either the stored text, or a stated reason it is
 * not there. Never silently nothing — a bare absence invites a consumer to rebuild a spec out of the
 * normalized config, which is the one thing keeping the source exists to prevent.
 *
 * <p>{@code contentHash} is named either way, so a consumer that finds the source absent can ask again
 * later against the same identity rather than having to re-derive it.
 *
 * @param contentHash the hash the connector's catalog row records as provenance
 * @param text the stored source, or {@code null} when it is not available
 * @param unavailable why the source is absent, or {@code null} when it is present
 */
public record SpecSource(String contentHash, String text, String unavailable) {

    /** No source is stored under this hash: a bundled row, or one registered before sources were kept. */
    public static final String NOT_STORED = "not-stored";

    public SpecSource {
        if ((text == null) == (unavailable == null)) {
            // Exactly one of the two holds. Both set would leave "is it there?" ambiguous; neither set is
            // the silent absence this type exists to rule out.
            throw new IllegalArgumentException("exactly one of text and unavailable must be set");
        }
    }

    /** The source, read back from the store. */
    public static SpecSource of(String contentHash, String text) {
        return new SpecSource(contentHash, Objects.requireNonNull(text, "text"), null);
    }

    /** No source, and why. */
    public static SpecSource unavailable(String contentHash, String reason) {
        return new SpecSource(contentHash, null, Objects.requireNonNull(reason, "reason"));
    }
}
