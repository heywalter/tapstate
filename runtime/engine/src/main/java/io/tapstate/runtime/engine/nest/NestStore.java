package io.tapstate.runtime.engine.nest;

import java.io.Serializable;

/**
 * Where one nest vertex keeps its state between events, one entry per key the vertex is partitioned by.
 *
 * <p>A vertex reads an entry when an event for that key arrives and writes it back once the batch it was
 * drained in is done, never per event: a hot key touched many times in one drain is stored once. Nothing
 * here enumerates keys, and nothing may - a vertex that could list its own entries would load them all on
 * the way back from a restart, which is the one thing the state layer exists to avoid.
 */
public interface NestStore<S> extends Serializable {

    /** The state held under {@code key}, or null when this vertex has never seen that key. */
    S load(Object key);

    /** Stores {@code state} under {@code key}, replacing whatever was there. */
    void save(Object key, S state);

    /** Removes the entry under {@code key} entirely, if there is one. */
    void remove(Object key);
}
