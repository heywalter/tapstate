package io.tapstate.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpEnvironmentTest {

    @Test
    void controlCredentialsAreNeverAvailableToSourceConfigExpansion() {
        McpEnvironment environment = new McpEnvironment(Map.of(
                "TAPSTATE_TOKEN", "machine-token",
                "TAPSTATE_SERVER_URL", "https://server.example",
                "MYSQL_PASSWORD", "connector-secret"));

        assertThat(environment.values())
                .containsExactly(Map.entry("MYSQL_PASSWORD", "connector-secret"));
    }
}
