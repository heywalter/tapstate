package io.tapstate.cli;

import io.tapstate.core.common.TapstateException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locates and foregrounds the MCP sidecar shipped next to the CLI. */
final class McpLauncher {

    private static final Pattern JAVA_VERSION = Pattern.compile("\\bversion\\s+\"(?:1\\.)?(\\d+)");

    private McpLauncher() {
    }

    static int launch(String server, boolean allowWrite) {
        List<String> command = command(
                currentExecutable(), System.getenv(), systemProperties(), server, allowWrite);
        if (command.size() > 1
                && command.get(1).equals("-jar")
                && !hasJava21Runtime(Path.of(command.get(0)))) {
            throw unavailable("tapstate-mcp.jar requires Java 21 or newer", null);
        }
        Process child;
        try {
            child = new ProcessBuilder(command).inheritIO().start();
        } catch (IOException error) {
            throw unavailable("could not start the installed sidecar", error);
        }
        Thread shutdown = new Thread(child::destroy, "tapstate-mcp-launcher-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            return child.waitFor();
        } catch (InterruptedException error) {
            child.destroy();
            Thread.currentThread().interrupt();
            throw unavailable("launcher interrupted while waiting for the sidecar", error);
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // Shutdown is already in progress and owns child termination.
            }
        }
    }

    static List<String> command(
            Path cliExecutable,
            Map<String, String> environment,
            Map<String, String> properties,
            String server,
            boolean allowWrite) {
        Path installedCli;
        try {
            installedCli = cliExecutable.toRealPath();
        } catch (IOException error) {
            throw unavailable("cannot resolve the installed CLI executable", error);
        }
        Path bin = installedCli.getParent();
        if (bin == null) {
            throw unavailable("cannot resolve the CLI installation directory", null);
        }
        Path libexec = bin.resolve("../libexec").normalize();
        Path nativeSidecar = libexec.resolve("tapstate-mcp");
        List<String> command = new ArrayList<>();
        if (Files.isRegularFile(nativeSidecar) && Files.isExecutable(nativeSidecar)) {
            command.add(nativeSidecar.toString());
        } else {
            Path jar = libexec.resolve("tapstate-mcp.jar");
            if (!Files.isRegularFile(jar)) {
                throw unavailable("no sidecar found under " + libexec, null);
            }
            Path java = javaExecutable(environment, properties);
            if (java == null) {
                throw unavailable("tapstate-mcp.jar requires a Java 21 runtime", null);
            }
            command.add(java.toString());
            command.add("-jar");
            command.add(jar.toString());
        }
        if (server != null && !server.isBlank()) {
            command.add("--server");
            command.add(server);
        }
        if (allowWrite) {
            command.add("--allow-write");
        }
        return List.copyOf(command);
    }

    private static Path currentExecutable() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .orElseThrow(() -> unavailable("cannot resolve the running CLI executable", null));
    }

    private static Path javaExecutable(
            Map<String, String> environment, Map<String, String> properties) {
        Path java = javaUnder(environment.get("JAVA_HOME"));
        if (java != null) {
            return java;
        }
        java = javaUnder(properties.get("java.home"));
        if (java != null) {
            return java;
        }
        String path = environment.get("PATH");
        if (path != null && !path.isBlank()) {
            for (String directory : path.split(File.pathSeparator)) {
                try {
                    java = executable(Path.of(directory), "java");
                    if (java != null) {
                        return java;
                    }
                } catch (InvalidPathException ignored) {
                    // Continue through malformed PATH entries to find an installed runtime.
                }
            }
        }
        return null;
    }

    private static Path javaUnder(String javaHome) {
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        try {
            return executable(Path.of(javaHome, "bin"), "java");
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static Path executable(Path directory, String name) {
        Path candidate = directory.resolve(name);
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return candidate;
        }
        Path windowsCandidate = directory.resolve(name + ".exe");
        return Files.isRegularFile(windowsCandidate) && Files.isExecutable(windowsCandidate)
                ? windowsCandidate : null;
    }

    private static boolean hasJava21Runtime(Path java) {
        Process process;
        try {
            process = new ProcessBuilder(java.toString(), "-version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0
                    && supportsJava21(new String(
                            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean supportsJava21(String versionOutput) {
        Matcher matcher = JAVA_VERSION.matcher(versionOutput);
        if (!matcher.find()) {
            return false;
        }
        try {
            return Integer.parseInt(matcher.group(1)) >= 21;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private static Map<String, String> systemProperties() {
        String javaHome = System.getProperty("java.home");
        return javaHome == null ? Map.of() : Map.of("java.home", javaHome);
    }

    private static TapstateException unavailable(String reason, Throwable cause) {
        return new TapstateException(CliError.MCP_UNAVAILABLE, Map.of("reason", reason), cause);
    }
}
