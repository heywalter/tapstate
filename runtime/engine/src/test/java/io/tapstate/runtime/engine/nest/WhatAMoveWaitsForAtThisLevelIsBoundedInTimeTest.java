package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tracking;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ChainAxes;
import io.tapstate.runtime.engine.FrontierOrders;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Both halves of a move at a resolver, bounded in time - the mirror of what the assembler already does for
 * the moves it holds.
 *
 * <p>The halves are routed by different keys and may be worked by different members, so neither can decide
 * which runs first. That is why the arriving half keeps looking and the leaving half keeps holding: either
 * one may be waiting on the other. What neither can tell on its own is a half that has not been worked
 * <em>yet</em> from one that is never coming, and only time can - so each is given a protection and then
 * given up on.
 *
 * <p><b>The two have to be bounded together.</b> Bound the arriving half alone and a subtree parked after it
 * stopped looking is stranded with nobody to take it in, holding the frontier for the life of the job; bound
 * the leaving half alone and the arriving one still pays a durable read every turn, forever, for an identity
 * nobody is handing anything over for. Each bound is what makes the other safe.
 */
class WhatAMoveWaitsForAtThisLevelIsBoundedInTimeTest {

    private static final String POLICY = "policy";
    private static final String CLAIM = "claim";
    private static final ChainAxes AXES = ChainAxes.assign(List.of(POLICY, CLAIM));

    private static final TransformBody.Nest TRACKED = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TRACKED, tables());
    private static final NestVertex POLICIES = TOPOLOGY.vertexAt(List.of("policies"));

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;
    private static final int TWIN = twinOf(POLICIES.pathId());

    private static final Map<Integer, List<String>> CHAINS =
            Map.of(OWN_ROWS, List.of(POLICY), CLAIMS, List.of(CLAIM), TWIN, List.of(POLICY));

    private static final long RENAMED_AT = 200L;
    private static final long PROTECTION = 1_000L;
    private static final long FAR_ABOVE = FrontierOrders.pack(POLICY, new SourceOrder(1L, 900L));

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final TestOutbox outbox = new TestOutbox(256);
    private final List<ReleasedChild> givenUp = new ArrayList<>();
    private final Ticking clock = new Ticking();

    @Test
    void anIdentityNobodyHandsAnythingOverForIsGivenUpOnRatherThanLookedForForever() throws Exception {
        Counting mappings = new Counting();
        ResolverProcessor takingOver = resolver(mappings);
        feed(takingOver, OWN_ROWS, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));

        clock.advance(PROTECTION);
        takingOver.tryProcess();
        mappings.forgetLoads();
        takingOver.tryProcess();

        assertThat(mappings.loads())
                .describedAs("nothing was ever parked for it, which is the ordinary case: a tracked key "
                        + "change on a row with no children waiting under the old value. Left recorded, it "
                        + "costs a durable read every turn for the life of the job")
                .isZero();
    }

    /**
     * The discrimination that stops the bound above from being satisfied by giving up at once. Inside its
     * protection the identity is still looked for, because a hand-over not collected yet says nothing about
     * whether it is about to be.
     */
    @Test
    void anIdentityInsideItsProtectionIsStillLookedFor() throws Exception {
        Counting mappings = new Counting();
        ResolverProcessor takingOver = resolver(mappings);
        feed(takingOver, OWN_ROWS, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));

        clock.advance(PROTECTION - 1);
        takingOver.tryProcess();
        mappings.forgetLoads();
        takingOver.tryProcess();

        assertThat(mappings.loads())
                .describedAs("the half that would hand something over may simply not have been worked yet")
                .isPositive();
    }

    @Test
    void aVacatedSubtreeNobodyCollectsIsGivenUpOnRatherThanHeldForever() throws Exception {
        ResolverProcessor vacating = resolver(new Counting());
        feed(vacating, CLAIMS, claim(1, "K1", "P1"));
        feed(vacating, TWIN, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));

        clock.advance(PROTECTION);
        vacating.tryProcess();

        assertThat(givenUp)
                .describedAs("those rows were read out of the entry they waited in and nothing will send "
                        + "them again, so they are handed on rather than dropped")
                .hasSize(1);
        assertThat(stores.forParking(POLICIES).count())
                .describedAs("and the parking area does not go on holding what has been given up on")
                .isZero();
    }

    /**
     * The half that costs more than the rows. While a subtree sits parked the frontier is held below the
     * change that put it there, so one nobody collects pins the chain for the life of the job - and a bound
     * that let go of the rows but not the hold would have bounded nothing.
     *
     * <p>Said on this level's own account, with no further bound arriving: what a level may promise depends
     * on what it holds as well as on what its edges said, and only the second turns up as a message.
     */
    @Test
    void givingUpOnAVacatedSubtreeAlsoLetsGoOfWhatItWasHoldingBack() throws Exception {
        ResolverProcessor vacating = resolver(new Counting());
        feed(vacating, CLAIMS, claim(1, "K1", "P1"));
        feed(vacating, TWIN, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));
        assertThat(boundsAfterBothEdgesPromise(vacating, FAR_ABOVE))
                .describedAs("held below the change that parked them to begin with")
                .containsExactly(FrontierOrders.pack(POLICY, at(RENAMED_AT)) - 1);

        clock.advance(PROTECTION);
        vacating.tryProcess();

        assertThat(boundsIn(drain()))
                .describedAs("an entry given up on is no longer something a replay would finish, so keeping "
                        + "the frontier beneath it would wait for what has already been decided against")
                .containsExactly(FAR_ABOVE);
    }

    // ---- harness ------------------------------------------------------------------------

    private ResolverProcessor resolver(NestStore<ResolverState> holding) throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, holding,
                (from, released) -> givenUp.add(released), AXES, CHAINS, ReplayFloor.NONE, clock,
                NestSettings.defaults().withMigrationProtection(POLICIES.mapName(), PROTECTION),
                stores.forParking(POLICIES));
        processor.init(outbox, new TestProcessorContext());
        return processor;
    }

    private static int twinOf(List<String> pathId) {
        for (NestInbound edge : POLICIES.inbound()) {
            if (edge.carriesDepartures() && edge.pathId().equals(pathId)) {
                return edge.ordinal();
            }
        }
        throw new AssertionError("no departure edge carries " + pathId);
    }

    private void feed(ResolverProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        drain();
    }

    private List<Long> boundsAfterBothEdgesPromise(ResolverProcessor processor, long arriving) {
        Watermark bound = new Watermark(arriving, AXES.axisOf(POLICY));
        processor.tryProcessWatermark(OWN_ROWS, bound);
        processor.tryProcessWatermark(TWIN, bound);
        return boundsIn(drain());
    }

    private static List<Long> boundsIn(List<Object> drained) {
        List<Long> bounds = new ArrayList<>();
        for (Object item : drained) {
            if (item instanceof Watermark bound) {
                bounds.add(bound.timestamp());
            }
        }
        return bounds;
    }

    private List<Object> drain() {
        List<Object> out = new ArrayList<>();
        outbox.drainQueueAndReset(0, out, false);
        return out;
    }

    private static Envelope claim(long seq, String claimId, String policyId) {
        return Envelope.insert(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }

    private static Envelope policyRenamed(long seq, String was, String is, String customerId) {
        return Envelope.update(seq, "policy",
                row("policy_id", was, "customer_id", customerId, "policy_no", "PN-1"),
                row("policy_id", is, "customer_id", customerId, "policy_no", "PN-1"), null)
                .withOrder(at(seq));
    }

    /** A store that says how many times it was read, which is the cost a look nobody needs actually has. */
    private static final class Counting implements NestStore<ResolverState> {

        private final HeapNestStore<ResolverState> entries = new HeapNestStore<>();
        private long loads;

        long loads() {
            return loads;
        }

        void forgetLoads() {
            loads = 0;
        }

        @Override
        public ResolverState load(Object key) {
            loads++;
            return entries.load(key);
        }

        @Override
        public void save(Object key, ResolverState state) {
            entries.save(key, state);
        }

        @Override
        public void remove(Object key) {
            entries.remove(key);
        }

        @Override
        public long count() {
            return entries.count();
        }
    }

    /** A clock that only moves when the test moves it, so a protection is measured rather than waited for. */
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
