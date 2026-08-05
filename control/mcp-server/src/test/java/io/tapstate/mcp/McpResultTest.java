package io.tapstate.mcp;

import io.tapstate.control.client.ControlResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpResultTest {

    @Test
    void successfulValuesAreNormalizedToStringKeyedStructuredContent() {
        assertThat(McpResult.from(new ControlResponse.Success(200, Map.of(1, "value"))))
                .isEqualTo(new McpResult(false, Map.of("1", "value")));
        assertThat(McpResult.success(List.of("one", "two")).body())
                .containsEntry("value", List.of("one", "two"));
        assertThat(McpResult.success(null).body()).isEmpty();
    }

    @Test
    void malformedUpstreamRejectionUsesARegisteredMcpError() {
        McpResult result = McpResult.from(
                new ControlResponse.Rejected(502, "", "invalid upstream response", Map.of()));

        assertThat(result.error()).isTrue();
        assertThat(result.body())
                .containsEntry("code", "mcp.server-rejected")
                .containsEntry("params", Map.of("status", 502));
    }

    @Test
    void codedRejectionPreservesTheServerContractAndCodedErrorsRenderSolutions() {
        McpResult rejected = McpResult.from(new ControlResponse.Rejected(
                422, "source.invalid", "Invalid source.", Map.of("field", "host")));
        assertThat(rejected.error()).isTrue();
        assertThat(rejected.body()).containsEntry("code", "source.invalid")
                .containsEntry("status", 422)
                .containsEntry("params", Map.of("field", "host"));

        McpResult coded = McpResult.coded(McpError.ENVIRONMENT_MISSING,
                Map.of("variable", "MYSQL_PASSWORD"));
        assertThat(coded.body()).containsEntry("code", "mcp.environment-missing")
                .containsKey("solution");
    }
}
