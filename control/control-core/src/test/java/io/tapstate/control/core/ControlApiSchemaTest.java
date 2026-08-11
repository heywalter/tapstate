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
    void sourceDraftUsesTheStructuredSourceRequestAndYamlResult() {
        assertThat(ControlApiSchema.ref("source.draft").params()).isEqualTo("#/$defs/SourceDraftRequest");
        assertThat(ControlApiSchema.ref("source.draft").result()).isEqualTo("#/$defs/SourceDraftResult");
    }

}
