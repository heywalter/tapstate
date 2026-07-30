package io.tapstate.control.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tapstate.core.common.JsonReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpControlClientTest {

    @Test
    void postSendsBearerAndJsonAndReturnsTheDecodedTree() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<Object> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(JsonReader.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            answer(exchange, 201, "{\"tokenId\":\"tok-1\"}");
        });
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            ControlResponse response = client.post(
                    baseOf(server), "secret", "/api/tokens", Map.of("scope", "write"), RequestBudget.LIGHT);

            assertThat(response).isInstanceOfSatisfying(ControlResponse.Success.class, success -> {
                assertThat(success.status()).isEqualTo(201);
                assertThat(success.body()).isEqualTo(Map.of("tokenId", "tok-1"));
            });
            assertThat(authorization.get()).isEqualTo("Bearer secret");
            assertThat(body.get()).isEqualTo(Map.of("scope", "write"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void codedHttpFailureRetainsStatusCodeMessageAndParams() throws Exception {
        HttpServer server = server(exchange -> answer(exchange, 403,
                "{\"code\":\"control.forbidden\",\"message\":\"Forbidden.\","
                        + "\"params\":{\"required\":\"admin\"}}"));
        try (HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(2))) {
            ControlResponse response = client.get(baseOf(server), "read-token", "/api/tokens");

            assertThat(response).isEqualTo(new ControlResponse.Rejected(
                    403, "control.forbidden", "Forbidden.", Map.of("required", "admin")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionFailureIsAStableUnreachableOutcome() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (HttpControlClient client = new HttpControlClient(
                Duration.ofMillis(250), Duration.ofMillis(250))) {
            assertThat(client.get(URI.create("http://127.0.0.1:" + closedPort), "token", "/api/tokens"))
                    .isInstanceOf(ControlResponse.Unreachable.class);
        }
    }

    @Test
    void requestBudgetBoundsAStalledExchange() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            received.countDown();
            try {
                release.await();
                answer(exchange, 200, "{}");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        try (HttpControlClient client = new HttpControlClient(
                Duration.ofMillis(250), Duration.ofMillis(250))) {
            CompletableFuture<ControlResponse> response = CompletableFuture.supplyAsync(() ->
                    client.get(baseOf(server), "token", "/api/tokens"));

            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(response.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ControlResponse.Unreachable.class);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void closeCancelsAnInFlightExchange() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            received.countDown();
            try {
                release.await();
                answer(exchange, 200, "{}");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        HttpControlClient client = new HttpControlClient(Duration.ofSeconds(1), Duration.ofSeconds(30));
        try {
            CompletableFuture<ControlResponse> response = CompletableFuture.supplyAsync(() -> client.post(
                    baseOf(server), "token", "/api/artifacts:apply", Map.of(), RequestBudget.HEAVY));

            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            client.close();

            assertThat(response.get(2, TimeUnit.SECONDS))
                    .isInstanceOf(ControlResponse.Unreachable.class);
        } finally {
            release.countDown();
            client.close();
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
