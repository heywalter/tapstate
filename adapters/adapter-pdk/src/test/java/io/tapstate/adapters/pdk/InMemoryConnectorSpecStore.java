package io.tapstate.adapters.pdk;

import io.tapstate.spi.store.ConnectorSpecStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** An in-memory connector spec source store for tests, keyed by content hash. */
final class InMemoryConnectorSpecStore implements ConnectorSpecStore {

    private final Map<String, byte[]> specs = new LinkedHashMap<>();

    @Override
    public void put(String contentHash, byte[] spec) {
        specs.put(contentHash, spec.clone());
    }

    @Override
    public Optional<byte[]> get(String contentHash) {
        return Optional.ofNullable(specs.get(contentHash)).map(byte[]::clone);
    }

    /** How many distinct specs are stored — content-addressed, so this counts unique spec sources. */
    int size() {
        return specs.size();
    }
}
