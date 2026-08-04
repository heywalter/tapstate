package io.tapstate.adapters.pdk;

import io.tapdata.entity.schema.type.TapArray;
import io.tapdata.entity.schema.type.TapBinary;
import io.tapdata.entity.schema.type.TapBoolean;
import io.tapdata.entity.schema.type.TapDate;
import io.tapdata.entity.schema.type.TapDateTime;
import io.tapdata.entity.schema.type.TapJson;
import io.tapdata.entity.schema.type.TapMap;
import io.tapdata.entity.schema.type.TapNumber;
import io.tapdata.entity.schema.type.TapString;
import io.tapdata.entity.schema.type.TapTime;
import io.tapdata.entity.schema.type.TapType;
import io.tapdata.entity.schema.type.TapYear;
import io.tapstate.core.common.TapstateType;

/**
 * Maps a PDK type onto the tapstate type namespace: the normalization step that turns what a connector
 * declared about a column into what every other layer reasons about.
 *
 * <p>The mapping is a projection, not a judgement - it says what a column is, never what may be done
 * with it. A shape this does not name maps onto unknown, which is deliberate in both directions: a
 * connector type nothing here covers must not arrive somewhere as one of the named types, and it must
 * arrive as something a caller has to decide about rather than as an absence.
 */
final class PdkTypeMapping {

    private PdkTypeMapping() {
    }

    /** The tapstate type for a filled PDK type. */
    static TapstateType of(TapType type) {
        return switch (type) {
            case TapNumber number -> number(number);
            case TapString ignored -> TapstateType.STRING;
            case TapBoolean ignored -> TapstateType.BOOLEAN;
            case TapDate ignored -> TapstateType.DATE;
            case TapTime ignored -> TapstateType.TIME;
            case TapDateTime ignored -> TapstateType.DATETIME;
            case TapYear ignored -> TapstateType.YEAR;
            case TapBinary ignored -> TapstateType.BINARY;
            case TapJson ignored -> TapstateType.JSON;
            case TapArray ignored -> TapstateType.ARRAY;
            case TapMap ignored -> TapstateType.MAP;
            case null, default -> TapstateType.UNKNOWN;
        };
    }

    /**
     * Splits a number by how the source holds it. Where a connector marks the split itself it is taken as
     * stated: a type marked fixed is exact and scaled, one marked not fixed is binary floating point.
     *
     * <p>Marking it is optional, and a great many connectors do not - so the absence cannot be read as
     * "integer". A type that declares a scale is scaled whatever else it left out, and which of the two
     * scaled kinds it is nobody said: calling it exact would refuse arithmetic that is fine, calling it
     * approximate would permit arithmetic that drops digits. Unknown is the only answer that neither
     * guesses nor silently permits - it is refused by name and the author rules on it. Only a number that
     * declares no scale at all is taken as an integer.
     */
    private static TapstateType number(TapNumber number) {
        Boolean fixed = number.getFixed();
        if (fixed != null) {
            return fixed ? TapstateType.DECIMAL : TapstateType.DOUBLE;
        }
        Integer scale = number.getScale();
        if (scale != null && scale != 0) {
            return TapstateType.UNKNOWN;
        }
        return TapstateType.INT64;
    }
}
