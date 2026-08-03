package io.tapstate.adapters.pdk;

import io.tapdata.entity.schema.type.TapNumber;
import io.tapdata.entity.schema.type.TapString;
import io.tapdata.entity.schema.type.TapType;
import io.tapstate.core.common.TapstateType;

/**
 * Maps a PDK type onto the tapstate type namespace: the normalization step that turns what a connector
 * declared about a column into what every other layer reasons about.
 */
final class PdkTypeMapping {

    private PdkTypeMapping() {
    }

    /** The tapstate type for a filled PDK type. */
    static TapstateType of(TapType type) {
        if (type instanceof TapNumber number) {
            return number(number);
        }
        if (type instanceof TapString) {
            return TapstateType.STRING;
        }
        return TapstateType.UNKNOWN;
    }

    /**
     * Splits a number by how the source holds it. A connector declares the split itself: a type it marks
     * fixed is exact and scaled, one it marks not fixed is binary floating point, and one it marks neither
     * carries no scale at all - an integer.
     */
    private static TapstateType number(TapNumber number) {
        if (number.getFixed() == null) {
            return TapstateType.INT64;
        }
        return number.getFixed() ? TapstateType.DECIMAL : TapstateType.DOUBLE;
    }
}
