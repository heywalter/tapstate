package io.tapstate.e2e;

import io.tapstate.control.core.MonitorError;
import io.tapstate.core.common.JsonReader;
import io.tapstate.core.common.JsonWriter;
import io.tapstate.core.lifecycle.LifecycleVerb;
import io.tapstate.core.lifecycle.PipelineState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The product's HTTP surface, as a caller sees it.
 *
 * <p>The harness speaks this wire itself rather than borrowing the CLI's client: the CLI's own
 * testing is unit tests, a corpus and a native smoke, and pulling it in here would make every
 * specification a test of two things at once. What is shared with the product on purpose is the JSON
 * codec and the DSL parser - the places where a second implementation would be a second truth.
 *
 * <p>Failures are surfaced, never absorbed: a refused verb fails the specification carrying the
 * server's own status and body, because a harness that turns a 4xx into a quiet nothing would let a
 * broken product pass.
 */
final class ControlPlane {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final URI baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private String credential;

    ControlPlane(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Whether the product answers its health probe; the readiness signal a launcher waits on. */
    boolean healthy() {
        try {
            HttpResponse<String> response = send(get("/healthz"));
            return response.statusCode() == 200 && "ok".equals(response.body());
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    /**
     * Creates the first admin and holds its token for every later call. Bootstrap is refused off
     * loopback, which is why both tiers run the server on this machine rather than in a container.
     */
    void bootstrapAndLogin(String username, String password) {
        String body = JsonWriter.write(Map.of("username", username, "password", password));
        expect(send(post("/auth/bootstrap", body)), 204, "bootstrap the first admin");
        HttpResponse<String> login = send(post("/auth/login", body));
        expect(login, 200, "log in");
        if (!(JsonReader.parse(login.body()) instanceof Map<?, ?> map)
                || !(map.get("token") instanceof String token)) {
            throw new AssertionError("login returned no token: " + login.body());
        }
        credential = token;
    }

    /**
     * Applies resource documents as one batch, each named by the file it came from. One call, not one
     * per file: the product resolves references within the submitted set, so resources that name each
     * other have to be submitted together.
     */
    void apply(Map<String, String> contentBySource) {
        List<Map<String, String>> drafts = contentBySource.entrySet().stream()
                .map(entry -> Map.of("source", entry.getKey(), "content", entry.getValue()))
                .toList();
        String body = JsonWriter.write(Map.of("drafts", drafts));
        expect(send(authed("/api/artifacts:apply", body)), 200, "apply " + contentBySource.keySet());
    }

    /**
     * Applies a batch the product is expected to refuse, and returns the refusal it answered with.
     *
     * <p>A separate verb rather than a flag on {@link #apply}, for the same reason registration has one:
     * a caller that meant to apply and was refused has failed, and a caller that meant to witness a
     * refusal and got an apply has failed too. One return value cannot mean both.
     */
    Refusal applyExpectingRefusal(Map<String, String> contentBySource) {
        List<Map<String, String>> drafts = contentBySource.entrySet().stream()
                .map(entry -> Map.of("source", entry.getKey(), "content", entry.getValue()))
                .toList();
        String body = JsonWriter.write(Map.of("drafts", drafts));
        HttpResponse<String> response = send(authed("/api/artifacts:apply", body));
        return interpretRefusal(response.statusCode(), response.body(), "applying " + contentBySource.keySet());
    }

    /**
     * One document submitted for apply, optionally carrying the version it was written against. A null
     * {@code expectedContentHash} submits no precondition, which is the unconditional apply every caller
     * above sends.
     */
    record Draft(String source, String content, String expectedContentHash) {}

    /** Applies one document written against the version {@code expectedContentHash} names. */
    void applyExpecting(String source, String content, String expectedContentHash) {
        expect(send(authed("/api/artifacts:apply", applyBody(List.of(
                        new Draft(source, content, expectedContentHash))))),
                200, "apply " + source + " against version " + expectedContentHash);
    }

    /**
     * Attempts an apply carrying a precondition the product is expected to refuse, and returns the refusal.
     * The peer of {@link #applyExpecting}, kept apart from it for the reason every other refusal verb here
     * is kept apart from its success: one return value cannot mean both.
     */
    Refusal applyExpectingRefusal(String source, String content, String expectedContentHash) {
        HttpResponse<String> response = send(authed("/api/artifacts:apply",
                applyBody(List.of(new Draft(source, content, expectedContentHash)))));
        return interpretRefusal(response.statusCode(), response.body(), "applying " + source);
    }

    /**
     * The apply request body. A draft carrying no precondition omits the field entirely rather than sending
     * an explicit null: the field is defined as optional, and a caller that never asked for the check has to
     * travel the wire indistinguishably from one written before the field existed.
     */
    private static String applyBody(List<Draft> drafts) {
        List<Map<String, String>> encoded = drafts.stream()
                .map(draft -> draft.expectedContentHash() == null
                        ? Map.of("source", draft.source(), "content", draft.content())
                        : Map.of("source", draft.source(), "content", draft.content(),
                                "expectedContentHash", draft.expectedContentHash()))
                .toList();
        return JsonWriter.write(Map.of("drafts", encoded));
    }

    /** The ids the server holds - read back from the server, which is the truth, not from the files sent. */
    List<String> artifactIds() {
        HttpResponse<String> response = send(authedGet("/api/artifacts"));
        expect(response, 200, "list artifacts");
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)
                || !(map.get("artifacts") instanceof List<?> artifacts)) {
            throw new AssertionError("artifact list was not a list: " + response.body());
        }
        return artifacts.stream()
                .map(each -> each instanceof Map<?, ?> m ? m.get("id") : null)
                .map(String::valueOf)
                .toList();
    }

    /**
     * One stored artifact as the server hands it back, hash included. The hash is the precondition an edit
     * or a removal has to supply, so reading it here is what makes read-then-remove a closed loop over the
     * wire rather than something the caller computes locally off bytes it hopes are the same.
     */
    record StoredArtifact(String id, String kind, String canonicalForm, String contentHash) {}

    /**
     * The artifact stored under {@code id}, or empty when the server holds none.
     *
     * <p>Empty is a reading, not a failure: "it is gone" is the assertion a removal witness makes, and a
     * caller that could not distinguish an absent artifact from a broken read could not make it. Every
     * other non-200 stays loud.
     */
    Optional<StoredArtifact> artifact(String id) {
        HttpResponse<String> response = send(authedGet("/api/artifacts/" + urlSegment(id)));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        expect(response, 200, "read the artifact " + id);
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)) {
            throw new AssertionError("the artifact read was not an object: " + response.body());
        }
        return Optional.of(new StoredArtifact(
                string(map, "id", response.body()),
                string(map, "kind", response.body()),
                string(map, "canonicalForm", response.body()),
                string(map, "contentHash", response.body())));
    }

    /**
     * The content hash of the stored artifact {@code id}, failing when the server holds none.
     *
     * <p>Separate from {@link #artifact} because a caller reading a hash in order to spend it on a removal
     * has already assumed the artifact is there; getting an empty back would fail it one call later, on a
     * line that says nothing about what actually went wrong.
     */
    String contentHash(String id) {
        return artifact(id)
                .orElseThrow(() -> new AssertionError(
                        "no artifact " + id + " to read a content hash from"))
                .contentHash();
    }

    /** Removes the artifact {@code id}, offering {@code expectedContentHash} as the version the caller read. */
    void deleteArtifact(String id, String expectedContentHash) {
        expect(send(authedDelete(id, expectedContentHash)), 204, "delete " + id);
    }

    /**
     * Attempts a removal the product is expected to refuse, and returns the refusal it answered with. A
     * null {@code expectedContentHash} sends no {@code If-Match} at all, which is the unconditional
     * removal the product answers with its own missing-precondition code.
     *
     * <p>A separate verb rather than a flag on {@link #deleteArtifact}, for the reason the apply and
     * register pairs are separate: a caller that meant to remove and was refused has failed, and a caller
     * that meant to witness a refusal and got a removal has failed too - and the second is the regression
     * these callers exist to catch, so it can never be allowed to read as success.
     */
    Refusal deleteArtifactExpectingRefusal(String id, String expectedContentHash) {
        HttpResponse<String> response = send(authedDelete(id, expectedContentHash));
        return interpretRefusal(response.statusCode(), response.body(), "deleting " + id);
    }

    /** Registers a connector's runtime jar; the product makes this idempotent by content hash. */
    void registerConnector(String connectorId, byte[] jar) {
        String body = JsonWriter.write(Map.of("artifact", Base64.getEncoder().encodeToString(jar)));
        expect(send(authed("/api/connectors:register", body)), 200, "register the " + connectorId + " connector");
    }

    /**
     * The refusal a rejected verb answered with: the HTTP status, the code the product named, and the
     * named arguments it sent with it.
     *
     * <p>The arguments are here because several refusals are only actionable through them - who is still
     * referencing the resource, what state the pipeline is actually in - and a caller that could read the
     * code but not the arguments would have to assert that a refusal happened without ever checking it
     * named the right thing. They are empty, never null, for a body that carried none.
     */
    record Refusal(int status, String code, Map<String, Object> params) {

        Refusal(int status, String code) {
            this(status, code, Map.of());
        }
    }

    /**
     * Posts an artifact the product is expected to refuse, and returns the refusal it answered with.
     *
     * <p>A separate verb rather than a flag on {@link #registerConnector}: a caller that meant to
     * register and was refused has failed, and a caller that meant to witness a refusal and got a
     * registration has failed too. One return value cannot mean both.
     */
    Refusal registerConnectorExpectingRefusal(byte[] jar) {
        String body = JsonWriter.write(Map.of("artifact", Base64.getEncoder().encodeToString(jar)));
        HttpResponse<String> response = send(authed("/api/connectors:register", body));
        return interpretRefusal(response.statusCode(), response.body(), "registering the artifact");
    }

    /**
     * What an answer to a verb the caller expected to be refused is allowed to mean. Only a client error
     * is a refusal: it is the product having judged the request and declined it, which is the outcome
     * such a caller is witnessing.
     *
     * <p>Every other answer is this specification's own failure and stays loud. A success is the refusal
     * not happening at all - the regression these callers exist to catch. A server failure is the product
     * unable to answer, which says nothing about whether the request was acceptable; read as the expected
     * refusal it would let a server that fell over stand in for a gate that held, and the assertion on
     * the code would then be the only thing between a green run and a hole, on a body that carries no
     * code at all. A redirect is not a judgement either.
     */
    static Refusal interpretRefusal(int status, String body, String what) {
        if (status < 400) {
            throw new AssertionError(
                    "expected " + what + " to be refused, but the product did not refuse it: HTTP " + status
                            + " - " + body);
        }
        if (status >= 500) {
            throw new AssertionError(
                    "expected " + what + " to be refused, but the server failed instead: HTTP " + status
                            + " - " + body);
        }
        return new Refusal(status, codeOf(body), paramsOf(body));
    }

    /** Every connector id the online catalog answers with, registered rows and bundled ones alike. */
    List<String> connectorIds() {
        HttpResponse<String> response = send(authedGet("/api/connectors"));
        expect(response, 200, "list the online connector catalog");
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)
                || !(map.get("connectors") instanceof List<?> connectors)) {
            throw new AssertionError("the catalog listing carried no connectors: " + response.body());
        }
        return connectors.stream()
                .map(each -> each instanceof Map<?, ?> row ? row.get("id") : null)
                .map(String::valueOf)
                .toList();
    }

    /** Discovers a source's model, which is what a target table is later derived from. */
    void discoverSchema(String resourceId, String connectorId, Map<String, Object> settings) {
        String body = JsonWriter.write(
                Map.of("id", resourceId, "connectorId", connectorId, "settings", settings));
        expect(send(authed("/api/connections:discover-schema", body)), 200, "discover the model of " + resourceId);
    }

    /**
     * Records a lifecycle intent. The verb's own spelling comes from the product's enum, so the wire
     * word cannot drift from the word the product accepts.
     */
    void lifecycle(String pipelineId, LifecycleVerb verb) {
        expect(send(authed("/api/pipelines/" + pipelineId + ":" + verb.id(), "")),
                200, verb.id() + " " + pipelineId);
    }

    /**
     * The published lifecycle state, or empty when the pipeline has published no observation yet.
     *
     * <p>Empty is a reading, not a failure. Between recording a start intent and the first convergence
     * pass there is no observation at all and the product says so with a coded refusal - so a caller that
     * took that for fatal could never wait for a pipeline to come up, which is the one thing waiting is
     * for. Answering with some state instead would be worse still: it would invent a reading.
     *
     * <p>Empty is weaker than it looks: this read is served from the published observations alone, so a
     * pipeline that was never applied answers exactly as one that is applied and not yet converged. A
     * caller cannot tell "still coming" from "never existed" here, and a wait for the second will spend
     * its whole bound before saying so.
     */
    Optional<PipelineState> state(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/status"));
        return interpretState(response.statusCode(), response.body(), pipelineId);
    }

    /**
     * Attempts a status read the product is expected to refuse, and returns the refusal it answered with.
     *
     * <p>Kept apart from {@link #state} because the two emptinesses are not the same thing. That reader
     * treats "no observation published yet" as a reading and keeps every other refusal loud, which is what
     * lets a caller wait for a pipeline to come up. A pipeline that no longer exists is a different answer
     * with a different code, and a caller witnessing that has to see the code rather than an absence it
     * cannot tell from "not converged yet".
     */
    Refusal stateExpectingRefusal(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + urlSegment(pipelineId) + "/status"));
        return interpretRefusal(response.statusCode(), response.body(), "reading the status of " + pipelineId);
    }

    /**
     * The published error count, or empty when the pipeline has published no observation yet.
     *
     * <p>Empty is a reading and not a failure, on the same terms {@link #state} is: the metrics face answers
     * the product's own {@code monitor.no-observation} code for a pipeline no convergence pass has reached,
     * and a wait exists to sit through exactly that window.
     */
    Optional<Long> errorCount(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/metrics"));
        return interpretErrorCount(response.statusCode(), response.body(), pipelineId);
    }

    /**
     * The canonical code of the published failure, or empty when the pipeline has published none — it is
     * healthy, or no convergence pass has reached it yet.
     *
     * <p>Empty is a reading and not a failure, on the same terms {@link #state} is. The two emptinesses are
     * deliberately one here: a specification asserting a failure code is waiting for a run to die, and
     * "not dead yet" and "no observation yet" are both answered by waiting.
     */
    Optional<String> failureCode(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/status"));
        return interpretFailureCode(response.statusCode(), response.body(), pipelineId);
    }

    /**
     * What a status answer is allowed to say about a failure, read the way the two above are: only the
     * product's own {@code monitor.no-observation} code reads as "nothing published yet", every other refusal
     * stays loud. A healthy pipeline carries no failure field at all, which is the empty reading; a failure
     * present but missing its code is a regression of the status contract and is surfaced rather than waited
     * out.
     */
    static Optional<String> interpretFailureCode(int status, String body, String pipelineId) {
        if (status == 404 && MonitorError.NO_OBSERVATION.code().equals(codeOf(body))) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new AssertionError(
                    "could not read the status of " + pipelineId + ": expected HTTP 200, got " + status
                            + " - " + body);
        }
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map)) {
            throw new AssertionError("status answer was not an object: " + body);
        }
        if (!(map.get("failure") instanceof Map<?, ?> failure)) {
            return Optional.empty();
        }
        if (!(failure.get("code") instanceof String code)) {
            throw new AssertionError("status carried a failure with no code: " + body);
        }
        return Optional.of(code);
    }

    /**
     * What a status answer is allowed to mean. Only the product's own {@code monitor.no-observation} code
     * reads as "nothing published yet"; every other refusal stays loud, another code's 404 included. A rule
     * written on the status alone would let a route that 404s for its own reasons pass for a pipeline that
     * is merely slow to converge, and the specification would sit out its whole bound and then blame the
     * data. The code is the product's contract for exactly this distinction, so the code is what is read.
     */
    static Optional<PipelineState> interpretState(int status, String body, String pipelineId) {
        if (status == 404 && MonitorError.NO_OBSERVATION.code().equals(codeOf(body))) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new AssertionError(
                    "could not read the status of " + pipelineId + ": expected HTTP 200, got " + status
                            + " - " + body);
        }
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map) || !(map.get("state") instanceof String state)) {
            throw new AssertionError("status carried no state: " + body);
        }
        return Optional.of(PipelineState.valueOf(state));
    }

    /**
     * What a metrics answer is allowed to mean, read exactly the way a status answer is: only the product's
     * own {@code monitor.no-observation} code reads as "nothing published yet", and every other refusal stays
     * loud. A published observation always carries the errorCount metric - the runtime derives it from the
     * actual state - so a 200 that omits it is a regression of that contract, surfaced rather than waited out
     * as though the pipeline were merely slow to converge.
     */
    static Optional<Long> interpretErrorCount(int status, String body, String pipelineId) {
        if (status == 404 && MonitorError.NO_OBSERVATION.code().equals(codeOf(body))) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new AssertionError(
                    "could not read the metrics of " + pipelineId + ": expected HTTP 200, got " + status
                            + " - " + body);
        }
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map) || !(map.get("metrics") instanceof Map<?, ?> metrics)) {
            throw new AssertionError("metrics answer carried no metrics: " + body);
        }
        if (!(metrics.get("errorCount") instanceof Number errorCount)) {
            throw new AssertionError("metrics carried no errorCount: " + body);
        }
        return Optional.of(errorCount.longValue());
    }

    /**
     * The code a structured error body carries, or null for a body that is not one - a body that does not
     * parse included. A refusal can come from something that is not the product at all (an empty body, a
     * proxy's HTML), and the caller's job is to report that loudly with the pipeline and status named; it
     * cannot do that if reading the body for a code throws a parse error over the top of it.
     */
    private static String codeOf(String body) {
        try {
            return JsonReader.parse(body) instanceof Map<?, ?> map && map.get("code") instanceof String code
                    ? code
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The named arguments a structured error body carries, or none for a body that is not one. Read on the
     * same terms as {@link #codeOf}: a refusal that came from something other than the product must still be
     * reportable, so an unparseable body answers empty rather than throwing over the top of the report.
     */
    private static Map<String, Object> paramsOf(String body) {
        try {
            if (!(JsonReader.parse(body) instanceof Map<?, ?> map)
                    || !(map.get("params") instanceof Map<?, ?> params)) {
                return Map.of();
            }
            // Copied through a map that tolerates a null value rather than Map.copyOf, which rejects one. A
            // single null argument would otherwise throw here and lose the whole refusal - code, message and
            // all - which is the failure this harness exists to report, not to suffer.
            Map<String, Object> copy = new LinkedHashMap<>();
            params.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return Collections.unmodifiableMap(copy);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** A required string field of a structured answer, named in the failure when the answer omits it. */
    private static String string(Map<?, ?> map, String field, String body) {
        if (!(map.get(field) instanceof String value)) {
            throw new AssertionError("the artifact read carried no " + field + ": " + body);
        }
        return value;
    }

    /**
     * One path segment, encoded the way the product's own clients encode it - form encoding with the plus
     * put back as {@code %20}, because a literal plus in a path is a plus and not a space.
     *
     * <p>Written out here rather than borrowed: the harness travels the public wire, so what it needs is an
     * id that survives the trip, not the product's private helper. Ids that need any of this are pinned
     * where the client builds the request; over this wire it keeps a specification honest about the address
     * it asked for.
     */
    private static String urlSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(TIMEOUT).GET().build();
    }

    /**
     * A conditional removal. A null hash sends no {@code If-Match} header at all rather than an empty one:
     * an empty header is a malformed precondition, and the caller that passes null is witnessing the
     * refusal of a removal that carried no precondition in the first place.
     */
    private HttpRequest authedDelete(String id, String expectedContentHash) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve("/api/artifacts/" + urlSegment(id)))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + requireCredential())
                .DELETE();
        if (expectedContentHash != null) {
            builder.header("If-Match", "\"" + expectedContentHash + "\"");
        }
        return builder.build();
    }

    private HttpRequest authedGet(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + requireCredential())
                .GET()
                .build();
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest authed(String path, String body) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + requireCredential())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * The bearer credential this harness logged in with, for handing to a second client that has to reach
     * the same server as the same principal - the shipped MCP sidecar, which is configured with a token
     * rather than a login.
     */
    String credential() {
        return requireCredential();
    }

    private String requireCredential() {
        if (credential == null) {
            throw new IllegalStateException("no credential: log in before driving an authenticated verb");
        }
        return credential;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while calling " + request.uri(), e);
        }
    }

    private static void expect(HttpResponse<String> response, int status, String what) {
        if (response.statusCode() != status) {
            throw new AssertionError(
                    "could not " + what + ": expected HTTP " + status + ", got " + response.statusCode()
                            + " - " + response.body());
        }
    }
}
