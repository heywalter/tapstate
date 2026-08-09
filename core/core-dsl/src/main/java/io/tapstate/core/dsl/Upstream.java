package io.tapstate.core.dsl;

/**
 * One thing a node reads: a source, and the table within it the wiring names. A null table is
 * "whichever tables that source holds" — the answer where the wiring cannot say, which is the only
 * answer that cannot leave a table unexamined.
 */
record Upstream(String source, String table) {
}
