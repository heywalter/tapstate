package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.Op;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a vertex with nothing arriving is allowed to spend.
 *
 * <p>Both kinds keep something a deletion left behind - a root's record of being deleted, a key's
 * tombstoned mapping - and both weigh it against where a restart would resume. Weighing it reads the
 * durable plane: the state comes back from the layer behind the map, and the resume position is a round
 * trip of its own, per chain, answered by nobody's cache. That is affordable once in a while and not
 * affordable per turn, because a vertex with nothing arriving is asked to make progress over and over -
 * and what is being weighed stays weighable for exactly as long as the frontier has not passed it, which
 * is measured in hours.
 *
 * <p>So the reading is what these cases are about rather than the dropping. A candidate that cannot be
 * dropped yet is the case that matters: it stays a candidate, so an unheld sweep asks the same question
 * of the same durable plane as fast as the idle loop turns, with every count reading healthy and nothing
 * anywhere saying where the traffic came from.
 *
 * <p>Three readings rather than two, and the third is the one that keeps this honest: a sweep held by
 * never running at all would pass the first two.
 */
class TheIdleSweepsDoNotCrossToTheDurablePlaneEveryTurnTest {

    /** Idle turns taken with the clock held still - far more than any interval would let through. */
    private static final int IDLE_TURNS = 50;

    /** Longer than the sweep interval, so the pass after it is due. */
    private static final long PAST_THE_INTERVAL = 5_000L;

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int OWN_ROWS = 0;

    private final CountingFloor floor = new CountingFloor();
    private final Ticking clock = new Ticking();
    private final TestOutbox outbox = new TestOutbox(128);

    @Test
    void anAssemblerWeighsItsDeletedRootsOnAnIntervalRatherThanOnEveryTurn() throws Exception {
        AssemblerProcessor assembler = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                new HeapNestStore<>(), "doc", null, null, floor, NestSettings.defaults(), clock);
        assembler.init(outbox, new TestProcessorContext());
        // At the deletion rather than past it: a root that may not be dropped yet is the one that stays a
        // candidate, and so the one an unheld sweep would go on reading about for the life of the job.
        drain(assembler, OWN_ROWS, customer(Op.INSERT, 1, "c1"), customer(Op.DELETE, 2, "c1"));
        floor.at("customer", order(2));

        assembler.tryProcess();
        int afterTheFirstSweep = floor.reads;
        assertThat(afterTheFirstSweep)
                .describedAs("the first pass is due on its face - a sweep that never ran would pass the "
                        + "rest of this case by doing nothing at all")
                .isPositive();

        for (int turn = 0; turn < IDLE_TURNS; turn++) {
            assembler.tryProcess();
        }
        assertThat(floor.reads)
                .describedAs("%d further turns with the clock held still, and the durable plane is asked "
                        + "nothing more", IDLE_TURNS)
                .isEqualTo(afterTheFirstSweep);

        clock.advance(PAST_THE_INTERVAL);
        assembler.tryProcess();
        assertThat(floor.reads)
                .describedAs("held on an interval, not held for good: past it the record is weighed again")
                .isGreaterThan(afterTheFirstSweep);
    }

    @Test
    void aResolverWeighsItsTombstonesOnAnIntervalRatherThanOnEveryTurn() throws Exception {
        ResolverProcessor resolver = new ResolverProcessor(TOPOLOGY.vertexAt(List.of("policies")),
                new HeapNestStore<>(), (from, released) -> { }, null, null, floor, clock);
        resolver.init(outbox, new TestProcessorContext());
        drain(resolver, OWN_ROWS, policy(Op.INSERT, 1, "p1"), policy(Op.DELETE, 2, "p1"));
        floor.at("policy", order(2));

        resolver.tryProcess();
        int afterTheFirstSweep = floor.reads;
        assertThat(afterTheFirstSweep).describedAs("the first pass is due on its face").isPositive();

        for (int turn = 0; turn < IDLE_TURNS; turn++) {
            resolver.tryProcess();
        }
        assertThat(floor.reads)
                .describedAs("%d further turns with the clock held still, and the durable plane is asked "
                        + "nothing more", IDLE_TURNS)
                .isEqualTo(afterTheFirstSweep);

        clock.advance(PAST_THE_INTERVAL);
        resolver.tryProcess();
        assertThat(floor.reads)
                .describedAs("held on an interval, not held for good: past it the tombstone is weighed again")
                .isGreaterThan(afterTheFirstSweep);
    }

    // ---- harness ------------------------------------------------------------------------

    private static SourceOrder order(long seq) {
        return new SourceOrder(1L, seq);
    }

    private static Envelope customer(Op op, long seq, String customerId) {
        Map<String, Object> fields = row("customer_id", customerId, "name", "n" + seq);
        Envelope built = op == Op.DELETE
                ? Envelope.delete(seq, "customer", fields, null)
                : Envelope.insert(seq, "customer", fields, null);
        return built.withOrder(order(seq));
    }

    private static Envelope policy(Op op, long seq, String policyId) {
        Map<String, Object> fields =
                row("policy_id", policyId, "customer_id", "c1", "policy_no", "PN-" + policyId);
        Envelope built = op == Op.DELETE
                ? Envelope.delete(seq, "policy", fields, null)
                : Envelope.insert(seq, "policy", fields, null);
        return built.withOrder(order(seq));
    }

    private void drain(com.hazelcast.jet.core.Processor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
        outbox.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    /** A floor whose answers are set by the case, counting how often it was asked. */
    private static final class CountingFloor implements ReplayFloor {

        private static final long serialVersionUID = 1L;

        private final Map<String, SourceOrder> floors = new LinkedHashMap<>();
        private transient int reads;

        private void at(String chain, SourceOrder order) {
            floors.put(chain, order);
        }

        @Override
        public Optional<SourceOrder> of(String chain) {
            reads++;
            return Optional.ofNullable(floors.get(chain));
        }
    }

    /** A clock that only moves when the case moves it, so an interval is measured rather than waited for. */
    private static final class Ticking implements NestClock {

        private long now = 1_000_000L;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long millis() {
            return now;
        }
    }
}
