package io.tapstate.runtime.engine;

import com.hazelcast.jet.core.Watermark;
import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.runtime.engine.SinkFrontier.ChainEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far a sink may say it has landed when what reaches it was assembled from several chains at once.
 * Nothing that arrives here says by itself how far a chain has travelled — a document is drawn from
 * changes of several chains, and the events of one chain reach the sink out of their own order — so the
 * frontier is the meeting of two separate things: the entries the sink has proven durable, and the bound
 * the engine combined across every input queue for that chain. The highest entry at or below that bound
 * is what may be acked, and nothing above it.
 */
class SettledFloorTest {

    private static final ChainAxes AXES = ChainAxes.assign(List.of("orders", "lines"));

    @Test
    void advances_to_the_highest_settled_entry_at_or_below_the_bound() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 1, 2, 3), acked);
        floor.bound(boundAt("orders", 2), acked);

        // 3 is settled but the bound only reaches 2: everything at or below 2 is proven to have arrived,
        // while a change of another instance at 3 may still be in flight.
        assertThat(acked.calls).containsExactly("orders=w2");
    }

    @Test
    void says_nothing_until_a_bound_arrives() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 1, 2, 3), acked);

        // Settled is not the same as covered: what is durable here says nothing about what is still on its
        // way to here. Acking the highest settled entry is exactly the frontier lying.
        assertThat(acked.calls).isEmpty();
    }

    @Test
    void advances_nothing_when_the_bound_is_below_every_settled_entry() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 5, 6), acked);
        floor.bound(boundAt("orders", 4), acked);

        assertThat(acked.calls).isEmpty();
    }

    @Test
    void a_bound_that_climbs_advances_the_frontier_without_another_batch() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 1, 2, 3), acked);
        floor.bound(boundAt("orders", 1), acked);
        floor.bound(boundAt("orders", 3), acked);

        // The last batch of a run settles before the bound that covers it arrives. Waiting for a further
        // batch to notice would leave the durable position short of what is provably durable, for as long
        // as the source stays quiet.
        assertThat(acked.calls).containsExactly("orders=w1", "orders=w3");
    }

    @Test
    void tracks_each_chain_against_its_own_bound() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 1, 2), acked);
        floor.settled(entries("lines", 7, 8), acked);
        floor.bound(boundAt("orders", 2), acked);
        floor.bound(boundAt("lines", 7), acked);

        // Two chains are two independent promises: one running ahead never carries the other with it.
        assertThat(acked.calls).containsExactly("orders=w2", "lines=w7");
    }

    @Test
    void never_acks_the_same_entry_twice() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 1, 2), acked);
        floor.bound(boundAt("orders", 2), acked);
        floor.bound(boundAt("orders", 2), acked);
        floor.settled(entries("orders", 3), acked);

        // A repeated bound and a batch that adds nothing beneath it are both silence, not a second write
        // of the same position.
        assertThat(acked.calls).containsExactly("orders=w2");
    }

    @Test
    void never_lets_a_later_low_entry_pull_the_frontier_back() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(entries("orders", 5), acked);
        floor.bound(boundAt("orders", 9), acked);
        floor.settled(entries("orders", 2), acked);

        // A batch settling beneath what was already acked can only mean batches settled out of order,
        // which the single write in flight rules out. It is never rewritten backwards: a durable position
        // that moves down is one a restart replays from, past changes that were already acked away.
        assertThat(acked.calls).containsExactly("orders=w5");
    }

    @Test
    void carries_a_snapshot_row_through_with_no_token_of_its_own() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        Envelope snapshotRow = Envelope.read(1L, "orders", Map.of("id", 1), null)
                .withOrder(SourceOrder.snapshotRow(1));
        floor.settled(floor.positions(List.of(snapshotRow)), acked);
        floor.bound(new Watermark(FrontierOrders.pack("orders", SourceOrder.snapshotRow(1)),
                AXES.axisOf("orders")), acked);

        // A snapshot row is ordered but is not a spot in a change stream, so it carries no token. Taking
        // part is decided on the order; what gets persisted for it is the chain's cdc start, which is the
        // ack binding's to resolve. Skipping it because the token is absent stalls a snapshot-only run's
        // frontier at nothing at all.
        assertThat(acked.calls).containsExactly("orders=<no token>");
    }

    @Test
    void an_event_with_no_order_takes_no_part() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        Envelope synthetic = Envelope.insert(1L, "orders", Map.of("id", 1), null);
        assertThat(floor.positions(List.of(synthetic))).isEmpty();
    }

    @Test
    void takes_every_position_of_a_batch_rather_than_its_highest() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, SettledFloor.DEFAULT_MAX_ENTRIES_PER_CHAIN);

        floor.settled(floor.positions(List.of(event("orders", 1), event("orders", 2), event("orders", 3))),
                acked);
        floor.bound(boundAt("orders", 2), acked);

        // Reducing a batch to its highest position would leave the frontier stuck behind a bound that
        // falls inside a batch: with only 3 kept, a bound at 2 finds nothing to advance to.
        assertThat(acked.calls).containsExactly("orders=w2");
    }

    @Test
    void keeps_what_it_holds_for_a_chain_within_its_capacity_line() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, 8);

        for (int seq = 1; seq <= 100; seq++) {
            floor.settled(entries("orders", seq), acked);
        }

        // Entries are only dropped when the frontier passes them, so a bound that stalls - one dangling
        // reference is enough - grows this at the event rate inside the sink's own process, where the
        // guards over the assembled state cannot see it.
        assertThat(floor.held("orders")).isLessThanOrEqualTo(8);
    }

    @Test
    void thinning_keeps_the_highest_it_holds_however_many_entries_it_thinned() {
        // Every length, because where the last entry falls against the thinning decides whether keeping the
        // highest is really being exercised: one length alone passes on the arithmetic rather than the rule.
        for (int highest = 9; highest <= 40; highest++) {
            RecordingAck acked = new RecordingAck();
            SettledFloor floor = new SettledFloor(AXES, 8);

            for (int seq = 1; seq <= highest; seq++) {
                floor.settled(entries("orders", seq), acked);
            }
            floor.bound(boundAt("orders", highest), acked);

            // Thinning loses precision, never correctness: a dropped entry only makes the floor lower, so
            // the frontier is more conservative. Dropping the highest costs an advance that is provably
            // safe, and a frontier that never reaches what is already durable is what burns a retention
            // window while every indicator reads healthy.
            assertThat(acked.calls).as("highest settled position %d", highest)
                    .containsExactly("orders=w" + highest);
        }
    }

    @Test
    void a_thinned_chain_still_advances_to_an_entry_at_or_below_the_bound() {
        RecordingAck acked = new RecordingAck();
        SettledFloor floor = new SettledFloor(AXES, 8);

        for (int seq = 1; seq <= 100; seq++) {
            floor.settled(entries("orders", seq), acked);
        }
        floor.bound(boundAt("orders", 50), acked);

        assertThat(acked.calls).hasSize(1);
        int reached = Integer.parseInt(acked.calls.get(0).replaceAll("\\D+", ""));
        assertThat(reached).isLessThanOrEqualTo(50);
    }

    /** The entries a batch of changes on {@code chain} contributes, one per ring sequence. */
    private static List<ChainEntry> entries(String chain, int... seqs) {
        List<ChainEntry> entries = new ArrayList<>();
        for (int seq : seqs) {
            entries.add(new ChainEntry(chain, new ChainPosition(new SourceOrder(1, seq), "w" + seq)));
        }
        return entries;
    }

    /** One change on {@code chain} at ring sequence {@code seq}, as the source stamps it. */
    private static Envelope event(String chain, int seq) {
        return Envelope.insert(1L, chain, Map.of("id", seq), null)
                .withSrcPos("w" + seq)
                .withOrder(new SourceOrder(1, seq));
    }

    /** The bound the engine combined across every input queue for {@code chain}, at ring sequence {@code seq}. */
    private static Watermark boundAt(String chain, int seq) {
        return new Watermark(FrontierOrders.pack(chain, new SourceOrder(1, seq)), AXES.axisOf(chain));
    }

    /** Records what was acked, as {@code chain=token}. */
    private static final class RecordingAck implements SinkAck {

        private final List<String> calls = new ArrayList<>();

        @Override
        public void advance(String chain, ChainPosition position) {
            calls.add(chain + "=" + (position.token() == null ? "<no token>" : position.token()));
        }
    }
}
