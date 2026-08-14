package io.tapstate.adapters.pdk;

import io.tapstate.spi.sink.TargetField;
import io.tapstate.spi.sink.TargetTable;
import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;

import java.util.LinkedHashMap;

/**
 * Builds a PDK {@link TapTable} descriptor from a resolved tapstate {@link TargetTable}: each target
 * field becomes a typed column, and the primary-key fields become the key an upsert matches on, in
 * field order. The descriptor is what a connector reads to create the table and to coerce each row
 * value to its column type.
 *
 * <p>A key field is given a primary-key position in field order, which both marks it as a key and
 * fixes the key's column order — set through the position, never the flag alone, so the derived key is
 * ordered rather than lost.
 */
final class TargetTapTable {

    private TargetTapTable() {
    }

    static TapTable build(TargetTable target) {
        TapTable table = bare(target.name());
        int keyPos = 1;
        for (TargetField field : target.fields()) {
            TapField column = new TapField(field.name(), field.type());
            if (field.primaryKey()) {
                column.primaryKeyPos(keyPos++);
            }
            table.add(column);
        }
        return table;
    }

    /**
     * A descriptor naming a table and declaring no columns, for a stream no model was resolved for.
     *
     * <p>The column map is created here rather than left to the first column that is added. A connector
     * reads the key off a descriptor before it writes anything, and a descriptor whose map was never
     * created answers that read by throwing - inside the connector, where the cause reads as a failure to
     * write rather than as a model that was never resolved. Declaring no columns is the contract for this
     * case; being unable to say so is not.
     */
    static TapTable bare(String name) {
        TapTable table = new TapTable(name);
        table.setNameFieldMap(new LinkedHashMap<>());
        return table;
    }
}
