package io.tapstate.runtime.engine.nest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A nest store that keeps everything on the heap of the member running the vertex - a test double, and
 * only that.
 *
 * <p>It is the whole store, not a cache in front of one: nothing spills, and a restart begins with
 * nothing. A run drives no pipeline on this. Nest state is what a change let past the source's read
 * offset is being kept in, so state that dies with the process is not a smaller version of the real
 * thing, it is a way to lose those changes with nothing reporting it - which is why this lives in test
 * sources, where the compiler is what keeps it out of a running system rather than a rule someone reads.
 *
 * <p>What it is good for: driving a vertex whose subject is not durability, where a real store would add
 * a container and tell the case nothing.
 */
public final class HeapNestStore<S> implements NestStore<S> {

    private final Map<Object, S> entries = new LinkedHashMap<>();

    @Override
    public S load(Object key) {
        return entries.get(key);
    }

    @Override
    public void save(Object key, S state) {
        entries.put(key, state);
    }

    @Override
    public void remove(Object key) {
        entries.remove(key);
    }

    @Override
    public long count() {
        return entries.size();
    }
}
