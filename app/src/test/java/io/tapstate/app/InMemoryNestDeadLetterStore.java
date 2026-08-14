package io.tapstate.app;

import io.tapstate.spi.store.NestDeadLetterRecord;
import io.tapstate.spi.store.NestDeadLetterStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link NestDeadLetterStore} for the assembly-layer tests: where a nest's unassemblable changes
 * would go, without a database behind them. It files per element and keeps namespaces apart the way a real
 * one does, which is what the tests using it rely on.
 */
final class InMemoryNestDeadLetterStore implements NestDeadLetterStore {

    private final Map<String, NestDeadLetterRecord> byElement = new LinkedHashMap<>();

    @Override
    public void record(NestDeadLetterRecord record) {
        byElement.put(record.namespace() + "/" + record.element(), record);
    }

    @Override
    public List<NestDeadLetterRecord> read(String namespace, int limit) {
        return byElement.values().stream()
                .filter(held -> held.namespace().equals(namespace))
                .sorted(Comparator.comparingLong(NestDeadLetterRecord::discardedAt).reversed())
                .limit(Math.max(limit, 0))
                .toList();
    }

    @Override
    public void dropNamespace(String namespace) {
        byElement.values().removeIf(held -> held.namespace().equals(namespace));
    }
}
