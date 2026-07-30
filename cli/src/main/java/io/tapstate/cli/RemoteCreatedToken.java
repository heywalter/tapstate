package io.tapstate.cli;

/** Newly issued machine token carrying the bearer value shown exactly once. */
record RemoteCreatedToken(String tokenId, String scope, String token, String createdAt) {
}
