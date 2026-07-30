package io.tapstate.mcp;

import io.tapstate.control.client.ControlResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpResultTest {

    @Test
    void malformedUpstreamRejectionUsesARegisteredMcpError() {
        McpResult result = McpResult.from(
                new ControlResponse.Rejected(502, "", "invalid upstream response", Map.of()));

        assertThat(result.error()).isTrue();
        assertThat(result.body())
                .containsEntry("code", "mcp.server-rejected")
                .containsEntry("params", Map.of("status", 502));
    }
}
