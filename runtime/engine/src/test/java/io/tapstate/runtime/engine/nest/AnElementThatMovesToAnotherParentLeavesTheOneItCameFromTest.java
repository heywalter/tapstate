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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The second structural family: the row is re-pointed at another parent. Unlike a change of the key a
 * document shows an element by, this one can take the element out of the document altogether, because
 * which document it belongs to is exactly what the join key decides.
 *
 * <p>So one change has to reach two places. The element itself climbs to wherever its new parent leads,
 * and a second item climbs the old key to say the element has left - and the two are routed independently,
 * because whether they meet again in one document is not something the level that saw the row can know.
 * Where they do meet, the assembly recognises the pair and carries the node across in one piece. Where they
 * do not, one document gains the element and the other loses it.
 *
 * <p><b>What is deliberately not done yet is an element that has a subtree of its own crossing into another
 * document.</b> Carrying it means handing a whole subtree from one document to another, which is what the
 * parking area is for; until that exists, such an element stays where it is rather than being taken apart.
 * Left in place it is a document that disagrees with the source - the same ghost an untracked tree accepts
 * - where taking it apart would be rows nothing will ever resend, gone with nothing to report it. The test
 * below pins that choice so the weaker of the two failures cannot be reached by accident.
 */
class AnElementThatMovesToAnotherParentLeavesTheOneItCameFromTest {

    private static final TransformBody.Nest TRACKED = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    tracking(embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims",
                            List.of("claim_id")))));

    private static final TransformBody.Nest UNTRACKED = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    /** The switch one level up, so a row that carries its own parent key is the one being moved. */
    private static final TransformBody.Nest TRACKED_POLICIES = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    /** A leaf hanging straight off the root, which the assembler takes without any resolver in between. */
    private static final TransformBody.Nest TRACKED_LEAF_ON_ROOT = nest("customer", List.of("customer_id"),
            tracking(embed("profile", "customer_id", "customer_id", EmbedAs.OBJECT, "profile",
                    List.of("customer_id"))));

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;

    @Test
    void aClaimMovedToASiblingPolicyIsShownUnderTheNewOneAlone() throws Exception {
        Chain chain = new Chain(TRACKED);
        chain.customer(1, "C1");
        chain.policy(1, "P1", "C1");
        chain.policy(2, "P2", "C1");
        chain.claim(3, "K1", "P1");

        chain.claimMoved(4, "K1", "P1", "P2");

        assertThat(chain.claimsUnder("C1", 0)).describedAs("the policy it left").isEmpty();
        assertThat(chain.claimsUnder("C1", 1)).describedAs("the policy it joined").hasSize(1);
    }

    /**
     * The case the pair exists for: the two halves resolve to different documents, so no single assembly
     * ever sees both. One gains the element, the other has to be told it is gone.
     */
    @Test
    void aClaimMovedToAPolicyOfAnotherCustomerLeavesTheFirstDocument() throws Exception {
        Chain chain = new Chain(TRACKED);
        chain.customer(1, "C1");
        chain.customer(1, "C2");
        chain.policy(1, "P1", "C1");
        chain.policy(2, "P2", "C2");
        chain.claim(3, "K1", "P1");

        chain.claimMoved(4, "K1", "P1", "P2");

        assertThat(chain.claimsUnder("C1", 0))
                .describedAs("the document it left keeps nothing of it")
                .isEmpty();
        assertThat(chain.claimsUnder("C2", 0))
                .describedAs("the document it joined shows it once")
                .hasSize(1);
    }

    /**
     * The same move one level up, on a row of an embed that has a resolver of its own. It takes a different
     * path through the code - such a row carries its own parent key rather than being routed by one - and a
     * test that only moved a leaf would leave that path unwitnessed.
     */
    @Test
    void aPolicyWithNothingBeneathItMovedToAnotherCustomerLeavesTheFirstDocument() throws Exception {
        Chain chain = new Chain(TRACKED_POLICIES);
        chain.customer(1, "C1");
        chain.customer(1, "C2");
        chain.policy(2, "P1", "C1");

        chain.policyMoved(3, "P1", "C1", "C2");

        assertThat(chain.policiesOf("C1"))
                .describedAs("the document it left keeps nothing of it")
                .isEmpty();
        assertThat(chain.policiesOf("C2"))
                .describedAs("the document it joined shows it once")
                .hasSize(1);
    }

    /** Without the switch there is no pair and no comparison, so both documents claim the element. */
    @Test
    void anUntrackedClaimMovedAcrossCustomersIsLeftInBothDocuments() throws Exception {
        Chain chain = new Chain(UNTRACKED);
        chain.customer(1, "C1");
        chain.customer(1, "C2");
        chain.policy(1, "P1", "C1");
        chain.policy(2, "P2", "C2");
        chain.claim(3, "K1", "P1");

        chain.claimMoved(4, "K1", "P1", "P2");

        assertThat(chain.claimsUnder("C1", 0)).hasSize(1);
        assertThat(chain.claimsUnder("C2", 0)).hasSize(1);
    }

    /**
     * A leaf hanging straight off the root reaches no resolver at all - the assembler reads its join key
     * itself - so the pair is neither routed nor emitted here: both documents are held by this one vertex.
     * That is a path of its own, and the two documents it settles are the two it has to settle.
     */
    @Test
    void aLeafOnTheRootMovedToAnotherCustomerLeavesTheFirstDocument() throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", TRACKED_LEAF_ON_ROOT, tables());
        HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
        AssemblerProcessor assembler =
                new AssemblerProcessor(topology.assembler(), topology.slots(), documents, "doc");
        TestOutbox out = new TestOutbox(256);
        assembler.init(out, new TestProcessorContext());
        feed(assembler, out, 0, Envelope.insert(1, "customer", row("customer_id", "C1"), null).withOrder(at(1)));
        feed(assembler, out, 0, Envelope.insert(1, "customer", row("customer_id", "C2"), null).withOrder(at(1)));
        feed(assembler, out, 1, Envelope.insert(2, "profile",
                row("customer_id", "C1", "tier", "gold"), null).withOrder(at(2)));

        Envelope moved = Envelope.update(3, "profile",
                row("customer_id", "C1", "tier", "gold"),
                row("customer_id", "C2", "tier", "gold"), null).withOrder(at(3));
        // Both edges, because the graph draws both: the same row keyed by where it now belongs and by where
        // it was, so the document it is leaving is settled by whoever holds it rather than reached for.
        feed(assembler, out, 1, moved);
        feed(assembler, out, departureTwin(topology, List.of("profile")), moved);

        assertThat(documents.load(List.of("C1")).render(topology.slots()).orElseThrow())
                .describedAs("an object embed with nothing in it omits its field rather than showing null")
                .doesNotContainKey("profile");
        assertThat(documents.load(List.of("C2")).render(topology.slots()).orElseThrow())
                .containsKey("profile");
    }

    /**
     * The same move, read off what leaves the vertex rather than off what it stored.
     *
     * <p>The witness above reads the store, and the store being right is precisely the condition under
     * which this can be wrong: a document held back is a document stored correctly and shown to nobody.
     * The arriving half is told whether something was sent to the key the element used to hang from, and
     * takes that as "a subtree is on its way to me" - so it holds the document back until it collects one.
     * A leaf owns no subtree, nothing is ever parked for it, and the wait has no end: the element is in
     * neither document downstream, the run reports RUNNING with no errors, and the frontier stops moving
     * because the change that started it is still held.
     *
     * <p>Asserting the departure alone would not catch it either - the old document really does go out
     * without the element. It is the document that gains it that never leaves, so that is what is read.
     */
    @Test
    void aLeafOnTheRootMovedToAnotherCustomerGoesOutUnderTheNewOne() throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", TRACKED_LEAF_ON_ROOT, tables());
        HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
        // Built with somewhere to hand a subtree through, which is what a run actually has. Without one the
        // arriving half never looks for a hand-over at all, so every witness above is blind to this by
        // construction rather than by what it asserts.
        NestBinding.NestStores stores = HeapNestStores.onHeap();
        AssemblerProcessor assembler = new AssemblerProcessor(topology.assembler(), topology.slots(),
                documents, "doc", null, null, ReplayFloor.NONE, NestSettings.defaults(), NestClock.SYSTEM,
                NestSendPolicy.within(0), stores.forParking(topology.assembler()));
        TestOutbox out = new TestOutbox(256);
        assembler.init(out, new TestProcessorContext());
        feed(assembler, out, 0, Envelope.insert(1, "customer", row("customer_id", "C1"), null).withOrder(at(1)));
        feed(assembler, out, 0, Envelope.insert(1, "customer", row("customer_id", "C2"), null).withOrder(at(1)));
        feed(assembler, out, 1, Envelope.insert(2, "profile",
                row("customer_id", "C1", "tier", "gold"), null).withOrder(at(2)));

        Envelope moved = Envelope.update(3, "profile",
                row("customer_id", "C1", "tier", "gold"),
                row("customer_id", "C2", "tier", "gold"), null).withOrder(at(3));
        List<Object> emitted = new ArrayList<>();
        feedKeeping(assembler, out, 1, moved, emitted);
        feedKeeping(assembler, out, departureTwin(topology, List.of("profile")), moved, emitted);

        assertThat(documentsAmong(emitted, "C2"))
                .describedAs("what left the vertex for the document that gained the element")
                .isNotEmpty();
        assertThat(documentsAmong(emitted, "C2").get(documentsAmong(emitted, "C2").size() - 1))
                .describedAs("the last thing said about the document that gained it")
                .containsKey("profile");
    }

    /** Every document that left the vertex naming {@code customerId}, in the order they left. */
    private static List<Map<String, Object>> documentsAmong(List<Object> emitted, String customerId) {
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object item : emitted) {
            if (item instanceof Envelope envelope && envelope.after() != null
                    && customerId.equals(envelope.after().get("customer_id"))) {
                documents.add(envelope.after());
            }
        }
        return documents;
    }

    /** Feeds one event and keeps what the vertex emitted, rather than discarding it like {@link #feed}. */
    private static void feedKeeping(
            AssemblerProcessor processor, TestOutbox out, int ordinal, Envelope event, List<Object> emitted) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        out.drainQueueAndReset(0, emitted, false);
    }

    /**
     * An element carrying a subtree into another document is left alone rather than taken apart. Removing
     * it would drop rows that nothing resends; leaving it is a stale copy, which is the failure the tree
     * already accepts when it tracks nothing at all.
     */
    @Test
    void anElementWithASubtreeCrossingDocumentsIsNotTakenApart() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.take(new NestElement(new ElementRef(List.of("policies"), null, List.of("PN-1"), "P1"),
                row("policy_no", "PN-1"), at(2), Map.of()));
        assembly.take(new NestElement(
                new ElementRef(List.of("policies", "claims"), "P1", List.of("K1"), null),
                row("claim_id", "K1"), at(3), Map.of()));

        assembly.take(new NestElement(new ElementRef(List.of("policies"), null, List.of("PN-1"), "P1"),
                null, at(4), Map.of(),
                new ElementRef(List.of("policies"), null, List.of("PN-1"), "P1")));

        Map<String, Object> document = assembly.render(slotsOf(TRACKED)).orElseThrow();
        assertThat(NestFixtures.listAt(document, "policies"))
                .describedAs("kept whole rather than emptied of a subtree nothing will resend")
                .hasSize(1);
        assertThat(NestFixtures.listAt(document, "policies", "claims")).hasSize(1);
    }

    /** The ordinal the graph delivers {@code pathId}'s rows on keyed by what they are leaving. */
    private static int departureTwin(NestTopology topology, List<String> pathId) {
        for (NestInbound edge : topology.assembler().inbound()) {
            if (edge.carriesDepartures() && edge.pathId().equals(pathId)) {
                return edge.ordinal();
            }
        }
        throw new AssertionError("no departure edge carries " + pathId);
    }

    private static void feed(AssemblerProcessor processor, TestOutbox out, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        out.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    private static List<EmbedSlot> slotsOf(TransformBody.Nest tree) {
        return NestTopology.compile("p", "doc", tree, tables()).slots();
    }

    /**
     * The policies resolver feeding the assembler, which is the shortest run in which both halves of a move
     * are routed by what they carry rather than by what a test decided.
     */
    private static final class Chain {

        private final ResolverProcessor resolver;
        private final AssemblerProcessor assembler;
        private final TestOutbox resolverOut = new TestOutbox(256);
        private final TestOutbox assemblerOut = new TestOutbox(256);
        private final List<EmbedSlot> slots;
        private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();

        private final TransformBody.Nest tree;

        Chain(TransformBody.Nest tree) throws Exception {
            this.tree = tree;
            NestTopology topology = NestTopology.compile("p", "doc", tree, tables());
            this.slots = topology.slots();
            this.resolver = new ResolverProcessor(topology.vertexAt(List.of("policies")),
                    new HeapNestStore<>(), (from, released) -> { });
            this.resolver.init(resolverOut, new TestProcessorContext());
            this.assembler = new AssemblerProcessor(topology.assembler(), slots, documents, "doc");
            this.assembler.init(assemblerOut, new TestProcessorContext());
        }

        /** A root row, which the assembler takes directly - it passes through no resolver. */
        void customer(long seq, String id) {
            TestInbox inbox = new TestInbox();
            inbox.queue().add(Envelope.insert(seq, "customer", row("customer_id", id, "name", "n" + id), null)
                    .withOrder(at(seq)));
            assembler.process(0, inbox);
            List<Object> sent = new ArrayList<>();
            assemblerOut.drainQueueAndReset(0, sent, false);
        }

        void policy(long seq, String policyId, String customerId) {
            through(OWN_ROWS, Envelope.insert(seq, "policy", row("policy_id", policyId,
                    "customer_id", customerId, "policy_no", "PN-" + policyId), null).withOrder(at(seq)));
        }

        void policyMoved(long seq, String policyId, String was, String is) {
            through(OWN_ROWS, Envelope.update(seq, "policy",
                    row("policy_id", policyId, "customer_id", was, "policy_no", "PN-" + policyId),
                    row("policy_id", policyId, "customer_id", is, "policy_no", "PN-" + policyId), null)
                    .withOrder(at(seq)));
        }

        void claim(long seq, String claimId, String policyId) {
            through(CLAIMS, Envelope.insert(seq, "claim",
                    row("claim_id", claimId, "policy_id", policyId), null).withOrder(at(seq)));
        }

        void claimMoved(long seq, String claimId, String was, String is) {
            through(CLAIMS, Envelope.update(seq, "claim",
                    row("claim_id", claimId, "policy_id", was),
                    row("claim_id", claimId, "policy_id", is), null).withOrder(at(seq)));
        }

        /**
         * One event through the resolver, then everything it routed through the assembler.
         *
         * <p>Delivered on the departure twin as well where the tree has one, because that is what the graph
         * does: a processor emits to every outbound edge it has, so a tracked stream reaches its vertex
         * twice - once keyed by where the row now is, once by where it was. A harness feeding only the first
         * would be testing a graph nobody runs.
         */
        private void through(int ordinal, Envelope event) {
            deliver(ordinal, event);
            departureTwinOf(ordinal).ifPresent(twin -> deliver(twin, event));
        }

        private java.util.OptionalInt departureTwinOf(int ordinal) {
            NestVertex vertex = NestTopology.compile("p", "doc", tree, tables()).vertexAt(List.of("policies"));
            NestInbound arriving = vertex.inbound().get(ordinal);
            for (NestInbound edge : vertex.inbound()) {
                if (edge.carriesDepartures() && edge.pathId().equals(arriving.pathId())) {
                    return java.util.OptionalInt.of(edge.ordinal());
                }
            }
            return java.util.OptionalInt.empty();
        }

        private void deliver(int ordinal, Envelope event) {
            TestInbox inbox = new TestInbox();
            inbox.queue().add(event);
            resolver.process(ordinal, inbox);
            List<Object> routed = new ArrayList<>();
            resolverOut.drainQueueAndReset(0, routed, false);
            if (routed.isEmpty()) {
                return;
            }
            TestInbox upward = new TestInbox();
            upward.queue().addAll(routed);
            assembler.process(1, upward);
            List<Object> sent = new ArrayList<>();
            assemblerOut.drainQueueAndReset(0, sent, false);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> policiesOf(String customerId) {
            RootAssembly assembly = documents.load(List.of(customerId));
            assertThat(assembly).describedAs("a document exists for %s", customerId).isNotNull();
            Map<String, Object> document = assembly.render(slots).orElse(new LinkedHashMap<>());
            return (List<Map<String, Object>>) document.getOrDefault("policies", List.of());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> claimsUnder(String customerId, int policyIndex) {
            RootAssembly assembly = documents.load(List.of(customerId));
            assertThat(assembly).describedAs("a document exists for %s", customerId).isNotNull();
            Map<String, Object> document = assembly.render(slots).orElse(new LinkedHashMap<>());
            List<Map<String, Object>> policies =
                    (List<Map<String, Object>>) document.getOrDefault("policies", List.of());
            if (policies.size() <= policyIndex) {
                return List.of();
            }
            Object claims = policies.get(policyIndex).get("claims");
            return claims == null ? List.of() : (List<Map<String, Object>>) claims;
        }
    }
}
