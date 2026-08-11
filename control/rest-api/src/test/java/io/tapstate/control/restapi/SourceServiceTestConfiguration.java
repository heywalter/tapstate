package io.tapstate.control.restapi;

import io.tapstate.control.core.SourceRepresentation;
import io.tapstate.control.core.SourceService;
import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.spi.store.ArtifactStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
final class SourceServiceTestConfiguration {

    @Bean
    SourceRepresentation sourceRepresentation() {
        return new SourceRepresentation(TapstateCatalog::load);
    }

    @Bean
    SourceService sourceService(ArtifactStore store, SourceRepresentation representation) {
        return new SourceService(TapstateCatalog::load, store, representation);
    }

}
