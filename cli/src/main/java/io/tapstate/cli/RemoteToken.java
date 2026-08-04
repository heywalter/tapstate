package io.tapstate.cli;

/** Secret-free machine-token descriptor returned by the control plane. */
record RemoteToken(String tokenId, String scope, boolean revoked, String createdAt) {
}
