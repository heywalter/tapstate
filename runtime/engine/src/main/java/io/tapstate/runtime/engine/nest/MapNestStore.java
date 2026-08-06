package io.tapstate.runtime.engine.nest;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import java.io.Serializable;
import java.util.Map;
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
 * <p><b>A write is carried to its key rather than put across the map.</b> Putting a state serializes the
 * whole assembled document every time; handing it to the key it belongs to serializes none of it, because
 * the key is on the member the write is made from and what travels is a reference. Measured on both:
 * carried, a write costs nothing that grows with the document, where a put costs one full copy of it and
 * so grows with every row the document has ever absorbed.
 *
 * <p>It is a whole state that is carried, not a description of what changed in it, so a write still
 * replaces what was there in one step: nothing is left half-applied by a write that fails partway. Getting
 * to no copy at all would mean the state never leaving its partition, which is a different shape of
 * operator than this one.
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
        map.executeOnKey(key, new Carried<>(state));
    }

    @Override
    public void remove(Object key) {
        map.delete(key);
    }

    /**
     * Puts a state where its key already is. It answers nothing on purpose: whatever it answered would be
     * serialized on the way back, which is the cost being avoided in the first place.
     *
     * <p>It has no backup form. These maps keep no replica - the state is rebuildable from the source and
     * from what stands behind the map - so there is no second copy for one to be applied to.
     */
    private static final class Carried<S> implements EntryProcessor<Object, S, Void>, Serializable {

        private static final long serialVersionUID = 1L;

        private final S state;

        Carried(S state) {
            this.state = state;
        }

        @Override
        public Void process(Map.Entry<Object, S> entry) {
            entry.setValue(state);
            return null;
        }

        @Override
        public EntryProcessor<Object, S, Void> getBackupProcessor() {
            return null;
        }
    }
}
