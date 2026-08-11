package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.spi.store.NestDeadLetterRecord;
import io.tapstate.spi.store.NestDeadLetterStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A change a nest can never place in a document leaves the pipeline. Counting it says that happened; only
 * keeping it says what happened, and nothing about the documents can stand in for either - a nest discarding
 * a million rows and a nest discarding none produce exactly the same output.
 */
class WhatCannotBeAssembledIsKeptNotJustCountedTest {

    private static final NestVertex ITEMS = new NestVertex(List.of("items"), "items", "nest.p.items",
            List.of("order_id"), List.of("customer_id"),
            List.of(new NestInbound(0, "item", List.of("items"), List.of("order_id"), List.of("id"))));

    private final RecordingStore store = new RecordingStore();

    @Test
    void keepsTheRowAndWhatSaysWhereItCameFrom() {
        NestDeadLetter channel = boundChannel(() -> 9_000L);

        channel.unassemblable(ITEMS, released(7, Duration.ofMinutes(3)));

        NestDeadLetterRecord kept = store.only();
        assertThat(kept.namespace()).isEqualTo("nest.p.items");
        assertThat(kept.chain()).isEqualTo("item");
        assertThat(kept.order()).isEqualTo("1:7");
        assertThat(kept.heldForMillis()).isEqualTo(Duration.ofMinutes(3).toMillis());
        assertThat(kept.discardedAt()).isEqualTo(9_000L);
        assertThat(kept.row()).containsEntry("id", 7);
        assertThat(kept.deletion()).isFalse();
    }

    /**
     * A change that removes an element carries no row, and that has to survive the trip: a deletion filed as
     * a row with no fields reads back as "this row was dropped and here it is, empty", which is a different
     * and wrong thing to tell whoever is looking into what was discarded.
     */
    @Test
    void keepsADeletionAsADeletion() {
        NestDeadLetter channel = boundChannel(() -> 1L);

        channel.unassemblable(ITEMS, new ReleasedChild(
                new NestElement(new ElementRef(List.of("items"), 1, List.of(7), 7), null,
                        new SourceOrder(1L, 7), Map.of("item", position(7))),
                Duration.ZERO));

        assertThat(store.only().deletion()).isTrue();
        assertThat(store.only().row()).isNull();
    }

    /**
     * The discriminating case for filing per element: a replay hands the same discarded change over again,
     * and the channel has to say what it said before rather than growing by an hour of replay every time an
     * hour is replayed.
     */
    @Test
    void theSameChangeHandedOverTwiceLeavesOneRecord() {
        NestDeadLetter channel = boundChannel(() -> 5L);

        channel.unassemblable(ITEMS, released(7, Duration.ofMinutes(3)));
        channel.unassemblable(ITEMS, released(7, Duration.ofMinutes(3)));

        assertThat(store.recorded).hasSize(1);
    }

    /**
     * The other half of that: two elements that are not the same must not be filed over each other. Same key
     * value, different embeds - which is the ordinary shape, since a vertex hands over changes from the
     * embeds beneath it as well as its own and nothing makes their keys disjoint.
     */
    @Test
    void twoEmbedsSharingAKeyValueAreFiledApart() {
        NestDeadLetter channel = boundChannel(() -> 5L);

        channel.unassemblable(ITEMS, releasedIn(List.of("items"), 7));
        channel.unassemblable(ITEMS, releasedIn(List.of("items", "tags"), 7));

        assertThat(store.recorded).hasSize(2);
        assertThat(store.recorded.stream().map(NestDeadLetterRecord::element).distinct()).hasSize(2);
    }

    /**
     * Every one, not only the ones anything logged: a log line about these is sampled so that a parent
     * deleted with many children below it does not bury the rest of the log, and the count is what still
     * says how many. It is the count that reaches the face a pipeline's other numbers are read from.
     */
    @Test
    void countsEveryChangeHandedToItAndLeavesTheTotalWhereTheRunIsRead() {
        CountingGauge gauge = new CountingGauge();
        NestDeadLetter channel = new DurableNestDeadLetter(() -> 5L).bindTo(store, gauge);

        for (int id = 0; id < 25; id++) {
            channel.unassemblable(ITEMS, released(id, Duration.ZERO));
        }

        assertThat(store.recorded).hasSize(25);
        assertThat(gauge.last).containsEntry("nest.p.items", 25L);
    }

    /**
     * The count is a running total, not a delta. It is read by scraping, so a gauge told "one more" would
     * report one for a namespace that had discarded thousands - and would read as healthy while doing it.
     */
    @Test
    void theCountItLeavesIsTheTotalSoFarRatherThanTheLatestOne() {
        CountingGauge gauge = new CountingGauge();
        NestDeadLetter channel = new DurableNestDeadLetter(() -> 5L).bindTo(store, gauge);

        channel.unassemblable(ITEMS, released(1, Duration.ZERO));
        channel.unassemblable(ITEMS, released(2, Duration.ZERO));
        channel.unassemblable(ITEMS, released(3, Duration.ZERO));

        assertThat(gauge.seen).containsExactly(1L, 2L, 3L);
    }

    /**
     * The channel is carried onto the DAG, so anything here that cannot be written down fails at submit time
     * on a pipeline that had passed everything else - which is why the unbound form holds no store.
     */
    @Test
    void travelsToTheMemberBeforeItHasAnywhereToPutAnything() throws Exception {
        DurableNestDeadLetter unbound = new DurableNestDeadLetter();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(unbound);
        }
        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            read = in.readObject();
        }

        assertThat(read).isInstanceOf(DurableNestDeadLetter.class);
    }

    /** And the bound form must not: a live store in a graph being submitted is a mistake worth failing on. */
    @Test
    void theBoundChannelRefusesToBeWrittenIntoAGraph() {
        NestDeadLetter bound = boundChannel(() -> 5L);

        assertThatThrownBy(() -> {
            try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
                out.writeObject(bound);
            }
        }).isInstanceOf(NotSerializableException.class);
    }

    /**
     * Handing a change to a channel that was never bound is a wiring mistake, and the one thing it must not
     * do is what an unwired channel would do by default - accept the change and lose it, which is the exact
     * failure this whole path exists to stop.
     */
    @Test
    void refusesAChangeWhenNothingIsBehindIt() {
        assertThatThrownBy(() -> new DurableNestDeadLetter().unassemblable(ITEMS, released(7, Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * But a member that was told of no channel is not refused a job. The discriminating pair is this and the
     * case above: a pipeline whose data is whole never hands anything over, and stopping it for want of a
     * channel it has no use for would fail the wrong jobs - every healthy one - while still not saving a
     * single row from the one job that is actually about to lose some.
     */
    @Test
    void aMemberToldOfNoChannelIsNotRefusedUntilARowActuallyNeedsKeeping() {
        NestDeadLetter bound = new DurableNestDeadLetter().boundTo(new HashMap<>());

        assertThat(bound).isNotNull();
        assertThatThrownBy(() -> bound.unassemblable(ITEMS, released(7, Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * And a member that was told of one gets a different channel - the bound one, which keeps what it is
     * handed. Asserted as "not the unbound form" rather than by handing it a change, because the channel
     * this produces leaves its count with the run's statistics and there is no run here to leave one with.
     * What it does with a change once bound is what every case above covers.
     */
    @Test
    void aMemberToldOfAChannelGetsTheBoundOneRatherThanThisOne() {
        Map<String, Object> userContext = new HashMap<>();
        userContext.put(DurableNestDeadLetter.USER_CONTEXT_KEY, store);
        DurableNestDeadLetter unbound = new DurableNestDeadLetter(() -> 5L);

        assertThat(unbound.boundTo(userContext)).isNotSameAs(unbound);
        assertThat(unbound.boundTo(new HashMap<>())).isSameAs(unbound);
    }

    private NestDeadLetter boundChannel(NestClock clock) {
        return new DurableNestDeadLetter(clock).bindTo(store);
    }

    /** A gauge that keeps what it was told, so the total the run would report can be asserted on. */
    private static final class CountingGauge implements NestDeadLetterGauge {

        private final Map<String, Long> last = new LinkedHashMap<>();
        private final List<Long> seen = new ArrayList<>();

        @Override
        public void handedOver(String namespace, long handedOver) {
            last.put(namespace, handedOver);
            seen.add(handedOver);
        }
    }

    private static ReleasedChild released(int id, Duration heldFor) {
        return new ReleasedChild(
                new NestElement(new ElementRef(List.of("items"), 1, List.of(id), id), Map.of("id", id),
                        new SourceOrder(1L, id), Map.of("item", position(id))),
                heldFor);
    }

    private static ReleasedChild releasedIn(List<String> pathId, int id) {
        return new ReleasedChild(
                new NestElement(new ElementRef(pathId, 1, List.of(id), id), Map.of("id", id),
                        new SourceOrder(1L, id), Map.of("item", position(id))),
                Duration.ZERO);
    }

    private static ChainPosition position(int id) {
        return new ChainPosition(new SourceOrder(1L, id), "t" + id);
    }

    /** A store that keeps what it is given, filed the way a real one files it. */
    private static final class RecordingStore implements NestDeadLetterStore {

        private final Map<String, NestDeadLetterRecord> byElement = new LinkedHashMap<>();
        private final List<NestDeadLetterRecord> recorded = new ArrayList<>();

        @Override
        public void record(NestDeadLetterRecord record) {
            byElement.put(record.namespace() + "/" + record.element(), record);
            recorded.clear();
            recorded.addAll(byElement.values());
        }

        @Override
        public List<NestDeadLetterRecord> read(String namespace, int limit) {
            return byElement.values().stream()
                    .filter(held -> held.namespace().equals(namespace))
                    .sorted(Comparator.comparingLong(NestDeadLetterRecord::discardedAt).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public void dropNamespace(String namespace) {
            byElement.values().removeIf(held -> held.namespace().equals(namespace));
        }

        private NestDeadLetterRecord only() {
            assertThat(recorded).hasSize(1);
            return recorded.get(0);
        }
    }
}
