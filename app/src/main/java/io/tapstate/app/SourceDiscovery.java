package io.tapstate.app;

import io.tapstate.core.model.SourceResource;
import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SourceModel;
import io.tapstate.spi.store.StorePort;

/** Looks up the latest schema only when it belongs to the source's current connector. */
final class SourceDiscovery {

    private SourceDiscovery() {
    }

    static SourceModel model(StorePort storePort, SourceResource source) {
        return storePort.schemas().get(source.id())
                .filter(discovered -> discovered.connectorId().equals(source.connector()))
                .map(DiscoveredSourceModel::model)
                .orElse(null);
    }
}
