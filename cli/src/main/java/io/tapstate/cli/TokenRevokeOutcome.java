package io.tapstate.cli;

sealed interface TokenRevokeOutcome {
    record Revoked() implements TokenRevokeOutcome {
    }

    record Rejected(String code, String message) implements TokenRevokeOutcome {
    }

    record Unreachable() implements TokenRevokeOutcome {
    }
}
