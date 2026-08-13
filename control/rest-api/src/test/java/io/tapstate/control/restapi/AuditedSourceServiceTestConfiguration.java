package io.tapstate.control.restapi;

import io.tapstate.control.core.AuditGate;
import io.tapstate.control.core.AuditedSourceService;
import io.tapstate.control.core.SourceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
final class AuditedSourceServiceTestConfiguration {

    @Bean
    AuditedSourceService auditedSourceService(SourceService sources, AuditGate auditGate) {
        return new AuditedSourceService(sources, auditGate);
    }
}
