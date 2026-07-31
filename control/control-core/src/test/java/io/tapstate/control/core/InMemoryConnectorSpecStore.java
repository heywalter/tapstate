package io.tapstate.control.core;

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
}
