package io.tapstate.mcp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tapstate.control.client.HttpControlClient;
import io.tapstate.control.core.ControlOperations;
import io.tapstate.control.core.Operation;
import io.tapstate.control.core.Scope;
import io.tapstate.core.common.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class McpOperationExecutorTest {

    @Test
    void routesEveryMcpOperationThroughTheHttpControlContract() throws Exception {
        List<String> paths = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            paths.add(exchange.getRequestURI().toString());
            if (exchange.getRequestMethod().equals("GET")
                    && exchange.getRequestURI().getPath().equals("/api/connectors/mysql")) {
                answer(exchange, 200, """
                        {"id":"mysql","origin":"registered","runtimeAvailable":true,
                         "spec":{"contentHash":"abc123","text":"{}","unavailable":null}}
                        """);
            } else {
                answer(exchange, 200, "{}");
            }
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    baseOf(server), "token", Map.of(), client);
            Map<String, Object> source = Map.of(
                    "id", "orders", "connector", "mysql", "config", Map.of());
            Map<String, Object> connection = Map.of(
                    "id", "orders", "connectorId", "mysql", "settings", Map.of());
            Map<String, Object> pipeline = Map.of("id", "orders");
            Map<String, Object> logs = new LinkedHashMap<>(pipeline);
            logs.put("limit", 999);

            List<Map.Entry<io.tapstate.control.core.Operation, Map<String, Object>>> calls = List.of(
                    Map.entry(ControlOperations.CONNECTOR_LIST, Map.of()),
                    Map.entry(ControlOperations.CONNECTOR_GET, Map.of("id", "mysql")),
                    Map.entry(ControlOperations.SOURCE_LIST, Map.of()),
                    Map.entry(ControlOperations.SOURCE_GET, Map.of("id", "orders")),
                    Map.entry(ControlOperations.SOURCE_CREATE, source),
                    Map.entry(ControlOperations.CONNECTION_TEST, connection),
                    Map.entry(ControlOperations.CONNECTION_TEST_RESULT, Map.of("id", "orders")),
                    Map.entry(ControlOperations.CONNECTION_DISCOVER_SCHEMA, connection),
                    Map.entry(ControlOperations.CONNECTION_SCHEMA, Map.of("id", "orders")),
                    Map.entry(ControlOperations.ARTIFACT_VALIDATE, Map.of("drafts", List.of())),
                    Map.entry(ControlOperations.ARTIFACT_APPLY, Map.of("drafts", List.of())),
                    Map.entry(ControlOperations.PIPELINE_START, pipeline),
                    Map.entry(ControlOperations.PIPELINE_STOP, pipeline),
                    Map.entry(ControlOperations.PIPELINE_STATUS, pipeline),
                    Map.entry(ControlOperations.PIPELINE_METRICS, pipeline),
                    Map.entry(ControlOperations.PIPELINE_SNAPSHOT, pipeline),
                    Map.entry(ControlOperations.PIPELINE_LOGS, logs));

            for (Map.Entry<io.tapstate.control.core.Operation, Map<String, Object>> call : calls) {
                assertThat(executor.execute(call.getKey(), call.getValue()).error())
                        .as(call.getKey().id())
                        .isFalse();
            }

            assertThat(paths).contains(
                    "/api/connectors", "/api/connectors/mysql", "/api/sources",
                    "/api/sources/orders", "/api/connections:test",
                    "/api/connections/orders/test-result", "/api/connections:discover-schema",
                    "/api/connections/orders/schema", "/api/artifacts:validate", "/api/artifacts:apply",
                    "/api/pipelines/orders:start", "/api/pipelines/orders:stop",
                    "/api/pipelines/orders/status", "/api/pipelines/orders/metrics",
                    "/api/pipelines/orders/snapshot", "/api/pipelines/orders/logs?limit=200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceCreateAcceptsTheRegisteredConnectorSpecContract() throws Exception {
        AtomicReference<Map<?, ?>> posted = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            if (exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, """
                        {"id":"mysql","origin":"registered","runtimeAvailable":true,
                         "spec":{"contentHash":"abc123","text":"{}",
                                 "unavailable":null}}
                        """);
            } else {
                posted.set((Map<?, ?>) JsonReader.parse(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                answer(exchange, 201, "{\"id\":\"orders\"}");
            }
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    baseOf(server), "token", Map.of("MYSQL_PASSWORD", "sentinel-secret"), client);

            McpResult result = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql",
                            "config", Map.of("password", "${MYSQL_PASSWORD}")));

            assertThat(result.error()).isFalse();
            assertThat(((Map<?, ?>) posted.get().get("config")).get("password"))
                    .isEqualTo("sentinel-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceCreateRequiresLiveSpecAndExpandsEnvironmentOnlyInTheOutboundRequest() throws Exception {
        AtomicReference<Map<?, ?>> posted = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, """
                        {"id":"mysql","origin":"registered","runtimeAvailable":true,
                         "spec":{"contentHash":"abc123","text":"{}",
                                 "unavailable":null}}
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
        try (ServerSocket socket = new ServerSocket(0);
                HttpControlClient client = new HttpControlClient(Duration.ofMillis(200), Duration.ofMillis(200))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    URI.create("http://127.0.0.1:" + socket.getLocalPort()), "token", Map.of(), client);

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
                        {"id":"mysql","origin":"registered","runtimeAvailable":true,
                         "spec":{"contentHash":"abc123","text":"{}",
                                 "unavailable":null}}
                        """);
            } else {
                posted.set((Map<?, ?>) JsonReader.parse(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                answer(exchange, 201, "{\"id\":\"orders\"}");
            }
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(10), Duration.ofSeconds(10))) {
            McpOperationExecutor executor = new McpOperationExecutor(baseOf(server), "token", Map.of(), client);

            McpResult defaulted = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql",
                            "config", Map.of("host", "${var:MYSQL_HOST:localhost}")));
            McpResult missing = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql",
                            "config", Map.of("password", "${MYSQL_PASSWORD}")));

            assertThat(defaulted.error()).as(defaulted.body().toString()).isFalse();
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

    @Test
    void unsupportedOperationsAreReturnedAsStructuredFailures() {
        try (HttpControlClient client = new HttpControlClient()) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    URI.create("http://127.0.0.1:1"), "token", Map.of(), client);

            McpResult result = executor.execute(
                    new Operation("test.unsupported", Scope.READ, false, null, Map.of()), Map.of());

            assertThat(result.error()).isTrue();
            assertThat(result.body()).containsEntry("code", "control.malformed-request");
        }
    }

    @Test
    void sourceCreatePropagatesConnectorRejectionWithoutWritingSource() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            answer(exchange, 503, "{\"message\":\"temporarily unavailable\"}");
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    baseOf(server), "token", Map.of(), client);

            McpResult result = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql", "config", Map.of()));

            assertThat(result.error()).isTrue();
            assertThat(result.body()).containsEntry("code", "mcp.server-rejected");
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sourceCreateRejectsNonObjectConnectorResponse() throws Exception {
        HttpServer server = server(exchange -> answer(exchange, 200, "[]"));
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(10), Duration.ofSeconds(10))) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    baseOf(server), "token", Map.of(), client);

            McpResult result = executor.execute(ControlOperations.SOURCE_CREATE,
                    Map.of("id", "orders", "connector", "mysql", "config", Map.of()));

            assertThat(result.error()).isTrue();
            assertThat(result.body()).containsEntry("code", "mcp.connector-spec-unavailable");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiredPipelineIdIsValidatedBeforeAnyHttpRequest() {
        try (HttpControlClient client = new HttpControlClient()) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    URI.create("http://127.0.0.1:1"), "token", Map.of(), client);

            McpResult missing = executor.execute(ControlOperations.PIPELINE_STATUS, Map.of());
            McpResult blank = executor.execute(ControlOperations.PIPELINE_STATUS, Map.of("id", " "));

            assertThat(missing.body()).containsEntry("code", "control.malformed-request");
            assertThat(blank.body()).containsEntry("code", "control.malformed-request");
            assertThat(missing.error()).isTrue();
            assertThat(blank.error()).isTrue();
        }
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mcp-operation-test-http");
            thread.setDaemon(true);
            return thread;
        }));
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
