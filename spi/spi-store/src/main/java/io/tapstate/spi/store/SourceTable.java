package io.tapstate.spi.store;

import java.util.List;
import java.util.Objects;

/**
 * One discovered stream (table): its logical name, its fields, its primary-key column names, its
 * indexes, and how many rows it held when it was discovered. An immutable value.
 *
 * <p>{@code name} is always present. {@code primaryKey} is the ordered list of key column names (empty
 * when the source declares none). {@code fields}, {@code primaryKey} and {@code indexes} are each held
 * as an unmodifiable defensive copy; a null list is normalized to empty.
 *
 * <p>{@code approximateRowCount} is the one component here that is a measurement rather than a shape,
 * and it is null wherever no measurement was taken — a source that cannot count, a count that was not
 * reached, a model discovered before counting existed. Null is kept distinct from zero deliberately:
 * both would let a reader size something at nothing, but only one of them says the source is empty,
 * and a reader that cannot tell them apart sizes an unmeasured table as an empty one. It is what the
 * last discovery counted and is not maintained afterwards; the row count of a live table moves
 * constantly, and nothing here moves with it.
 */
public record SourceTable(
        String name,
        List<SourceField> fields,
        List<String> primaryKey,
        List<SourceIndex> indexes,
        Long approximateRowCount) {

    public SourceTable {
        Objects.requireNonNull(name, "name");
        fields = fields == null ? List.of() : List.copyOf(fields);
        primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        indexes = indexes == null ? List.of() : List.copyOf(indexes);
    }

    /** A table nothing counted, which is every table discovery could not get a count out of. */
    public SourceTable(String name, List<SourceField> fields, List<String> primaryKey, List<SourceIndex> indexes) {
        this(name, fields, primaryKey, indexes, null);
    }
}
