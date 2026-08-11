package io.tapstate.mcp;

import io.tapstate.control.client.ControlResponse;
import io.tapstate.control.client.HttpControlClient;
import io.tapstate.control.client.RequestBudget;
import io.tapstate.control.core.ControlError;
import io.tapstate.control.core.Operation;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.dsl.DslParser;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.canonical.CanonicalWriter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Executes one registered operation through the Server's HTTP control contract. */
final class McpOperationExecutor {

    private final URI server;
    private final String token;
    private final Map<String, String> environment;
    private final HttpControlClient client;
    private final DslParser parser = new DslParser();
    private final CanonicalWriter writer = new CanonicalWriter();

    McpOperationExecutor(
            URI server,
            String token,
            Map<String, String> environment,
            HttpControlClient client) {
        this.server = Objects.requireNonNull(server, "server");
        this.token = Objects.requireNonNull(token, "token");
        this.environment = Map.copyOf(environment);
        this.client = Objects.requireNonNull(client, "client");
    }

    McpResult execute(Operation operation, Map<String, Object> arguments) {
        Objects.requireNonNull(operation, "operation");
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        try {
            return switch (operation.id()) {
                case "connector.list" -> get("/api/connectors");
                case "connector.get" -> get("/api/connectors/" + segment(required(args, "id")));
                case "source.draft" -> sourceDraft(args);
                case "connection.test" -> connectionWrite(args, "/api/connections:test");
                case "connection.test-result" -> get(
                        "/api/connections/" + segment(required(args, "id")) + "/test-result");
                case "connection.discover-schema" -> connectionWrite(
                        args, "/api/connections:discover-schema");
                case "connection.schema" -> get(
                        "/api/connections/" + segment(required(args, "id")) + "/schema");
                case "artifact.validate" -> post(
                        "/api/artifacts:validate", args, RequestBudget.HEAVY);
                case "artifact.apply" -> post("/api/artifacts:apply", args, RequestBudget.HEAVY);
                case "pipeline.start" -> pipelineAction(args, "start");
                case "pipeline.stop" -> pipelineAction(args, "stop");
                case "pipeline.status" -> pipelineRead(args, "status");
                case "pipeline.metrics" -> pipelineRead(args, "metrics");
                case "pipeline.snapshot" -> pipelineRead(args, "snapshot");
                case "pipeline.logs" -> pipelineLogs(args);
                default -> McpResult.coded(
                        ControlError.MALFORMED_REQUEST,
                        Map.of("reason", "unsupported MCP operation: " + operation.id()));
            };
        } catch (TapstateException error) {
            return McpResult.coded(error.code(), error.args());
        }
    }

    private McpResult pipelineAction(Map<String, Object> arguments, String action) {
        return post(
                "/api/pipelines/" + segment(required(arguments, "id")) + ":" + action,
                null,
                RequestBudget.LIGHT);
    }

    private McpResult connectionWrite(Map<String, Object> arguments, String path) {
        Map<String, Object> expanded = new LinkedHashMap<>(arguments);
        expanded.put("settings", EnvironmentExpander.expand(arguments.get("settings"), environment));
        return post(path, expanded, RequestBudget.HEAVY);
    }

    private McpResult sourceDraft(Map<String, Object> arguments) {
        String connector = required(arguments, "connector");
        List<String> secretFields = connectorSecretFields(connector);
        if (secretFields == null) {
            return McpResult.coded(
                    McpError.CONNECTOR_SPEC_UNAVAILABLE,
                    Map.of("connector", connector));
        }
        Map<String, Object> expanded = new LinkedHashMap<>(arguments);
        expanded.put("config", EnvironmentExpander.expand(arguments.get("config"), environment));
        return redactSourceDraft(
                post("/api/sources:draft", expanded, RequestBudget.HEAVY), secretFields, connector);
    }

    private List<String> connectorSecretFields(String connector) {
        McpResult result = get("/api/connectors/" + segment(connector));
        if (result.error()) {
            return null;
        }
        Object config = result.body().get("config");
        if (!(config instanceof List<?> fields)) {
            return null;
        }
        List<String> secrets = new java.util.ArrayList<>();
        for (Object field : fields) {
            if (!(field instanceof Map<?, ?> view)
                    || !(view.get("name") instanceof String name)
                    || !(view.get("secret") instanceof Boolean secret)) {
                return null;
            }
            if (secret) {
                secrets.add(name);
            }
        }
        return List.copyOf(secrets);
    }

    private McpResult redactSourceDraft(
            McpResult result, List<String> secretFields, String connector) {
        if (result.error() || secretFields.isEmpty()) {
            return result;
        }
        Object value = result.body().get("yaml");
        if (!(value instanceof String yaml)) {
            return McpResult.coded(
                    McpError.CONNECTOR_SPEC_UNAVAILABLE,
                    Map.of("connector", connector));
        }
        try {
            Resource resource = parser.parse(yaml);
            if (!(resource instanceof SourceResource source)) {
                return McpResult.coded(
                        McpError.CONNECTOR_SPEC_UNAVAILABLE,
                        Map.of("connector", connector));
            }
            Map<String, Object> config = new LinkedHashMap<>(source.config());
            secretFields.forEach(config::remove);
            SourceResource redacted = new SourceResource(
                    source.id(),
                    source.metadata(),
                    source.connector(),
                    config,
                    source.mode(),
                    source.tables(),
                    source.options(),
                    source.srs(),
                    source.experimental());
            Map<String, Object> body = new LinkedHashMap<>(result.body());
            body.put("yaml", writer.write(redacted));
            return new McpResult(false, body);
        } catch (RuntimeException error) {
            return McpResult.coded(
                    McpError.CONNECTOR_SPEC_UNAVAILABLE,
                    Map.of("connector", connector));
        }
    }

    private McpResult pipelineRead(Map<String, Object> arguments, String view) {
        return get("/api/pipelines/" + segment(required(arguments, "id")) + "/" + view);
    }

    private McpResult pipelineLogs(Map<String, Object> arguments) {
        String path = "/api/pipelines/" + segment(required(arguments, "id")) + "/logs";
        Object limit = arguments.get("limit");
        if (limit instanceof Number number) {
            path += "?limit=" + Math.max(1, Math.min(200, number.intValue()));
        }
        return get(path);
    }

    private McpResult get(String path) {
        return McpResult.from(client.get(server, token, path));
    }

    private McpResult post(String path, Object body, RequestBudget budget) {
        return McpResult.from(client.post(server, token, path, body, budget));
    }

    private static String required(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new TapstateException(
                    ControlError.MALFORMED_REQUEST,
                    Map.of("reason", "a non-blank `" + name + "` is required"),
                    null);
        }
        return text;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
