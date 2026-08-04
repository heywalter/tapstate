package io.tapstate.adapters.pdk;

import io.tapstate.core.event.Envelope;
import io.tapdata.entity.event.dml.TapInsertRecordEvent;
import io.tapdata.entity.event.dml.TapUpdateRecordEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what a decoded row actually holds, as opposed to which field lands where (that is the golden's
 * job). A driver hands over whatever box its own client uses - an int column may arrive in any integral
 * box, a real one as a float - while the type namespace a column is resolved into names one width per
 * kind. This is the seam where the two are made to agree: the same boundary that says a column is
 * {@code INT64} also delivers a value that is one.
 *
 * <p>The conversions widen or leave alone and never round, so no value changes on the way in. Anything
 * the namespace does not name a lossless target for is left exactly as it came.
 */
class TapEventValueModelTest {

    @Test
    void anIntegerColumnArrivesAsTheSixtyFourBitIntegerItsTypeSaysItIs() {
        Envelope env = insert(row("qty", 5));

        assertThat(env.after().get("qty"))
                .as("the namespace has one integer width, so the row must speak it")
                .isEqualTo(5L)
                .isInstanceOf(Long.class);
    }

    @Test
    void theNarrowerIntegralBoxesWidenTheSameWay() {
        Envelope env = insert(row("small", (short) 7, "tiny", (byte) 3));

        assertThat(env.after().get("small")).isEqualTo(7L);
        assertThat(env.after().get("tiny")).isEqualTo(3L);
    }

    @Test
    void aFloatArrivesAsTheDoubleItsTypeSaysItIs() {
        Envelope env = insert(row("rate", 1.5f));

        assertThat(env.after().get("rate"))
                .as("a binary floating point column is one width in the namespace, as integers are")
                .isEqualTo(1.5d)
                .isInstanceOf(Double.class);
    }

    @Test
    void aBigIntegerInsideTheRangeArrivesAsTheSixtyFourBitInteger() {
        Envelope env = insert(row("big", BigInteger.valueOf(Long.MAX_VALUE)));

        assertThat(env.after().get("big")).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void aBigIntegerOutsideTheRangeIsLeftExactlyAsItCame() {
        BigInteger wider = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);

        Envelope env = insert(row("big", wider));

        // Narrowing it would hand every downstream reader a different number and report it as a
        // success. It stays what it is and is refused by name where it has to be.
        assertThat(env.after().get("big")).isEqualTo(wider);
    }

    @Test
    void aDecimalIsCarriedThroughUntouchedDownToItsScale() {
        BigDecimal amount = new BigDecimal("12345.6789");

        Envelope env = insert(row("amount", amount));

        // The one conversion that must never happen. Routing an exact fixed-point number through any
        // binary floating point type loses digits silently - green gate, green run, wrong money. The
        // assertion is equals rather than compareTo on purpose: compareTo ignores scale and would let
        // a rescaled value pass.
        assertThat(env.after().get("amount")).isEqualTo(amount).isInstanceOf(BigDecimal.class);
    }

    @Test
    void binaryStaysTheBytesItArrivedAs() {
        byte[] bytes = {1, 2, 3};

        Envelope env = insert(row("blob", bytes));

        // Wrapping bytes is an expression-language representation concern, not a value-model one: a
        // sink is owed the row's bytes.
        assertThat(env.after().get("blob")).isSameAs(bytes);
    }

    @Test
    void aValueTheNamespaceNamesNoWiderFormForIsLeftAlone() {
        Envelope env = insert(row("name", "eu", "flag", true));

        assertThat(env.after().get("name")).isEqualTo("eu");
        assertThat(env.after().get("flag")).isEqualTo(true);
    }

    @Test
    void aNestedDocumentAndAnArrayAreConvertedThroughToTheirLeaves() {
        Envelope env = insert(row(
                "doc", Map.of("qty", 5),
                "tags", List.of(1, 2)));

        // A document's own fields and an array's elements are as reachable from a reader as a
        // top-level column, so they hold the same currency.
        assertThat(env.after().get("doc")).isEqualTo(Map.of("qty", 5L));
        assertThat(env.after().get("tags")).isEqualTo(List.of(1L, 2L));
    }

    @Test
    void aContainerWithNothingToConvertIsNotCopied() {
        List<String> tags = List.of("eu", "us");

        Envelope env = insert(row("tags", tags));

        assertThat(env.after().get("tags"))
                .as("the ordinary row must not pay a copy per nested container")
                .isSameAs(tags);
    }

    @Test
    void theBeforeRowOfAnUpdateIsConvertedToo() {
        TapUpdateRecordEvent event = TapUpdateRecordEvent.create()
                .table("orders").referenceTime(1000L)
                .before(row("qty", 5)).after(row("qty", 6));

        Envelope env = TapEventCodec.decodeChange(event);

        assertThat(env.before().get("qty")).isEqualTo(5L);
        assertThat(env.after().get("qty")).isEqualTo(6L);
    }

    @Test
    void aSnapshotRowIsConvertedTheSameWayAChangeIs() {
        TapInsertRecordEvent event = TapInsertRecordEvent.create()
                .table("orders").referenceTime(1000L).after(row("qty", 5));

        Envelope env = TapEventCodec.decodeSnapshotRow(event);

        // The phase says which op a row carries, never what its values are.
        assertThat(env.after().get("qty")).isEqualTo(5L);
    }

    private static Envelope insert(Map<String, Object> after) {
        return TapEventCodec.decodeChange(
                TapInsertRecordEvent.create().table("orders").referenceTime(1000L).after(after));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }
}
