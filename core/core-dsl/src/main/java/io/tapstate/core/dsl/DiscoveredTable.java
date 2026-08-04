package io.tapstate.core.dsl;

import io.tapstate.core.common.TapstateType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One table a source was discovered to hold, as the type gate needs it: the table's name and the
 * resolved type of each column it carries.
 *
 * <p>A table is the unit because a table is what an expression runs on. Columns are not pooled across
 * the tables of a source: one database naming a column {@code id} in two unrelated tables, typed
 * differently, is ordinary rather than exceptional, and a pooled view would have to call such a column
 * unresolved and refuse every expression that reads it — on most real databases, for most expressions.
 * Keeping the tables apart means an expression is judged against the columns of the table it actually
 * reads, and where it genuinely reads several, against each of them in turn.
 */
public record DiscoveredTable(String name, Map<String, TapstateType> columns) {

    public DiscoveredTable {
        Objects.requireNonNull(name, "name");
        columns = columns == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }
}
