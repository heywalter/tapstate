package io.tapstate.app;

import io.tapstate.core.common.TapstateErrorCode;
import io.tapstate.core.common.Severity;

import java.util.Set;

/**
 * The {@code actuation} domain's error codes: the assembly root failing to resolve a pipeline's runnable
 * topology from its stored artifact when a start actuates it. A desired-to-run pipeline whose artifact is
 * absent, or an id that names a resource of another kind, is a user-facing, diagnosable failure carried
 * through the error-code system and rendered through the shared message catalog - distinct from the
 * {@code engine} domain, which polices operating the Jet job once the topology is built.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for each
 * name, and the build-time placeholder gate checks the catalog templates against it.
 */
enum ActuationError implements TapstateErrorCode {

    /** A start named a pipeline id with no stored artifact to run: {@code pipeline} is the id given. */
    PIPELINE_NOT_FOUND("actuation.pipeline-not-found", Set.of("pipeline")),

    /**
     * A start named an id that resolves to a resource of another kind: {@code pipeline} is the id given and
     * {@code kind} is the kind actually stored under it.
     */
    NOT_A_PIPELINE("actuation.not-a-pipeline", Set.of("pipeline", "kind")),

    /** A source omitted discovery required to expand its table selection; {@code source} is its id. */
    SOURCE_SCHEMA_NOT_DISCOVERED("actuation.source-schema-not-discovered", Set.of("source")),

    /** A source reference names no discovered table; {@code source} and {@code table} identify it. */
    SOURCE_TABLE_NOT_DISCOVERED("actuation.source-table-not-discovered", Set.of("source", "table")),

    /** A source selector set expands to no tables; {@code source} is its id. */
    SOURCE_TABLE_SELECTION_EMPTY("actuation.source-table-selection-empty", Set.of("source")),

    /** A source table selector is not valid Java regex syntax; {@code source} and {@code regex} carry the input. */
    SOURCE_TABLE_REGEX_INVALID("actuation.source-table-regex-invalid", Set.of("source", "regex")),

    /** A bare table name is selected by several sources; {@code sources} lists the conflicting source ids. */
    SOURCE_TABLE_AMBIGUOUS("actuation.source-table-ambiguous", Set.of("table", "sources")),

    /** A table object carries settings the current capture path does not implement; fields lists their names. */
    SOURCE_TABLE_SPEC_UNSUPPORTED("actuation.source-table-spec-unsupported", Set.of("source", "table", "fields")),

    /** A serve.from regex is invalid; {@code regex} carries the expression. */
    FROM_REGEX_INVALID("actuation.from-regex-invalid", Set.of("regex")),

    /** A serve.from regex matches no upstream vertex; {@code regex} carries the expression. */
    FROM_REGEX_EMPTY("actuation.from-regex-empty", Set.of("regex"));

    private final String code;
    private final Set<String> placeholders;

    ActuationError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
