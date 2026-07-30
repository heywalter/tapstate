package io.tapstate.control.core;

import java.time.Instant;

/** A newly issued machine token, including the bearer value that is returned exactly once. */
public record CreatedToken(String tokenId, Scope scope, String token, Instant createdAt) {
}
