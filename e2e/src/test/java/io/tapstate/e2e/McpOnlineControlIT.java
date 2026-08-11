package io.tapstate.e2e;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A fake control peer used to verify MCP readiness behavior over the public wire.
 */
class McpOnlineControlIT {

    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void readinessProbeTimesOutWhenPeerDoesNotRespond() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requestReceived.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try {
            assertThatThrownBy(() -> awaitServer(server, Duration.ofSeconds(1)))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(requestReceived.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
        server.start();
        return server;
    }

    private static void awaitServer(HttpServer server) throws Exception {
        awaitServer(server, READINESS_TIMEOUT);
    }

    private static void awaitServer(HttpServer server, Duration timeout) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/_ready"))
                        .timeout(timeout)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
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
