package io.tapstate.adapters.pdk;

import io.tapdata.entity.schema.TapField;
import io.tapdata.entity.schema.TapTable;
import io.tapdata.entity.schema.type.TapRaw;
import io.tapstate.core.common.TapstateType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the mapping from a connector's own declared column type onto the tapstate type namespace.
 *
 * <p>The input is never hand-built: each case drives the real fill from a real connector's verbatim
 * {@code dataTypes} declaration, so the case also witnesses that the fill actually carries the
 * distinguishing attribute across. Asserting on a hand-built type would pass even if the fill stopped
 * setting it, which is exactly the way the mapping would silently go wrong.
 */
class PdkTypeMappingTest {

    /** The mysql connector's own declaration for its decimal column type, verbatim from its spec. */
    private static final String DECIMAL_SPEC = """
            {"dataTypes": {"decimal[($precision,$scale)][unsigned]": {"to": "TapNumber",\
             "precision": [1, 65], "scale": [0, 30], "defaultPrecision": 10, "defaultScale": 0,\
             "unsigned": "unsigned", "fixed": true}}}""";

    @Test
    void aFixedPointColumnMapsOntoDecimal(@TempDir Path dir) {
        TapField amount = filled(dir, DECIMAL_SPEC, "amount", "decimal(18,4)");

        assertThat(PdkTypeMapping.of(amount.getTapType()))
                .as("a decimal column carries a scale no binary floating point type holds losslessly")
                .isEqualTo(TapstateType.DECIMAL);
    }

    /** The mysql connector's own declaration for its widest integer column type, verbatim from its spec. */
    private static final String BIGINT_SPEC = """
            {"dataTypes": {"bigint[($zerofill)]": {"to": "TapNumber", "bit": 64, "precision": 19,\
             "value": [-9223372036854775808, 9223372036854775807]}}}""";

    @Test
    void anIntegerColumnMapsOntoInt64(@TempDir Path dir) {
        TapField id = filled(dir, BIGINT_SPEC, "id", "bigint");

        assertThat(PdkTypeMapping.of(id.getTapType()))
                .as("an integer column holds no scale, so it maps losslessly onto the 64-bit integer")
                .isEqualTo(TapstateType.INT64);
    }

    /** The mysql connector's own declaration for its binary floating point column type, verbatim. */
    private static final String DOUBLE_SPEC = """
            {"dataTypes": {"double": {"to": "TapNumber", "precision": [1, 17], "preferPrecision": 11,\
             "preferScale": 4, "scale": [0, 17], "fixed": false}}}""";

    @Test
    void aBinaryFloatingPointColumnMapsOntoDouble(@TempDir Path dir) {
        TapField rate = filled(dir, DOUBLE_SPEC, "rate", "double");

        assertThat(PdkTypeMapping.of(rate.getTapType()))
                .as("the source itself already holds this column as binary floating point, so nothing is lost")
                .isEqualTo(TapstateType.DOUBLE);
    }

    /** The mysql connector's own declaration for its variable-length text column type, verbatim. */
    private static final String VARCHAR_SPEC = """
            {"dataTypes": {"varchar($byte)": {"name": "varchar", "to": "TapString", "byte": 16358,\
             "defaultByte": 1, "byteRatio": 3}}}""";

    @Test
    void aTextColumnMapsOntoString(@TempDir Path dir) {
        TapField customer = filled(dir, VARCHAR_SPEC, "customer", "varchar(64)");

        assertThat(PdkTypeMapping.of(customer.getTapType()))
                .as("text is the one column shape row expressions already read without losing anything")
                .isEqualTo(TapstateType.STRING);
    }

    @Test
    void aColumnTheConnectorDeclaresNothingAboutMapsOntoUnknown(@TempDir Path dir) {
        // The connector declares a mapping for bigint only. A column of any other type is one its own
        // machinery cannot resolve, and what comes back is the framework's raw fallback - not an absent
        // type, which is what makes the fallback worth pinning here.
        TapField amount = filled(dir, BIGINT_SPEC, "amount", "decimal(18,4)");
        assertThat(amount.getTapType()).as("an unresolvable column falls back to raw").isInstanceOf(TapRaw.class);

        assertThat(PdkTypeMapping.of(amount.getTapType()))
                .as("an unresolved column must be nameably unknown, never quietly one of the safe types")
                .isEqualTo(TapstateType.UNKNOWN);
    }

    @Test
    void aColumnWithNoResolvedTypeAtAllMapsOntoUnknown() {
        assertThat(PdkTypeMapping.of(null))
                .as("a connector declaring no mapping at all leaves the type absent, and absent is not safe")
                .isEqualTo(TapstateType.UNKNOWN);
    }

    /** Discovers one column of the given database type through a connector declaring {@code spec}. */
    private static TapField filled(Path dir, String spec, String column, String dataType) {
        ConnectorRef ref = new ConnectorRef(
                List.of(Synthetic.discoverableSource(dir)), "synthetic.Discoverable", "2.0.8", null, spec);
        try (PdkConnector connector = PdkConnector.open("demo", ref, Map.of())) {
            TapTable table = new TapTable("orders");
            table.add(new TapField(column, dataType));
            connector.fillFieldTypes(table);
            return table.getNameFieldMap().get(column);
        }
    }
}
