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
            "connector_list", "connector_get", "source_list", "source_get", "source_draft",
            "connection_test_result", "connection_schema", "artifact_validate", "artifact_get",
            "pipeline_status", "pipeline_metrics", "pipeline_snapshot", "pipeline_logs");

    private static final List<String> WRITE_TOOLS = List.of(
            "artifact_apply", "artifact_delete", "connection_test", "connection_discover_schema",
            "pipeline_start", "pipeline_stop");

    /**
     * The read that supplies the removal's precondition has to be reachable without write access.
     * Landing it in the write bucket would make the hash obtainable only in a session that already
     * holds the power to destroy, which defeats the point of reading before deciding to.
     */
    @Test
    void theReadThatSuppliesTheRemovalPreconditionIsAvailableWithoutWriteAccess() {
        assertThat(McpToolCatalog.operations(false).stream().map(McpToolCatalog::toolName))
                .contains("artifact_get");
    }

    @Test
    void defaultSurfaceContainsExactlyTheThirteenReadTools() {
        assertThat(McpToolCatalog.operations(false).stream().map(McpToolCatalog::toolName))
                .containsExactlyInAnyOrderElementsOf(READ_TOOLS);
    }

    @Test
    void allowWriteAddsExactlyTheSixWriteTools() {
        assertThat(McpToolCatalog.operations(true).stream().map(McpToolCatalog::toolName))
                .containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, WRITE_TOOLS));
    }

    /**
     * The one tool on this surface that destroys a named resource must not be reachable from a session
     * that was not started with write access. The exact-set assertions above would also catch it, but
     * only as one name among nineteen; this says which property is load-bearing, so a future edit that
     * re-scopes the operation fails against a test that explains why it may not.
     */
    @Test
    void theDestructiveToolIsAbsentFromAReadOnlySession() {
        assertThat(McpToolCatalog.operations(false).stream().map(McpToolCatalog::toolName))
                .doesNotContain("artifact_delete");
        assertThat(McpToolCatalog.operations(true).stream().map(McpToolCatalog::toolName))
                .contains("artifact_delete");
    }

    /**
     * The tool name is derived, and it is a published promise the moment the sidecar advertises it: a
     * model calls {@code artifact_delete} by that literal name. Pinning it here means a change to the
     * derivation, or to the operation id, breaks a test rather than a caller.
     */
    @Test
    void theRemovalToolIsNamedArtifactDelete() {
        assertThat(McpToolCatalog.toolName(ControlOperations.ARTIFACT_DELETE)).isEqualTo("artifact_delete");
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
