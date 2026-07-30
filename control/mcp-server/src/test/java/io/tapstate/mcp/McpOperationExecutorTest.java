package io.tapstate.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tapstate.control.client.HttpControlClient;
import io.tapstate.control.core.ControlOperations;
import io.tapstate.core.common.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpOperationExecutorTest {

    @Test
    void sourceCreateRequiresLiveSpecAndExpandsEnvironmentOnlyInTheOutboundRequest() throws Exception {
        AtomicReference<Map<?, ?>> posted = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, """
                        {"id":"mysql","origin":"registered","spec":{"properties":{}},
                         "specContentHash":"abc123","runtimeAvailable":true}
                        """);
            } else {
                posted.set((Map<?, ?>) JsonReader.parse(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                answer(exchange, 201, """
                        {"id":"orders","connector":"mysql","config":{"password":"********"},
                         "configuredSecrets":["password"],"contentHash":"hash"}
                        """);
            }
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    baseOf(server), "machine-token", Map.of("MYSQL_PASSWORD", "sentinel-secret"), client);
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("host", "db.internal");
            config.put("password", "${MYSQL_PASSWORD}");

            McpResult result = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "${MYSQL_PASSWORD}", "connector", "mysql", "config", config));

            assertThat(result.error()).isFalse();
            assertThat(result.body().toString()).doesNotContain("sentinel-secret");
            assertThat(authorization.get()).isEqualTo("Bearer machine-token");
            assertThat(((Map<?, ?>) posted.get().get("config")).get("password"))
                    .isEqualTo("sentinel-secret");
            assertThat(posted.get().get("id")).isEqualTo("${MYSQL_PASSWORD}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceCreateFailsBeforeWriteWhenConnectorResponseHasNoCompleteSpec() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            answer(exchange, 200,
                    "{\"id\":\"mysql\",\"origin\":\"bundled\",\"runtimeAvailable\":false}");
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(baseOf(server), "token", Map.of(), client);

            McpResult result = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql", "config", Map.of()));

            assertThat(result.error()).isTrue();
            assertThat(result.body()).containsEntry("code", "mcp.connector-spec-unavailable");
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableServerIsReturnedAsStructuredCodedFailure() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (HttpControlClient client = new HttpControlClient(Duration.ofMillis(200), Duration.ofMillis(200))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    URI.create("http://127.0.0.1:" + closedPort), "token", Map.of(), client);

            McpResult result = executor.execute(ControlOperations.PIPELINE_STATUS, Map.of("id", "orders"));

            assertThat(result.error()).isTrue();
            assertThat(result.body()).containsEntry("code", "control.unreachable");
        }
    }

    @Test
    void sourceCreateSupportsExplicitDefaultsAndRejectsMissingEnvironment() throws Exception {
        AtomicReference<Map<?, ?>> posted = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, """
                        {"id":"mysql","origin":"registered","spec":{"properties":{}},
                         "specContentHash":"abc123","runtimeAvailable":true}
                        """);
            } else {
                posted.set((Map<?, ?>) JsonReader.parse(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                answer(exchange, 201, "{\"id\":\"orders\"}");
            }
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(baseOf(server), "token", Map.of(), client);

            McpResult defaulted = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql",
                            "config", Map.of("host", "${var:MYSQL_HOST:localhost}")));
            McpResult missing = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql",
                            "config", Map.of("password", "${MYSQL_PASSWORD}")));

            assertThat(defaulted.error()).isFalse();
            assertThat(((Map<?, ?>) posted.get().get("config")).get("host")).isEqualTo("localhost");
            assertThat(missing.error()).isTrue();
            assertThat(missing.body()).containsEntry("code", "mcp.environment-missing");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionWritesExpandOnlySettingsBeforeSendingThemToTheServer() throws Exception {
        AtomicReference<Map<?, ?>> posted = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            posted.set((Map<?, ?>) JsonReader.parse(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            answer(exchange, 200, "{\"id\":\"orders\"}");
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    baseOf(server), "token", Map.of("MYSQL_PASSWORD", "sentinel-secret"), client);
            Map<String, Object> request = Map.of(
                    "id", "${MYSQL_PASSWORD}",
                    "connectorId", "mysql",
                    "settings", Map.of("password", "${MYSQL_PASSWORD}"));

            McpResult tested = executor.execute(ControlOperations.CONNECTION_TEST, request);
            assertThat(tested.error()).isFalse();
            assertThat(posted.get().get("id")).isEqualTo("${MYSQL_PASSWORD}");
            assertThat(((Map<?, ?>) posted.get().get("settings")).get("password"))
                    .isEqualTo("sentinel-secret");

            McpResult discovered = executor.execute(ControlOperations.CONNECTION_DISCOVER_SCHEMA, request);
            assertThat(discovered.error()).isFalse();
            assertThat(((Map<?, ?>) posted.get().get("settings")).get("password"))
                    .isEqualTo("sentinel-secret");
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static URI baseOf(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
