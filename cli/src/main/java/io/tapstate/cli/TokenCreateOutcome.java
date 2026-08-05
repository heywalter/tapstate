package io.tapstate.cli;

sealed interface TokenCreateOutcome {
    record Issued(RemoteCreatedToken token) implements TokenCreateOutcome {
    }

    record Rejected(String code, String message) implements TokenCreateOutcome {
    }

    record Unreachable() implements TokenCreateOutcome {
    }
}
