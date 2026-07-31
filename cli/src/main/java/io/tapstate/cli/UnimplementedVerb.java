package io.tapstate.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Unmatched;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The handler shared by every verb that is declared but not built yet (registered under each name in
 * {@link Cli}). Such a verb stays on the table so it is discoverable and so its eventual spelling is
 * reserved, but it must say what is actually true of it: it does not exist yet. Reporting "not
 * connected" here would be false in both states — connecting does not implement it.
 */
@Command(mixinStandardHelpOptions = true, description = "(not implemented yet)")
final class UnimplementedVerb implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    /**
     * Swallows whatever the verb was given. A verb with no implementation has no argument grammar to
     * enforce yet, and rejecting the operands would report a usage error about a command that does not
     * exist — burying the only fact the user needs.
     *
     * <p>The standard help mixin above is the exception: its options are declared, so they are matched
     * before anything reaches here. A reserved verb is exactly the one a user needs described, since
     * "not implemented yet" only answers the question once they know what it was reserved for.
     */
    @Unmatched
    List<String> ignored;

    @Override
    public Integer call() {
        Diagnostics.printText(CliIo.err(spec), CliError.VERB_NOT_IMPLEMENTED, Map.of("verb", spec.name()));
        return Cli.EXIT_VERB_UNAVAILABLE;
    }
}
