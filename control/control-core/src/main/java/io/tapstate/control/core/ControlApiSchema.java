package io.tapstate.control.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON Schema document for control-operation parameters and results. */
public final class ControlApiSchema {

    private static final Map<String, SchemaRef> REFS = refs();
    private static final Map<String, Object> DEFINITIONS = definitions();

    private ControlApiSchema() {
    }

    /** Returns the request/result references for an MCP operation. */
    public static SchemaRef ref(String operationId) {
        SchemaRef ref = REFS.get(operationId);
        if (ref == null) {
            throw new IllegalArgumentException("no control schema for operation: " + operationId);
        }
        return ref;
    }

    /** Returns the complete deterministic Draft 2020-12 schema document. */
    public static Map<String, Object> document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        document.put("$id", "https://tapstate.io/schema/control/v1");
        document.put("$defs", DEFINITIONS);
        return immutableMap(document);
    }

    /** Resolves a local definition reference for protocol adapters. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolve(String ref) {
        String prefix = "#/$defs/";
        if (ref == null || !ref.startsWith(prefix)) {
            throw new IllegalArgumentException("unsupported control schema ref: " + ref);
        }
        Object definition = DEFINITIONS.get(ref.substring(prefix.length()));
        if (!(definition instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("unknown control schema ref: " + ref);
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, SchemaRef> refs() {
        Map<String, SchemaRef> refs = new LinkedHashMap<>();
        bind(refs, "connector.list", "ConnectorList");
        bind(refs, "connector.get", "ConnectorGet");
        bind(refs, "source.list", "SourceList");
        bind(refs, "source.get", "SourceGet");
        bind(refs, "source.create", "SourceCreate");
        bind(refs, "source.draft", "SourceDraft");
        bind(refs, "connection.test", "ConnectionTest");
        bind(refs, "connection.test-result", "ConnectionTestResult");
        bind(refs, "connection.discover-schema", "ConnectionDiscoverSchema");
        bind(refs, "connection.schema", "ConnectionSchema");
        bind(refs, "artifact.validate", "ArtifactValidate");
        bind(refs, "artifact.apply", "ArtifactApply");
        bind(refs, "artifact.delete", "ArtifactDelete");
        bind(refs, "pipeline.start", "PipelineStart");
        bind(refs, "pipeline.stop", "PipelineStop");
        bind(refs, "pipeline.status", "PipelineStatus");
        bind(refs, "pipeline.metrics", "PipelineMetrics");
        bind(refs, "pipeline.snapshot", "PipelineSnapshot");
        bind(refs, "pipeline.logs", "PipelineLogs");
        return immutableMap(refs);
    }

    private static void bind(Map<String, SchemaRef> refs, String operationId, String stem) {
        refs.put(operationId, new SchemaRef("#/$defs/" + stem + "Request", "#/$defs/" + stem + "Result"));
    }

    private static Map<String, Object> definitions() {
        Map<String, Object> defs = new LinkedHashMap<>();
        Map<String, Object> empty = object(List.of(), Map.of(), false);
        Map<String, Object> opaque = object(List.of(), Map.of(), true);
        Map<String, Object> id = string("Tapstate resource identifier");

        pair(defs, "ConnectorList", empty, opaque);
        pair(defs, "ConnectorGet", object(List.of("id"), Map.of("id", id), false), opaque);
        pair(defs, "SourceList", empty, opaque);
        pair(defs, "SourceGet", object(List.of("id"), Map.of("id", id), false), opaque);

        Map<String, Object> sourceProperties = new LinkedHashMap<>();
        sourceProperties.put("id", id);
        sourceProperties.put("metadata", object(List.of(), Map.of(
                "labels", Map.of("type", "object", "additionalProperties", Map.of("type", "string")),
                "description", string("Free-text Source description")), false));
        sourceProperties.put("connector", string("Registered connector id"));
        sourceProperties.put("config", object(List.of(), Map.of(), true));
        sourceProperties.put("mode", enumString("snapshot", "cdc", "stream", "file", "api"));
        Map<String, Object> table = object(List.of("type"), Map.of(
                "type", enumString("literal", "regex", "spec"),
                "name", string("Literal table name"),
                "pattern", string("Regular expression selecting tables"),
                "filter", string("Row filter expression"),
                "pk", array(string("Primary-key field name")),
                "options", opaque), false);
        sourceProperties.put("tables", array(table));
        sourceProperties.put("options", opaque);
        sourceProperties.put("srs", object(List.of(), Map.of(
                "key", string("Shared Record Store key"),
                "retention", string("Shared Record Store retention duration"),
                "schemaEvolution", enumString("track", "ignore"),
                "queryable", Map.of("type", "boolean"),
                "enabled", Map.of("type", "boolean")), false));
        sourceProperties.put("experimental", opaque);
        sourceProperties.put("clearSecrets", array(string("Config secret field to clear")));
        Map<String, Object> sourceRequest =
                object(List.of("id", "connector", "config"), sourceProperties, false);
        pair(defs, "SourceCreate", sourceRequest, opaque);
        pair(defs, "SourceDraft", sourceRequest, object(
                List.of("yaml"),
                Map.of("yaml", string("Canonical tapstate/v1 Source YAML")),
                false));

        Map<String, Object> connectionProperties = new LinkedHashMap<>();
        connectionProperties.put("id", id);
        connectionProperties.put("connectorId", string("Connector id"));
        connectionProperties.put("settings", object(List.of(), Map.of(), true));
        Map<String, Object> connectionRequest = object(
                List.of("id", "connectorId", "settings"), connectionProperties, false);
        pair(defs, "ConnectionTest", connectionRequest, opaque);
        pair(defs, "ConnectionDiscoverSchema", connectionRequest, opaque);
        pair(defs, "ConnectionTestResult", object(List.of("id"), Map.of("id", id), false), opaque);
        pair(defs, "ConnectionSchema", object(List.of("id"), Map.of("id", id), false), opaque);

        Map<String, Object> draft = object(
                List.of("content"),
                Map.of("source", string("Origin label for diagnostics"), "content", string("tapstate/v1 YAML")),
                false);
        Map<String, Object> artifactRequest = object(
                List.of("drafts"), Map.of("drafts", array(draft)), false);
        pair(defs, "ArtifactValidate", artifactRequest, opaque);
        pair(defs, "ArtifactApply", artifactRequest, opaque);

        // Both arguments are required. A delete carries an id and nothing else, so without the hash the
        // caller would be discarding a version it never read; making it optional is the same thing for any
        // caller that omits what it may omit.
        Map<String, Object> deleteRequest = object(
                List.of("id", "expectedContentHash"),
                Map.of(
                        "id", id,
                        "expectedContentHash",
                        string("Content hash of the version being removed, as returned by a read of it")),
                false);
        pair(defs, "ArtifactDelete", deleteRequest, opaque);

        Map<String, Object> pipelineId = object(List.of("id"), Map.of("id", id), false);
        pair(defs, "PipelineStart", pipelineId, opaque);
        pair(defs, "PipelineStop", pipelineId, opaque);
        pair(defs, "PipelineStatus", pipelineId, opaque);
        pair(defs, "PipelineMetrics", pipelineId, opaque);
        pair(defs, "PipelineSnapshot", pipelineId, opaque);
        Map<String, Object> logsRequest = object(
                List.of("id"),
                Map.of("id", id, "limit", integer(1, 200, "Maximum lines, capped by the server")),
                false);
        pair(defs, "PipelineLogs", logsRequest, opaque);
        return immutableMap(defs);
    }

    private static void pair(
            Map<String, Object> defs, String stem, Map<String, Object> request, Map<String, Object> result) {
        defs.put(stem + "Request", request);
        defs.put(stem + "Result", result);
    }

    private static Map<String, Object> object(
            List<String> required, Map<String, Object> properties, boolean additionalProperties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", immutableMap(properties));
        schema.put("additionalProperties", additionalProperties);
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return immutableMap(schema);
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "minLength", 1, "description", description);
    }

    private static Map<String, Object> enumString(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> integer(int minimum, int maximum, String description) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "description", description);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
