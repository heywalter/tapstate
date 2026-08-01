package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One child event held until the row it hangs under arrives: its row, whether it was a deletion, and
 * the position it occupies on each chain it came from.
 *
 * <p>The positions are not decoration. A held event has been consumed but not emitted, so the durable
 * frontier must not be allowed past it — and a bound can only be reported for a position that is still
 * here to be read. An event held without its position would let the frontier claim coverage of a change
 * that has not reached a sink, which after a restart is a change that can neither be replayed nor found.
 *
 * <p>The row is copied on the way in and the map is unmodifiable, so a held event cannot be changed
 * from under the state that holds it. {@link Serializable} because the bucket outlives a single run.
 */
public record PendingChild(
        Map<String, ChainPosition> positions,
        Map<String, Object> row,
        boolean deletion) implements Serializable {

    public PendingChild {
        Objects.requireNonNull(positions, "positions");
        positions = Collections.unmodifiableMap(new LinkedHashMap<>(positions));
        row = row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}
