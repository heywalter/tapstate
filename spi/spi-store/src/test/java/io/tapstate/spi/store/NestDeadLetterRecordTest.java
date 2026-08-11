package io.tapstate.spi.store;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NestDeadLetterRecordTest {

    private static final Map<String, Object> ROW = Map.of("id", 7, "order_id", 41);

    @Test
    void holdsWhatSaysWhichRowWasDroppedAndWhy() {
        NestDeadLetterRecord record = new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", "0000000042", 1_500L, 9_000L, ROW);
        assertThat(record.namespace()).isEqualTo("nest.orders.doc.items");
        assertThat(record.element()).isEqualTo("items/7");
        assertThat(record.chain()).isEqualTo("mysql-a");
        assertThat(record.order()).isEqualTo("0000000042");
        assertThat(record.heldForMillis()).isEqualTo(1_500L);
        assertThat(record.discardedAt()).isEqualTo(9_000L);
        assertThat(record.row()).containsExactlyInAnyOrderEntriesOf(ROW);
    }

    @Test
    void keepsADeletionApartFromARowOfNoFields() {
        NestDeadLetterRecord deletion = new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", "0000000042", 0L, 0L, null);
        assertThat(deletion.deletion()).isTrue();
        assertThat(new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", "0000000042", 0L, 0L, Map.of()).deletion())
                .isFalse();
    }

    @Test
    void cannotBeAlteredThroughTheMapItWasBuiltFrom() {
        Map<String, Object> mutable = new HashMap<>(ROW);
        NestDeadLetterRecord record = new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", "0000000042", 0L, 0L, mutable);
        mutable.put("id", 999);
        assertThat(record.row()).containsEntry("id", 7);
        assertThatThrownBy(() -> record.row().put("id", 999))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsTheRowsFieldOrder() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("z", 1);
        ordered.put("a", 2);
        NestDeadLetterRecord record = new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", "0000000042", 0L, 0L, ordered);
        assertThat(record.row().keySet()).containsExactly("z", "a");
    }

    @Test
    void rejectsABlankNamespace() {
        assertThatThrownBy(() -> new NestDeadLetterRecord(" ", "items/7", "mysql-a", "42", 0L, 0L, ROW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankElement() {
        assertThatThrownBy(() -> new NestDeadLetterRecord(
                "nest.orders.doc.items", "", "mysql-a", "42", 0L, 0L, ROW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankOrder() {
        assertThatThrownBy(() -> new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", " ", 0L, 0L, ROW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsATimeHeldThatRanBackwards() {
        assertThatThrownBy(() -> new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "mysql-a", "42", -1L, 0L, ROW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A change can cover no chain at all - nothing carried a position for it - and that is a reading in its
     * own right rather than a reason to refuse the record. Refusing here would drop the one record that says
     * a change with no position was discarded, which is exactly the case worth seeing.
     */
    @Test
    void acceptsAChangeThatNamesNoChain() {
        assertThat(new NestDeadLetterRecord(
                "nest.orders.doc.items", "items/7", "", "42", 0L, 0L, ROW).chain())
                .isEmpty();
    }
}
