package io.tapstate.runtime.engine.nest;

import com.hazelcast.map.IMap;
import java.util.Objects;

/**
 * A nest store over one distributed map - the map the vertex's own name resolves to.
 *
 * <p>What this buys over keeping the entries in the store object is that the entries stop belonging to the
 * object. A processor is built again on every restart and once per parallel instance, and each of those
 * builds its own store; when the name is what identifies the state, all of them address the same entries and
 * a restart resumes instead of beginning empty.
 *
 * <p>Two of the three calls deliberately use the form that does not hand back what was there before.
 * A write returns the previous value if asked, and the previous value here is a whole document that nobody
 * reads - asking for it would pay a full serialization of it on every single write. The same holds for
 * removal. Reading the map is the only place a value is meant to travel.
 *
 * <p>This holds a live map and is therefore built on the member that will use it, never carried to one. It
 * is left plainly non-serializable rather than hiding the field, so that carrying one fails loudly at the
 * moment it is carried instead of arriving with its state silently gone.
 */
final class MapNestStore<S> implements NestStore<S> {

    private static final long serialVersionUID = 1L;

    private final IMap<Object, S> map;

    MapNestStore(IMap<Object, S> map) {
        this.map = Objects.requireNonNull(map, "map");
    }

    @Override
    public S load(Object key) {
        return map.get(key);
    }

    @Override
    public void save(Object key, S state) {
        map.set(key, state);
    }

    @Override
    public void remove(Object key) {
        map.delete(key);
    }
}
