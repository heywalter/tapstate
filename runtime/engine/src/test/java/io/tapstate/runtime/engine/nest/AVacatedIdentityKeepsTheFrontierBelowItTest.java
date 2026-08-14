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
 * Children left behind by an identity change sit in the parking area until the identity that took over is
 * given them, and the durable frontier has to stay below the change that put them there for as long as they
 * are sitting.
 *
 * <p>A resolver otherwise lowers nothing, and that is deliberate: a child waiting for a parent is written
 * through with the state as the drain settles, so the frontier passing it costs nothing to recover - the
 * parent's own arrival brings it out again. Parking breaks the second half of that. The rows are still
 * somewhere durable, but <em>what would go looking for them</em> is not: the identity that took over is the
 * only thing that ever looks, and it remembers it is owed something only in memory. Let the frontier past
 * the change that started the move and a restart resumes above it, so nothing replays it, nothing looks
 * again, and those rows stay parked for the life of the pipeline with the job RUNNING and no error counted
 * anywhere.
 *
 * <p>The pair of cases is what gives the hold meaning. Holding forever would be trivially "safe" and would
 * pin the frontier on every identity change ever made, so the second case is the one that says it is
 * released - and <b>released with no further bound arriving</b>, because that is the shape that fails: what
 * a level may promise depends on what it holds as well as on what its edges said, and only the second of
 * those turns up as a message. An upstream that has said its last word sends nothing more to prompt a
 * recount, and a level that answered only when a bound arrived would leave the chain pinned where the hold
 * had it, for as long as the job runs.
 */
class AVacatedIdentityKeepsTheFrontierBelowItTest {

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

    /**
     * The twin edge carries the same stream its own-rows edge does, so both have to have promised before
     * anything is said about that chain - which is what the cases here feed.
     */
    private static final Map<Integer, List<String>> CHAINS =
            Map.of(OWN_ROWS, List.of(POLICY), CLAIMS, List.of(CLAIM), TWIN, List.of(POLICY));

    /** Where the identity change is read as having happened, and the position the hold has to stay below. */
    private static final long RENAMED_AT = 200L;

    /** Far above anything that arrives, so a level passing its edges' bound straight on is unmistakable. */
    private static final long FAR_ABOVE = FrontierOrders.pack(POLICY, new SourceOrder(1L, 900L));

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<ResolverState> mappings = new HeapNestStore<>();
    private final TestOutbox outbox = new TestOutbox(256);

    @Test
    void theBoundStaysBelowAnIdentityChangeWhoseChildrenAreStillParked() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, CLAIMS, claim(1, "K1", "P1"));

        feed(policies, TWIN, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));

        assertThat(boundsAfterBothEdgesPromise(policies, FAR_ABOVE))
                .describedAs("those children are in no state entry anything will look at while they sit "
                        + "parked; a frontier past the change that parked them means a restart resumes "
                        + "above it, nothing looks again, and they stay there with the job RUNNING")
                .containsExactly(FrontierOrders.pack(POLICY, at(RENAMED_AT)) - 1);
    }

    /**
     * Released by the children being taken in, and released without anything further arriving to prompt it.
     * The identity that took them over does so on its own copy of the same row, and no bound follows it -
     * which is exactly the situation an upstream that has finished speaking leaves behind.
     */
    @Test
    void theBoundClimbsOnceWhatWasParkedHasBeenTakenInWithNoFurtherBound() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, CLAIMS, claim(1, "K1", "P1"));
        feed(policies, TWIN, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));
        boundsAfterBothEdgesPromise(policies, FAR_ABOVE);

        feed(policies, OWN_ROWS, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));
        policies.tryProcess();

        assertThat(boundsIn(drain()))
                .describedAs("the hold is let go of because the children were taken in, and the level says "
                        + "so on its own rather than waiting for a bound that is never coming")
                .containsExactly(FAR_ABOVE);
    }

    /**
     * The one that matters in a running pipeline, and the one neither case above can tell apart from a local
     * arrangement. The two identities are on different partitions - which is the whole reason there is a
     * parking area at all - so the instance that gave the children up and the instance that takes them in are
     * different instances, on different threads and possibly on different members. Nothing the collecting one
     * does reaches into the bookkeeping of the one holding the frontier down.
     *
     * <p>Read the landing off anything local and this case is the one that fails: the hold is never let go of,
     * the chain stays pinned where the identity change put it, and every count reads healthy while it happens.
     */
    @Test
    void theHoldIsLetGoOfWhenAnotherInstanceTookTheChildrenIn() throws Exception {
        ResolverProcessor vacating = resolver(new HeapNestStore<>());
        ResolverProcessor takingOver = resolver(new HeapNestStore<>());
        feed(vacating, CLAIMS, claim(1, "K1", "P1"));
        feed(vacating, TWIN, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));
        assertThat(boundsAfterBothEdgesPromise(vacating, FAR_ABOVE))
                .containsExactly(FrontierOrders.pack(POLICY, at(RENAMED_AT)) - 1);

        feed(takingOver, OWN_ROWS, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));
        vacating.tryProcess();

        assertThat(boundsIn(drain()))
                .describedAs("what says the children landed is the parking area both instances reach, "
                        + "never anything the one that parked them keeps to itself")
                .containsExactly(FAR_ABOVE);
    }

    /**
     * The second look releases children, and a bound worked out in the same turn must not overtake them. It
     * is the ordinary rule about bounds and what is queued behind them, reached by a path that is easy to
     * miss: the children come out of the parking area rather than off an edge, so nothing about the turn looks
     * like it is carrying anything.
     *
     * <p>The bound here is on the stream those children came from, which is what makes overtaking them a real
     * loss rather than an ordering curiosity. A sink told that stream is covered to this point, while the
     * change itself is still sitting in this processor, acknowledges something it has not been given - and a
     * restart then resumes above it.
     */
    @Test
    void aBoundDoesNotOvertakeChildrenTheSecondLookHasJustReleased() throws Exception {
        ResolverProcessor vacating = resolver(new HeapNestStore<>());
        ResolverProcessor takingOver = resolver(new HeapNestStore<>());
        // The identity that takes over looks before anything has been left for it, so it is owed a look again.
        feed(takingOver, OWN_ROWS, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));
        feed(vacating, CLAIMS, claim(1, "K1", "P1"));
        feed(vacating, TWIN, policyRenamed(RENAMED_AT, "P1", "P2", "C1"));

        takingOver.tryProcessWatermark(CLAIMS, new Watermark(FAR_ABOVE, AXES.axisOf(CLAIM)));

        assertThat(drain()).satisfiesExactly(
                released -> assertThat(released)
                        .describedAs("the claim the second look released leaves first")
                        .isInstanceOf(KeyedElement.class),
                bound -> assertThat(bound)
                        .describedAs("and only then may a bound on its stream say it has gone")
                        .isInstanceOf(Watermark.class));
    }

    private ResolverProcessor resolver() throws Exception {
        return resolver(mappings);
    }

    private ResolverProcessor resolver(NestStore<ResolverState> holding) throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, holding, (from, released) -> { },
                AXES, CHAINS, ReplayFloor.NONE, NestClock.SYSTEM, NestSettings.defaults(),
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

    /** Both edges carrying the policy stream promise {@code arriving}, which is what lets the level answer. */
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

    /** The policy row with the column its claims point at renamed from {@code was} to {@code is}. */
    private static Envelope policyRenamed(long seq, String was, String is, String customerId) {
        return Envelope.update(seq, "policy",
                row("policy_id", was, "customer_id", customerId, "policy_no", "PN-1"),
                row("policy_id", is, "customer_id", customerId, "policy_no", "PN-1"), null)
                .withOrder(at(seq));
    }
}
