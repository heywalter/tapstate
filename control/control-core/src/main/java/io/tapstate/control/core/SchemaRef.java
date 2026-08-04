package io.tapstate.control.core;

/**
 * References into the shared control-operation JSON Schema for an operation's request parameters and
 * result. Each side is an opaque pointer string (e.g. a JSON-pointer into {@code $defs}); either side may
 * be {@code null} when the operation takes no parameters or returns nothing. The reference is stored, not
 * resolved here; a protocol adapter resolves it against the bundled control schema.
 */
public record SchemaRef(String params, String result) {}
