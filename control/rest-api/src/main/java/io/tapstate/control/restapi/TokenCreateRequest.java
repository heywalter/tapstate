package io.tapstate.control.restapi;

/** Request body for issuing a scoped machine token. */
record TokenCreateRequest(String scope) {
}
