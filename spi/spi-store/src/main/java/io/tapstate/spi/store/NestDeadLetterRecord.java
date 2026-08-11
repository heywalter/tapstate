package io.tapstate.spi.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One change a stateful operator could never place in a document, kept so that it can be looked at.
 *
 * <p>The count of these is a statistic and says how many; this says which. A count on its own answers
 * "is anything being discarded" and nothing else, and the question that follows it is always about the
 * rows themselves — whether they are one table's dangling references or every table's, whether they
 * stopped when a deletion finished replaying or are still arriving. Neither can be answered from a
 * number, and by the time it is asked the log line that carried the row has rotated away.
 *
 * <p>{@code element} identifies the element within its namespace and is what this is filed under, so a
 * change discarded twice — the ordinary consequence of a replay — leaves one record rather than two. The
 * alternative, one record per occurrence, would make a replayed hour multiply the channel by however many
 * times it was replayed, and none of the copies would say anything the first did not.
 *
 * <p>{@code row} absent means the change removed the element rather than putting a row there; the one
 * accessor {@link #deletion()} is how that is asked, so no second flag can disagree with it. A row that
 * is present but empty is a row with no fields, which is a different thing and stays different.
 *
 * <p>{@code discardedAt} is wall-clock and is the one thing here that is: it exists so that what arrived
 * most recently can be read first, which is what a reader looking into a channel wants and what
 * {@code order} cannot give them — an order is comparable within one chain and meaningless across two, and
 * a namespace is fed by as many chains as it has streams beneath it. Nothing is decided by it; it orders a
 * listing and no more.
 *
 * <p>A pure value over {@code java..} only (rule R2): the port stays free of any engine or store type.
 */
public record NestDeadLetterRecord(
        String namespace,
        String element,
        String chain,
        String order,
        long heldForMillis,
        long discardedAt,
        Map<String, Object> row) {

    public NestDeadLetterRecord {
        requireText(namespace, "namespace");
        requireText(element, "element");
        requireText(order, "order");
        Objects.requireNonNull(chain, "chain");
        if (heldForMillis < 0) {
            throw new IllegalArgumentException("nest dead letter heldForMillis must not be negative");
        }
        row = row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    /** Whether the change removed the element rather than putting a row there. */
    public boolean deletion() {
        return row == null;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("nest dead letter " + field + " must be non-blank");
        }
    }
}
