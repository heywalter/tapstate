package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.Envelope;
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
 * The assembler holding elements for a customer row that never arrives. Nothing it holds is in any
 * document — a document with no root is a ghost downstream and is never emitted — so every one of them
 * keeps the durable frontier below it, and a frontier held below one element for good pins the position
 * its source reads from until the log rotates past it.
 *
 * <p>Two kinds of thing are held and only one of them can be reasoned about here. An element under an
 * absent root waits for a root row, which arrives on a stream this vertex reads and can say how far it has
 * got. An element waiting for an ancestor waits for something the level below has already routed, arriving
 * on an edge that names no stream at all — so nothing about that stream can be asked and only the backstop
 * can ever end that wait. Handing both to one rule would answer the second with evidence gathered about
 * the first.
 *
 * <p>Both streams here are read from one database, so their event times come off one clock. That is not
 * something the engine can see, so it is told.
 */
class AnAssemblerReleasesWhatItHoldsForARootThatWillNotComeTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int ROOT_ROWS = 0;
    private static final int FROM_POLICIES = 1;
    private static final Duration BACKSTOP = Duration.ofHours(6);
    private static final long NOON = 1_700_000_000_000L;

    private final HeapNestStore<RootAssembly> store = new HeapNestStore<>();
    private final List<ReleasedChild> dead = new ArrayList<>();
    private long now = NOON;

    /** Both streams off one database clock, with the customers read told to have finished or not. */
    private static NestStreamFacts oneDatabase(boolean customersLoaded) {
        return new NestStreamFacts() {
            @Override
            public boolean loaded(String stream) {
                return "customer".equals(stream) && customersLoaded;
            }

            @Override
            public String clockOf(String stream) {
                return "db1";
            }
        };
    }

    private AssemblerProcessor vertex(NestStreamFacts facts) throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(), store,
                "doc", (from, released) -> dead.add(released),
                new PendingWatch(new PendingProtection(BACKSTOP), facts, () -> now));
        processor.init(new TestOutbox(128), new TestProcessorContext());
        return processor;
    }

    private static SourceOrder at(long seq) {
        return new SourceOrder(1L, seq);
    }

    /** A customer of another key, read later than what is waiting — the row that proves the read passed it. */
    private static Envelope customer(long seq, String id) {
        return Envelope.insert(seq, "customer", row("customer_id", id, "name", "n" + id), null)
                .withOrder(at(seq));
    }

    /** A policy element as the policies resolver would have routed it: keyed by the customer it hangs from. */
    private static KeyedElement policyElement(long seq, String customerId, String policyId) {
        ElementRef ref = new ElementRef(List.of("policies"), null, List.of("PN-" + policyId), policyId);
        return new KeyedElement(List.of(customerId),
                new NestElement(ref, row("policy_id", policyId, "policy_no", "PN-" + policyId), at(seq),
                        Map.of("policy", new ChainPosition(at(seq), "t" + seq)), seq));
    }

    /** A claim element, which hangs under a policy row rather than under the root. */
    private static KeyedElement claimElement(long seq, String customerId, String policyId, String claimId) {
        ElementRef ref = new ElementRef(List.of("policies", "claims"), policyId, List.of(claimId), claimId);
        return new KeyedElement(List.of(customerId),
                new NestElement(ref, row("claim_id", claimId, "policy_id", policyId), at(seq),
                        Map.of("claim", new ChainPosition(at(seq), "t" + seq)), seq));
    }

    private static void feed(AssemblerProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
    }

    /** Facts that count how often they are asked, so the cost of sweeping can be measured. */
    private static final class CountingFacts implements NestStreamFacts {

        private int asked;

        @Override
        public boolean loaded(String stream) {
            asked++;
            return false;
        }

        @Override
        public String clockOf(String stream) {
            return "db1";
        }
    }

    @Test
    void theSweepDoesNotRunOnEveryIdleTurn() throws Exception {
        CountingFacts facts = new CountingFacts();
        AssemblerProcessor vertex = vertex(facts);
        feed(vertex, FROM_POLICIES, policyElement(2, "C1", "P1"));

        vertex.tryProcess();
        vertex.tryProcess();
        vertex.tryProcess();
        assertThat(facts.asked)
                .describedAs("a sweep reads the layer behind the map, and the idle path is the one place "
                        + "that must never happen on every turn")
                .isEqualTo(1);

        now += Duration.ofSeconds(1).toMillis();
        vertex.tryProcess();
        assertThat(facts.asked).isEqualTo(2);
    }

    @Test
    void anElementIsReleasedOnceTheRootsStreamHasFinishedLoadingAndBeenReadPastIt() throws Exception {
        AssemblerProcessor vertex = vertex(oneDatabase(true));
        feed(vertex, FROM_POLICIES, policyElement(2, "C1", "P1"));
        // A customer of another key, read after the waiting element's own time. A customer row for C1 would
        // have come past this same processor -- a root row and the elements of its document are keyed alike
        // -- so a later customer read here and still no root for C1 means there is no such customer.
        feed(vertex, ROOT_ROWS, customer(10, "C9"));

        vertex.tryProcess();

        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).verdict()).isEqualTo(PendingVerdict.PARENT_ABSENT);
        assertThat(dead.get(0).child().ref().elementKey()).containsExactly("PN-P1");
        assertThat(store.load(List.of("C1")).lowestHeldByChain())
                .describedAs("the whole point: the bound this level reports has to rise past it")
                .isEmpty();
    }

    @Test
    void anElementIsHeldWhileTheStreamItsRootWouldComeOnIsStillLoading() throws Exception {
        AssemblerProcessor vertex = vertex(oneDatabase(false));
        feed(vertex, FROM_POLICIES, policyElement(2, "C1", "P1"));
        feed(vertex, ROOT_ROWS, customer(10, "C9"));

        vertex.tryProcess();

        assertThat(dead)
                .describedAs("the customer it belongs to may still be among the rows the load has not produced")
                .isEmpty();
    }

    @Test
    void anElementIsHeldWhileItsRootsStreamHasNotBeenReadPastIt() throws Exception {
        AssemblerProcessor vertex = vertex(oneDatabase(true));
        feed(vertex, FROM_POLICIES, policyElement(9, "C1", "P1"));
        feed(vertex, ROOT_ROWS, customer(2, "C9"));

        vertex.tryProcess();

        assertThat(dead)
                .describedAs("the customer it belongs to could be the very next row on that stream")
                .isEmpty();
    }

    @Test
    void anElementWhoseRootIsReadOffAnotherClockWaitsForTheBackstopInstead() throws Exception {
        AssemblerProcessor vertex = vertex(new NestStreamFacts() {
            @Override
            public boolean loaded(String stream) {
                return true;
            }

            @Override
            public String clockOf(String stream) {
                return stream;
            }
        });
        feed(vertex, FROM_POLICIES, policyElement(2, "C1", "P1"));
        feed(vertex, ROOT_ROWS, customer(10, "C9"));

        vertex.tryProcess();
        assertThat(dead)
                .describedAs("two clocks: an element whose clock runs behind would have its root declared "
                        + "absent while that root was still on its way")
                .isEmpty();

        now += BACKSTOP.toMillis();
        vertex.tryProcess();

        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).verdict()).isEqualTo(PendingVerdict.WALL_CLOCK_BACKSTOP);
    }

    @Test
    void anElementWaitingForAnAncestorOnlyEverWaitsForTheBackstop() throws Exception {
        AssemblerProcessor vertex = vertex(oneDatabase(true));
        // The customer arrives, so nothing here waits for a root. The claim waits for a policy row that has
        // not come up the cascade, and no evidence about a stream bears on that: the policy would arrive on
        // an edge that names no stream, already routed by the level below.
        feed(vertex, ROOT_ROWS, customer(1, "C1"), customer(10, "C9"));
        feed(vertex, FROM_POLICIES, claimElement(2, "C1", "P1", "CL1"));

        vertex.tryProcess();
        assertThat(dead)
                .describedAs("the evidence that ends a wait for a root says nothing about a wait for a policy")
                .isEmpty();

        now += BACKSTOP.toMillis();
        vertex.tryProcess();

        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).verdict()).isEqualTo(PendingVerdict.WALL_CLOCK_BACKSTOP);
        assertThat(dead.get(0).child().ref().elementKey()).containsExactly("CL1");
    }

    @Test
    void aKeyReadBackHoldingSomethingReentersTheSweep() throws Exception {
        // A restart starts the candidate set empty, and a change still held is one the frontier never
        // passed, so a restart replays it. The moment its key is touched again it has to be filed here
        // again, or it is held for good by a vertex that has forgotten it is holding it.
        RootAssembly held = new RootAssembly();
        held.take(policyElement(2, "C1", "P1").element(), NOON);
        store.save(List.of("C1"), held);

        AssemblerProcessor vertex = vertex(oneDatabase(true));
        feed(vertex, ROOT_ROWS, customer(10, "C9"));
        feed(vertex, FROM_POLICIES, policyElement(3, "C1", "P2"));

        vertex.tryProcess();

        assertThat(dead).extracting(child -> child.child().ref().elementKey())
                .containsExactlyInAnyOrder(List.of("PN-P1"), List.of("PN-P2"));
    }
}
