package io.tapstate.control.restapi;

import io.tapstate.control.core.SourceDraftService;
import io.tapstate.core.catalog.TapstateCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
final class SourceDraftTestConfiguration {

    @Bean
    SourceDraftService sourceDraftService() {
        return new SourceDraftService(TapstateCatalog::load);
    }
}
