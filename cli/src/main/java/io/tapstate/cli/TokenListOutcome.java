package io.tapstate.cli;

import java.util.List;

sealed interface TokenListOutcome {
    record Listed(List<RemoteToken> tokens) implements TokenListOutcome {
        public Listed {
            tokens = List.copyOf(tokens);
        }
    }

    record Rejected(String code, String message) implements TokenListOutcome {
    }

    record Unreachable() implements TokenListOutcome {
    }
}
