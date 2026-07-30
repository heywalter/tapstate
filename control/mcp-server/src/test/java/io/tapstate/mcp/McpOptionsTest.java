package io.tapstate.mcp;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOptionsTest {

    @Test
    void tokenComesOnlyFromEnvironmentAndCliServerOverridesEnvironment() {
        McpOptions options = McpOptions.parse(
                new String[] {"--server", "https://tapstate.example", "--allow-write"},
                Map.of("TAPSTATE_TOKEN", "machine-secret", "TAPSTATE_SERVER_URL", "https://ignored.example"));

        assertThat(options.server()).isEqualTo(URI.create("https://tapstate.example"));
        assertThat(options.token()).isEqualTo("machine-secret");
        assertThat(options.allowWrite()).isTrue();
        assertThat(options.toString()).doesNotContain("machine-secret");
    }

    @Test
    void environmentServerAndLoopbackDefaultAreSupported() {
        assertThat(McpOptions.parse(new String[0], Map.of(
                "TAPSTATE_TOKEN", "token", "TAPSTATE_SERVER_URL", "https://server.example")).server())
                .isEqualTo(URI.create("https://server.example"));
        assertThat(McpOptions.parse(new String[0], Map.of("TAPSTATE_TOKEN", "token")).server())
                .isEqualTo(URI.create("http://127.0.0.1:8080"));
    }

    @Test
    void tokenFlagAndMissingEnvironmentTokenAreRejected() {
        assertThatThrownBy(() -> McpOptions.parse(
                new String[] {"--token", "leak"}, Map.of("TAPSTATE_TOKEN", "token")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--token");
        assertThatThrownBy(() -> McpOptions.parse(new String[0], Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TAPSTATE_TOKEN");
    }
}
