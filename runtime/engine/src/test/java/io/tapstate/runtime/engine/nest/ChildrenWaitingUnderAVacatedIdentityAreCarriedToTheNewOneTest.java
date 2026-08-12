package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tracking;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The third structural family, in the one part of it that cannot rebuild itself: a row changes the column
 * its children point at.
 *
 * <p>Most of that family needs nothing done. The row arrives keyed by the identity it now has and declares
 * its mapping there, so the mapping is not carried anywhere - it is simply written where it now belongs.
 * What cannot follow are the children that arrived <em>before</em> their parent and are waiting under the
 * old value: nothing answers to that value any more, so they wait for an answer that can never come, and a
 * change waiting is a change consumed and shown to nobody - it holds the durable frontier below it for as
 * long as the job runs.
 *
 * <p>They are carried by the same two-edge arrangement a move between documents uses. The copy of the row
 * keyed by what it is leaving reads the entry being vacated - its own, which is the point - and leaves what
 * was waiting where the identity the row now has will look for it.
 *
 * <p>The pair of cases is what gives this meaning. Carrying them is only right because they would otherwise
 * be stranded; a child waiting under a value the row still answers to must be left exactly where it is,
 * which is what the second case pins.
 */
class ChildrenWaitingUnderAVacatedIdentityAreCarriedToTheNewOneTest {

    private static final TransformBody.Nest TRACKED = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TRACKED, tables());
    private static final NestVertex POLICIES = TOPOLOGY.vertexAt(List.of("policies"));

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<ResolverState> mappings = new HeapNestStore<>();
    private final TestOutbox outbox = new TestOutbox(256);

    @Test
    void aClaimWaitingUnderTheOldIdentityIsAnsweredUnderTheNewOne() throws Exception {
        ResolverProcessor policies = resolver();
        // The claim arrives first and waits: the policy it names has not been seen.
        feed(policies, CLAIMS, claim(1, "K1", "P1"));
        assertThat(drain()).describedAs("nothing can travel while the parent is unknown").isEmpty();

        // The policy arrives having renamed the column claims point at, and declares itself under the new
        // value. Both edges carry it, as the graph draws them.
        feed(policies, twinOf(POLICIES.pathId()), policyRenamed(2, "P1", "P2", "C1"));
        feed(policies, OWN_ROWS, policyRenamed(2, "P1", "P2", "C1"));

        List<Object> out = drain();
        assertThat(out)
                .describedAs("the policy itself, and the claim that had been waiting under the old value")
                .hasSize(2);
    }

    /**
     * Which of the two copies runs first is not something either can decide, and the one that takes the
     * identity over is the only thing that would ever look for what it is owed. Fed here in the order that
     * breaks it - the identity taken over first, vacated after - and then given a turn with nothing coming
     * in, which is one of the three places the second look happens.
     */
    @Test
    void whatWasVacatedAfterTheNewIdentityLookedIsStillCollected() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, CLAIMS, claim(1, "K1", "P1"));
        drain();

        feed(policies, OWN_ROWS, policyRenamed(2, "P1", "P2", "C1"));
        assertThat(drain())
                .describedAs("only the policy itself: nothing had been vacated when it looked")
                .hasSize(1);

        feed(policies, twinOf(POLICIES.pathId()), policyRenamed(2, "P1", "P2", "C1"));
        policies.tryProcess();

        assertThat(drain())
                .describedAs("the second look is what gets the waiting claim answered at all")
                .hasSize(1);
    }

    /**
     * The control. A row whose identity column did not change strands nothing, so a child waiting under it
     * must be left where it is - carrying it would take a change out of the one place something is going to
     * answer it.
     */
    @Test
    void aClaimWaitingUnderAnIdentityThatDidNotChangeIsLeftWhereItIs() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, CLAIMS, claim(1, "K1", "P1"));
        drain();

        feed(policies, twinOf(POLICIES.pathId()), policyEdited(2, "P1", "C1"));

        assertThat(drain())
                .describedAs("this copy has nothing to vacate, so it moves nothing and sends nothing")
                .isEmpty();
    }

    private ResolverProcessor resolver() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, mappings, (from, released) -> { },
                null, null, ReplayFloor.NONE, NestClock.SYSTEM, NestSettings.defaults(),
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

    /** The policy row with the column its claims point at renamed from {@code was} to {@code is}. */
    private static Envelope policyRenamed(long seq, String was, String is, String customerId) {
        return Envelope.update(seq, "policy",
                row("policy_id", was, "customer_id", customerId, "policy_no", "PN-1"),
                row("policy_id", is, "customer_id", customerId, "policy_no", "PN-1"), null)
                .withOrder(at(seq));
    }

    /** The same row with an unrelated column edited, so nothing its children point at has moved. */
    private static Envelope policyEdited(long seq, String policyId, String customerId) {
        return Envelope.update(seq, "policy",
                row("policy_id", policyId, "customer_id", customerId, "policy_no", "PN-1"),
                row("policy_id", policyId, "customer_id", customerId, "policy_no", "PN-2"), null)
                .withOrder(at(seq));
    }
}
