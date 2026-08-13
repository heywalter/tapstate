package io.tapstate.runtime.scheduler;

import io.tapstate.core.lifecycle.DesiredState;
import io.tapstate.spi.store.DesiredStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A trivial in-memory {@link DesiredStore} double: a last-write-wins map keyed by pipeline id. */
final class InMemoryDesiredStore implements DesiredStore {
    @Override
    public void delete(String pipelineId) {
        throw new UnsupportedOperationException("removal is not exercised by this double");
    }


    private final Map<String, DesiredState> docs = new HashMap<>();

    @Override
    public void save(DesiredState desired) {
        docs.put(desired.pipelineId(), desired);
    }

    @Override
    public Optional<DesiredState> read(String pipelineId) {
        return Optional.ofNullable(docs.get(pipelineId));
    }

    @Override
    public List<String> pipelineIds() {
        return List.copyOf(docs.keySet());
    }
}
