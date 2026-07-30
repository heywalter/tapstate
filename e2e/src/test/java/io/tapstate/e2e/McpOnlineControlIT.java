package io.tapstate.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tapstate.core.common.JsonReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP stdio process creating a Source through the remote control contract.
 *
 * <p>The declarative end-to-end vocabulary describes pipeline state and target rows. It has no word
 * for a JSON-RPC stdio process, tool discovery, or the HTTP request a tool sends, so this case drives
 * the shipped MCP entry point as a separate process. A fake HTTP peer keeps the case deterministic
 * while preserving the boundary under test: the MCP process can reach it only over the public wire.
 */
class McpOnlineControlIT {

    private static final String MCP_BOOT_JAR_PROPERTY = "tapstate.e2e.mcp-boot-jar";
    private static final String MACHINE_TOKEN = "mcp-e2e-machine-token";
    private static final String SENTINEL_SECRET = "mcp-e2e-sentinel-secret";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void sourceCreateUsesTheLiveConnectorSpecAndKeepsTheExpandedSecretOffMcpOutput() throws Exception {
        AtomicReference<Map<?, ?>> sourceRequest = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if (exchange.getRequestMethod().equals("GET")) {
                answer(exchange, 200, """
                        {"id":"mysql","origin":"registered","runtimeAvailable":true,
                         "spec":{"properties":{"host":{"type":"string"},
                         "password":{"type":"string","secret":true}}},
                         "specContentHash":"e2e-spec-hash"}
                        """);
                return;
            }
            sourceRequest.set((Map<?, ?>) JsonReader.parse(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            answer(exchange, 201, """
                    {"id":"orders","connector":"mysql","config":{"host":"db.internal",
                     "password":"********"},"configuredSecrets":["password"],
                     "contentHash":"source-content-hash"}
                    """);
        });
        Path stderr = temporaryDirectory.resolve("mcp.stderr");
        Process process = startMcp(server, stderr);
        try (Writer input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            send(input, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-06-18","capabilities":{},
                      "clientInfo":{"name":"tapstate-e2e","version":"1"}}}
                    """);
            Map<?, ?> initialized = receive(output);
            assertThat(((Map<?, ?>) initialized.get("result")).get("protocolVersion"))
                    .isEqualTo("2025-06-18");

            send(input, """
                    {"jsonrpc":"2.0","method":"notifications/initialized"}
                    """);
            send(input, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                    """);
            List<?> tools = (List<?>) ((Map<?, ?>) receive(output).get("result")).get("tools");
            assertThat(tools).hasSize(17);
            assertThat(tools.stream()
                    .map(tool -> String.valueOf(((Map<?, ?>) tool).get("name")))
                    .toList())
                    .contains("connector_get", "source_create", "artifact_apply", "pipeline_stop");

            send(input, """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                      "name":"source_create","arguments":{"id":"orders","connector":"mysql",
                      "config":{"host":"db.internal","password":"${MYSQL_PASSWORD}"}}}}
                    """);
            Map<?, ?> created = receive(output);
            Map<?, ?> result = (Map<?, ?>) created.get("result");
            assertThat(result.get("isError")).isEqualTo(false);
            assertThat(result.toString()).doesNotContain(SENTINEL_SECRET);
            assertThat(((Map<?, ?>) result.get("structuredContent")).get("configuredSecrets"))
                    .isEqualTo(List.of("password"));
        } finally {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
            server.stop(0);
        }

        assertThat(process.exitValue()).isZero();
        assertThat(requests).containsExactly("GET /api/connectors/mysql", "POST /api/sources");
        assertThat(authorization.get()).isEqualTo("Bearer " + MACHINE_TOKEN);
        assertThat(((Map<?, ?>) sourceRequest.get().get("config")).get("password"))
                .isEqualTo(SENTINEL_SECRET);
        assertThat(Files.readString(stderr))
                .doesNotContain(MACHINE_TOKEN)
                .doesNotContain(SENTINEL_SECRET);
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
        return server;
    }

    private static Process startMcp(HttpServer server, Path stderr) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", mcpBootJar().toString(),
                "--allow-write");
        builder.environment().put("TAPSTATE_SERVER_URL",
                "http://127.0.0.1:" + server.getAddress().getPort());
        builder.environment().put("TAPSTATE_TOKEN", MACHINE_TOKEN);
        builder.environment().put("MYSQL_PASSWORD", SENTINEL_SECRET);
        builder.redirectError(stderr.toFile());
        return builder.start();
    }

    private static Path mcpBootJar() {
        String file = System.getProperty(MCP_BOOT_JAR_PROPERTY);
        assertThat(file)
                .as("the build must set %s so the MCP process can be launched", MCP_BOOT_JAR_PROPERTY)
                .isNotBlank();
        Path jar = Path.of(file);
        assertThat(jar).isRegularFile();
        return jar;
    }

    private static void send(Writer input, String message) throws IOException {
        input.write(message.strip().replace("\n", ""));
        input.write('\n');
        input.flush();
    }

    private static Map<?, ?> receive(BufferedReader output) throws Exception {
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try {
                return output.readLine();
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        });
        String response = line.get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        assertThat(response).isNotBlank();
        return (Map<?, ?>) JsonReader.parse(response);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
