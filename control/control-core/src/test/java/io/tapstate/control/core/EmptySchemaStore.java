package io.tapstate.control.core;

import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SchemaStore;
import java.util.Optional;

/**
 * A schema store in which nothing has been discovered — the state every connection starts in. Tests
 * about something other than the row-expression type check use it so that check has nothing to judge
 * and stays out of their way, which is the same behaviour a real store gives before any discovery.
 */
final class EmptySchemaStore implements SchemaStore {

    @Override
    public void save(DiscoveredSourceModel discovered) {
        throw new UnsupportedOperationException("this store is only ever read");
    }

    @Override
    public Optional<DiscoveredSourceModel> get(String connectionId) {
        return Optional.empty();
    }
}
