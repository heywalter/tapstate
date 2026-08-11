package io.tapstate.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.tapstate.control.client.HttpControlClient;
import io.tapstate.control.core.ControlApiSchema;
import io.tapstate.control.core.ControlOperations;
import io.tapstate.control.core.Frontend;
import io.tapstate.control.core.Maturity;
import io.tapstate.control.core.Operation;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {

    private static final List<String> READ_TOOLS = List.of(
            "connector_list", "connector_get",
            "connection_test_result", "connection_schema", "artifact_validate",
            "pipeline_status", "pipeline_metrics", "pipeline_snapshot", "pipeline_logs");

    private static final List<String> WRITE_TOOLS = List.of(
            "artifact_apply", "connection_test", "connection_discover_schema",
            "pipeline_start", "pipeline_stop");

    @Test
    void defaultSurfaceContainsExactlyTheNineReadTools() {
        assertThat(McpToolCatalog.operations(false).stream().map(McpToolCatalog::toolName))
                .containsExactlyInAnyOrderElementsOf(READ_TOOLS);
    }

    @Test
    void allowWriteAddsExactlyTheFiveWriteTools() {
        assertThat(McpToolCatalog.operations(true).stream().map(McpToolCatalog::toolName))
                .containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, WRITE_TOOLS));
    }

    @Test
    void sdkSpecificationsAreDerivedFromRegistryDescriptionsAndSchemas() {
        try (HttpControlClient client = new HttpControlClient()) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    URI.create("http://127.0.0.1:1"), "token", Map.of(), client);
            List<SyncToolSpecification> specifications = McpToolCatalog.specifications(false, executor);

            for (SyncToolSpecification specification : specifications) {
                Operation operation = ControlOperations.registry()
                        .exposedOn(Frontend.MCP, Maturity.BETA).stream()
                        .filter(candidate -> McpToolCatalog.toolName(candidate).equals(specification.tool().name()))
                        .findFirst()
                        .orElseThrow();
                assertThat(specification.tool().description()).isEqualTo(operation.description());
                assertThat(specification.tool().inputSchema())
                        .isEqualTo(ControlApiSchema.resolve(operation.schema().params()));
                assertThat(specification.tool().outputSchema())
                        .isEqualTo(ControlApiSchema.resolve(operation.schema().result()));
            }
        }
    }

    @Test
    void sdkSpecificationHandlerProjectsExecutorResultIntoMcpResult() {
        try (HttpControlClient client = new HttpControlClient()) {
            McpOperationExecutor executor = new McpOperationExecutor(
                    URI.create("http://127.0.0.1:1"), "token", Map.of(), client);
            SyncToolSpecification specification = McpToolCatalog.specifications(false, executor).stream()
                    .filter(candidate -> candidate.tool().name().equals("connector_list"))
                    .findFirst()
                    .orElseThrow();

            McpSchema.CallToolResult result = specification.callHandler().apply(
                    null, new McpSchema.CallToolRequest("connector_list", Map.of()));

            assertThat(result.isError()).isTrue();
            assertThat(result.structuredContent()).isInstanceOf(Map.class);
            assertThat(result.content()).isNotEmpty();
        }
    }

    private static List<String> concat(List<String> left, List<String> right) {
        return java.util.stream.Stream.concat(left.stream(), right.stream()).toList();
    }
}
