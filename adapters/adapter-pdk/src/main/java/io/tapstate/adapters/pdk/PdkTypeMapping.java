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

import java.math.BigDecimal;

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

    /** The widest domain a 64-bit integer covers: signed at the bottom, unsigned at the top. */
    private static final BigDecimal SIGNED_64_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal UNSIGNED_64_MAX = new BigDecimal("18446744073709551615");

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
     * <p>Marking it is optional and a great many connectors do not, so the absence of the mark cannot be
     * read as anything on its own. A type that declares a scale is scaled whatever else it left out, and
     * which of the two scaled kinds it is nobody said: calling it exact would refuse arithmetic that is
     * fine, calling it approximate would permit arithmetic that drops digits. A declared scale of zero is
     * different in kind - it is the column stating it holds no fractional part, rather than a scale it is
     * held to - and that is an integer.
     *
     * <p>Left with a number the connector marked neither way and gave no scale for, the question is what
     * it did say. Something integral - a width, a precision, the range of values it holds - is the
     * connector describing a whole number, and that is taken as one. Nothing at all is a connector that
     * named the type and stopped, which is how a real binary floating point column arrives from more than
     * one connector in the set: reading that silence as "integer" is what makes such a column come out
     * computable, so the gate admits arithmetic over it and the value that reaches the expression at run
     * time is the double the driver delivered. Unknown is the only answer that neither guesses nor
     * silently permits - it is refused by name and the author rules on it.
     */
    private static TapstateType number(TapNumber number) {
        Boolean fixed = number.getFixed();
        if (fixed != null) {
            return fixed ? TapstateType.DECIMAL : TapstateType.DOUBLE;
        }
        Integer scale = number.getScale();
        if (scale != null) {
            return scale == 0 ? TapstateType.INT64 : TapstateType.UNKNOWN;
        }
        return describesAWholeNumber(number) ? TapstateType.INT64 : TapstateType.UNKNOWN;
    }

    /**
     * Whether what the connector said about this number describes a whole number.
     *
     * <p>The range it declares is the thing to read, because it is the same question the type namespace
     * asks: a column stating it holds values up to 1.8e308 is not a 64-bit integer whatever else it is,
     * while one stating it holds up to 2^64-1 is - even unsigned, at the very top of the width. The
     * upper bound is the unsigned one so an unsigned bigint is not thrown out for exceeding the signed
     * range it never claimed to sit in.
     *
     * <p>A declared width answers it too, and is the fallback where the range is absent. It is only the
     * fallback: where both are stated the range is what the column actually holds, and a width beside a
     * range that overflows it is the width describing storage rather than domain.
     */
    private static boolean describesAWholeNumber(TapNumber number) {
        BigDecimal min = number.getMinValue();
        BigDecimal max = number.getMaxValue();
        if (min != null && max != null) {
            return min.compareTo(SIGNED_64_MIN) >= 0 && max.compareTo(UNSIGNED_64_MAX) <= 0;
        }
        return number.getBit() != null;
    }
}
