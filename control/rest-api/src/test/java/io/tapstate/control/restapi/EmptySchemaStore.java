package io.tapstate.control.restapi;

import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SchemaStore;
import java.util.Optional;

/**
 * A schema store in which nothing has been discovered — the state every connection starts in. The
 * face tests are about routing, authorization and rendering rather than the row-expression type
 * check, so they wire this and that check has nothing to judge, exactly as with a real store before
 * any discovery has run.
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
