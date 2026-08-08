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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The vertex for policies, holding a claim whose policy never arrives. What decides whether it goes on
 * holding it is worked out here rather than in the rule: only the vertex knows how far the stream its
 * parents come from has been read, and whether that stream's clock can be compared with the waiting
 * change's at all.
 *
 * <p>Both streams here are read from one database, so their event times come off one clock. That is not
 * something the engine can see — a stream's position names the stream, not the source behind it — so it
 * is told, the same way it is told which table an alias means.
 */
class AVertexReleasesWhatItHoldsForAParentThatWillNotComeTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestVertex POLICIES =
            NestTopology.compile("p", "doc", TREE, tables()).vertexAt(List.of("policies"));

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;
    private static final Duration BACKSTOP = Duration.ofHours(6);
    private static final long NOON = 1_700_000_000_000L;

    private final HeapNestStore<ResolverState> store = new HeapNestStore<>();
    private final List<ReleasedChild> dead = new ArrayList<>();
    private long now = NOON;

    /** Both streams off one database clock, and the policies read told to have finished or not. */
    private static NestStreamFacts oneDatabase(boolean policiesLoaded) {
        return new NestStreamFacts() {
            @Override
            public boolean loaded(String stream) {
                return "policy".equals(stream) && policiesLoaded;
            }

            @Override
            public String clockOf(String stream) {
                return "db1";
            }
        };
    }

    private ResolverProcessor vertex(NestStreamFacts facts) throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, store, (from, released) -> dead.add(released),
                new PendingWatch(new PendingProtection(BACKSTOP), facts, () -> now));
        processor.init(new TestOutbox(128), new TestProcessorContext());
        return processor;
    }

    private static Envelope event(Op op, String src, long seq, Object... pairs) {
        Map<String, Object> fields = row(pairs);
        Envelope built = op == Op.DELETE
                ? Envelope.delete(seq, src, fields, null)
                : Envelope.insert(seq, src, fields, null);
        return built.withOrder(new SourceOrder(1L, seq));
    }

    private static Envelope policy(long seq, String policyId, String customerId) {
        return event(Op.INSERT, "policy", seq,
                "policy_id", policyId, "customer_id", customerId, "policy_no", "PN-" + policyId);
    }

    private static Envelope claim(long seq, String claimId, String policyId) {
        return event(Op.INSERT, "claim", seq, "claim_id", claimId, "policy_id", policyId);
    }

    private static void feed(ResolverProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
    }

    @Test
    void aChangeIsReleasedOnceItsParentsStreamHasFinishedLoadingAndBeenReadPastIt() throws Exception {
        ResolverProcessor vertex = vertex(oneDatabase(true));
        feed(vertex, CLAIMS, claim(2, "CL1", "P1"));
        // A policy for another customer, later than the waiting claim. The policy that would answer this
        // claim would have been filed on this same key -- a parent row and the children waiting for it are
        // partitioned alike -- so a policy read after that claim's time and still no answer here means
        // there is no such policy to come.
        feed(vertex, OWN_ROWS, policy(10, "P9", "C9"));

        vertex.tryProcess();

        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).verdict()).isEqualTo(PendingVerdict.PARENT_ABSENT);
        assertThat(dead.get(0).child().ref().elementKey()).containsExactly("CL1");
    }

    @Test
    void aChangeIsHeldWhileTheStreamItsParentWouldComeOnIsStillLoading() throws Exception {
        ResolverProcessor vertex = vertex(oneDatabase(false));
        feed(vertex, CLAIMS, claim(2, "CL1", "P1"));
        feed(vertex, OWN_ROWS, policy(10, "P9", "C9"));

        vertex.tryProcess();

        assertThat(dead)
                .describedAs("the policy answering it may still be among the rows the load has not produced")
                .isEmpty();
    }

    @Test
    void aChangeIsHeldWhileItsParentsStreamHasNotBeenReadPastIt() throws Exception {
        ResolverProcessor vertex = vertex(oneDatabase(true));
        feed(vertex, CLAIMS, claim(9, "CL1", "P1"));
        feed(vertex, OWN_ROWS, policy(2, "P9", "C9"));

        vertex.tryProcess();

        assertThat(dead)
                .describedAs("the policy answering it could be the very next row on that stream")
                .isEmpty();
    }

    @Test
    void aChangeWhoseParentIsReadOffAnotherClockWaitsForTheBackstopInstead() throws Exception {
        ResolverProcessor vertex = vertex(new NestStreamFacts() {
            @Override
            public boolean loaded(String stream) {
                return true;
            }

            @Override
            public String clockOf(String stream) {
                return stream;
            }
        });
        feed(vertex, CLAIMS, claim(2, "CL1", "P1"));
        feed(vertex, OWN_ROWS, policy(10, "P9", "C9"));

        vertex.tryProcess();
        assertThat(dead)
                .describedAs("two clocks: a claim whose clock runs behind would have its policy declared "
                        + "absent while that policy was still on its way")
                .isEmpty();

        now += BACKSTOP.toMillis();
        vertex.tryProcess();

        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).verdict())
                .describedAs("nothing was established; the wait was simply ended, and that reads differently")
                .isEqualTo(PendingVerdict.WALL_CLOCK_BACKSTOP);
    }
}
