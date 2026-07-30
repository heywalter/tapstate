package io.tapstate.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.tapstate.control.client.HttpControlClient;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Foreground stdio MCP process backed by a remote Tapstate Server. */
@SpringBootApplication
@ImportRuntimeHints(TapstateMcpApplication.McpRuntimeHints.class)
public class TapstateMcpApplication {

    static final String AOT_PROCESSING_PROPERTY = "tapstate.mcp.aot-processing";
    static final String LOGBACK_STATUS_LISTENER_PROPERTY = "logback.statusListenerClass";

    public static void main(String[] arguments) {
        prepareStdioLogging();
        try {
            start(arguments, System.getenv());
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        }
    }

    static ConfigurableApplicationContext start(
            String[] arguments, Map<String, String> environment) {
        McpOptions options = options(arguments, environment);
        McpEnvironment mcpEnvironment = new McpEnvironment(environment);
        SpringApplication application = new SpringApplication(TapstateMcpApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(context -> {
            context.getBeanFactory().registerSingleton("mcpOptions", options);
            context.getBeanFactory().registerSingleton("mcpEnvironment", mcpEnvironment);
        });
        return application.run();
    }

    static void prepareStdioLogging() {
        System.setProperty(
                LOGBACK_STATUS_LISTENER_PROPERTY,
                "ch.qos.logback.core.status.NopStatusListener");
    }

    static McpOptions options(String[] arguments, Map<String, String> environment) {
        if (!Boolean.getBoolean(AOT_PROCESSING_PROPERTY)) {
            return McpOptions.parse(arguments, environment);
        }
        Map<String, String> aotEnvironment = new HashMap<>(environment);
        aotEnvironment.putIfAbsent("TAPSTATE_TOKEN", "tapstate-aot-context-only");
        return McpOptions.parse(arguments, aotEnvironment);
    }

    @Bean(destroyMethod = "close")
    HttpControlClient controlClient() {
        return new HttpControlClient();
    }

    @Bean
    McpOperationExecutor operationExecutor(
            McpOptions options, McpEnvironment environment, HttpControlClient client) {
        return new McpOperationExecutor(
                options.server(), options.token(), environment.values(), client);
    }

    @Bean
    List<SyncToolSpecification> mcpTools(
            McpOptions options, McpOperationExecutor executor) {
        return McpToolCatalog.specifications(options.allowWrite(), executor);
    }

    static final class McpRuntimeHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            hints.reflection().registerType(
                    TypeReference.of("ch.qos.logback.core.status.NopStatusListener"),
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}
