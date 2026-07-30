package io.tapstate.cli;

import io.tapstate.core.common.TapstateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpLauncherTest {

    @Test
    void nativeSidecarIsPreferredAndReceivesOnlyMcpOptions(@TempDir Path bundle) throws Exception {
        Path cli = Files.createDirectories(bundle.resolve("bin")).resolve("tapstate");
        Files.createFile(cli);
        Path nativeSidecar = Files.createDirectories(bundle.resolve("libexec")).resolve("tapstate-mcp");
        Files.createFile(nativeSidecar);
        assertThat(nativeSidecar.toFile().setExecutable(true)).isTrue();

        List<String> command = McpLauncher.command(
                cli, Map.of(), Map.of(), "https://server.example", true);

        assertThat(command).containsExactly(
                nativeSidecar.toString(), "--server", "https://server.example", "--allow-write");
    }

    @Test
    void jarFallbackRequiresAJavaRuntime(@TempDir Path bundle) throws Exception {
        Path cli = Files.createDirectories(bundle.resolve("bin")).resolve("tapstate");
        Files.createFile(cli);
        Path jar = Files.createDirectories(bundle.resolve("libexec")).resolve("tapstate-mcp.jar");
        Files.createFile(jar);

        assertThatThrownBy(() -> McpLauncher.command(
                cli, Map.of(), Map.of(), null, false))
                .isInstanceOf(TapstateException.class)
                .hasMessageContaining("cli.mcp-unavailable");

        Path javaHome = Files.createDirectories(bundle.resolve("jdk/bin"));
        Path java = javaHome.resolve("java");
        Files.createFile(java);
        assertThat(java.toFile().setExecutable(true)).isTrue();

        assertThat(McpLauncher.command(
                cli, Map.of("JAVA_HOME", bundle.resolve("jdk").toString()), Map.of(), null, false))
                .containsExactly(java.toString(), "-jar", jar.toString());
        assertThat(McpLauncher.command(
                cli, Map.of("PATH", javaHome.toString()), Map.of(), null, false))
                .containsExactly(java.toString(), "-jar", jar.toString());
    }

    @Test
    void missingSidecarIsReportedAsACodedDiagnostic(@TempDir Path bundle) throws Exception {
        Path cli = Files.createDirectories(bundle.resolve("bin")).resolve("tapstate");
        Files.createFile(cli);

        assertThatThrownBy(() -> McpLauncher.command(cli, Map.of(), Map.of(), null, false))
                .isInstanceOf(TapstateException.class)
                .hasMessageContaining("cli.mcp-unavailable");
    }

    @Test
    void jarFallbackRecognizesOnlyJava21OrNewerVersionOutput() {
        assertThat(McpLauncher.supportsJava21("openjdk version \"21.0.6\" 2025-01-21")).isTrue();
        assertThat(McpLauncher.supportsJava21("java version \"25.0.1\" 2025-10-21 LTS")).isTrue();
        assertThat(McpLauncher.supportsJava21("openjdk version \"17.0.12\" 2024-07-16")).isFalse();
        assertThat(McpLauncher.supportsJava21("unexpected output")).isFalse();
    }
}
