package io.tapstate.cli;

import io.tapstate.core.common.TapstateException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/** Foregrounds the MCP sidecar while preserving stdio for its protocol transport. */
@Command(name = "mcp", mixinStandardHelpOptions = true,
        description = "Run the local stdio MCP server for one Tapstate Server.")
final class McpCmd implements Callable<Integer> {

    @Option(names = "--server", paramLabel = "URL",
            description = "Tapstate Server URL (default: $TAPSTATE_SERVER_URL, then http://127.0.0.1:8080).")
    String server;

    @Option(names = "--allow-write",
            description = "Expose write tools; the Server still enforces the token scope.")
    boolean allowWrite;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        try {
            return McpLauncher.launch(server, allowWrite);
        } catch (TapstateException error) {
            Diagnostics.printText(CliIo.err(spec), error.code(), error.args());
            CliIo.err(spec).flush();
            return Cli.EXIT_VERB_UNAVAILABLE;
        }
    }
}
