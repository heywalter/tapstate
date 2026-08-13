package io.tapstate.control.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ControlApiSchemaTest {

    private static final Set<String> MCP_OPERATIONS = Set.of(
            "connector.list", "connector.get",
            "source.draft",
            "connection.test", "connection.test-result", "connection.discover-schema", "connection.schema",
            "artifact.validate", "artifact.apply",
            "pipeline.start", "pipeline.stop", "pipeline.status", "pipeline.metrics",
            "pipeline.snapshot", "pipeline.logs");

    @Test
    void betaMcpSurfaceIsExactlyTheOnlinePipelineClosure() {
        Set<String> actual = ControlOperations.registry().exposedOn(Frontend.MCP, Maturity.BETA).stream()
                .map(Operation::id)
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(MCP_OPERATIONS);
    }

    @Test
    void everyMcpOperationHasResolvableRequestAndResultSchemaRefs() {
        Map<String, Object> document = ControlApiSchema.document();
        assertThat(document).containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema");
        assertThat(document.get("$defs")).isInstanceOf(Map.class);
        Map<?, ?> definitions = (Map<?, ?>) document.get("$defs");

        for (Operation operation : ControlOperations.registry().exposedOn(Frontend.MCP, Maturity.BETA)) {
            assertThat(operation.description()).as(operation.id() + " description").isNotBlank();
            assertThat(operation.schema()).as(operation.id()).isNotNull();
            assertThat(operation.schema().params()).as(operation.id() + " params").startsWith("#/$defs/");
            assertThat(operation.schema().result()).as(operation.id() + " result").startsWith("#/$defs/");
            assertThat(definitions.containsKey(
                    operation.schema().params().substring("#/$defs/".length()))).isTrue();
            assertThat(definitions.containsKey(
                    operation.schema().result().substring("#/$defs/".length()))).isTrue();
        }
    }

    @Test
    void sourceDraftDescriptionMakesTheServerOwnTheLiveConnectorContract() {
        assertThat(ControlOperations.SOURCE_DRAFT.description())
                .contains("known connector", "live connector contract", "canonical YAML");
    }

    @Test
    void sourceDraftLeavesOnlyConnectorConfigOpenForTheLiveContract() {
        Map<?, ?> definitions = (Map<?, ?>) ControlApiSchema.document().get("$defs");
        Map<?, ?> request = (Map<?, ?>) definitions.get("SourceDraftRequest");
        Map<?, ?> properties = (Map<?, ?>) request.get("properties");
        Map<?, ?> config = (Map<?, ?>) properties.get("config");

        assertThat(request.get("additionalProperties")).isEqualTo(false);
        assertThat(request.get("required")).isEqualTo(java.util.List.of("id", "connector", "config"));
        assertThat(properties.keySet().stream().map(String::valueOf).toList()).containsExactlyInAnyOrder(
                "id", "metadata", "connector", "config", "mode", "tables",
                "options", "srs", "experimental", "clearSecrets");
        assertThat(config.get("type")).isEqualTo("object");
        assertThat(config.get("additionalProperties")).isEqualTo(true);
        assertThat(definitions.get("SourceDraftResult")).isEqualTo(Map.of(
                "type", "object",
                "properties", Map.of("yaml", Map.of(
                        "type", "string", "minLength", 1,
                        "description", "Canonical tapstate/v1 Source YAML")),
                "additionalProperties", false,
                "required", java.util.List.of("yaml")));
    }

    @Test
    void sourceDraftSchemaConstrainsNestedFieldsButKeepsExtensionMapsOpen() {
        Map<?, ?> definitions = (Map<?, ?>) ControlApiSchema.document().get("$defs");
        Map<?, ?> request = (Map<?, ?>) definitions.get("SourceDraftRequest");
        Map<?, ?> properties = (Map<?, ?>) request.get("properties");

        Map<?, ?> metadata = (Map<?, ?>) properties.get("metadata");
        assertThat(metadata.get("additionalProperties")).isEqualTo(false);
        assertThat(((Map<?, ?>) metadata.get("properties")).keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("labels", "description");

        Map<?, ?> table = (Map<?, ?>) ((Map<?, ?>) properties.get("tables")).get("items");
        assertThat(((Map<?, ?>) ((Map<?, ?>) table.get("properties")).get("type")).get("enum"))
                .isEqualTo(java.util.List.of("literal", "regex", "spec"));

        Map<?, ?> srs = (Map<?, ?>) properties.get("srs");
        assertThat(((Map<?, ?>) ((Map<?, ?>) srs.get("properties")).get("schemaEvolution")).get("enum"))
                .isEqualTo(java.util.List.of("track", "ignore"));
        assertThat(((Map<?, ?>) ((Map<?, ?>) srs.get("properties")).get("queryable")).get("type"))
                .isEqualTo("boolean");
        assertThat(((Map<?, ?>) properties.get("options")).get("additionalProperties"))
                .isEqualTo(true);
    }
}
