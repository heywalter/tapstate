package io.tapstate.mcp;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Process options for the one-host, foreground stdio sidecar. */
record McpOptions(URI server, String token, boolean allowWrite) {

    private static final URI DEFAULT_SERVER = URI.create("http://127.0.0.1:8080");

    static McpOptions parse(String[] arguments, Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(environment, "environment");
        URI server = server(environment.get("TAPSTATE_SERVER_URL"));
        boolean allowWrite = false;
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.equals("--allow-write")) {
                allowWrite = true;
            } else if (argument.equals("--server")) {
                if (++index >= arguments.length) {
                    throw new IllegalArgumentException("--server requires an HTTP URL");
                }
                server = server(arguments[index]);
            } else if (argument.startsWith("--server=")) {
                server = server(argument.substring("--server=".length()));
            } else if (argument.equals("--token") || argument.startsWith("--token=")) {
                throw new IllegalArgumentException("--token is forbidden; use TAPSTATE_TOKEN");
            } else {
                throw new IllegalArgumentException("unknown MCP option: " + argument);
            }
        }
        String token = environment.get("TAPSTATE_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("TAPSTATE_TOKEN must be set");
        }
        return new McpOptions(server, token, allowWrite);
    }

    private static URI server(String configured) {
        URI uri = configured == null || configured.isBlank() ? DEFAULT_SERVER : URI.create(configured);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("MCP Server URL must be an absolute HTTP(S) URL");
        }
        return uri;
    }

    @Override
    public String toString() {
        return "McpOptions[server=" + server + ", token=********, allowWrite=" + allowWrite + "]";
    }
}
