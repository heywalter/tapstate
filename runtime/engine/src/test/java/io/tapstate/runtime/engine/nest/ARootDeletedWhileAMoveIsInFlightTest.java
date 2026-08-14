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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Two hard rules meet here and appear to contradict each other: a deleted root goes out at once, and a
 * move in flight holds emissions back until it lands. They do not actually collide, because they are about
 * different things - what is flushed is the deletion itself, what is held back is a half-assembled document
 * - and the four ways they can meet all fall out of rules that already exist rather than needing any
 * mechanism of their own.
 *
 * <p>Which is exactly why they are written down as cases. "It follows from the existing rules" is a claim
 * about behaviour, and the only way it stops being a claim is a test that would fail if it stopped being
 * true. Each of these is one line of reasoning away from a silent wrong answer: rows dropped with a root
 * that was mid-move, or a deleted document reappearing because something arrived for it afterwards.
 */
class ARootDeletedWhileAMoveIsInFlightTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;
    private static final int FROM_POLICIES = 1;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
    private final TestOutbox resolverOut = new TestOutbox(256);
    private final TestOutbox assemblerOut = new TestOutbox(256);

    /**
     * The document the element is leaving is deleted while its subtree sits parked. The rows are not in that
     * document any more, so nothing about deleting it can lose them - and the document that was going to
     * gain them still does.
     */
    @Test
    void theDocumentTheElementLeftMayBeDeletedWithoutLosingWhatWasParked() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor gaining = assembler();
        AssemblerProcessor losing = assembler();
        givenAPolicyWithAClaimUnderC1(policies, gaining);
        root(gaining, "C2", 1);

        List<Object> pair = through(policies, OWN_ROWS, policyMoved(4, "P1", "C1", "C2"));
        upward(losing, List.of(pair.get(0)));
        deleteRoot(losing, "C1", 5);
        upward(gaining, List.of(pair.get(1)));
        gaining.tryProcess();

        assertThat(claimsUnder(policiesOf("C2").get(0)))
                .describedAs("the rows had already left C1, so deleting C1 cannot take them with it")
                .hasSize(1);
    }

    /**
     * The document that gains the element is deleted before what it was owed arrives. What is parked still
     * lands - it is state, and the document is a tombstone rather than nothing - but nothing goes out for
     * it, because a document with no root renders nothing at all.
     */
    @Test
    void aHandOverIntoADeletedDocumentLandsInItsStateAndIsShownToNobody() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor gaining = assembler();
        AssemblerProcessor losing = assembler();
        givenAPolicyWithAClaimUnderC1(policies, gaining);
        root(gaining, "C2", 1);
        deleteRoot(gaining, "C2", 5);

        List<Object> pair = through(policies, OWN_ROWS, policyMoved(6, "P1", "C1", "C2"));
        upward(losing, List.of(pair.get(0)));
        upward(gaining, List.of(pair.get(1)));
        gaining.tryProcess();

        assertThat(documents.load(List.of("C2")).render(TOPOLOGY.slots()))
                .describedAs("a deleted root renders nothing, whatever arrived for it afterwards")
                .isEmpty();
    }

    /**
     * And it is not resurrected by it either. A document that came back would be one the source deleted
     * reappearing downstream because something unrelated arrived for it, which nothing later removes.
     */
    @Test
    void aHandOverDoesNotBringADeletedDocumentBack() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor gaining = assembler();
        AssemblerProcessor losing = assembler();
        givenAPolicyWithAClaimUnderC1(policies, gaining);
        root(gaining, "C2", 1);
        deleteRoot(gaining, "C2", 5);

        List<Object> pair = through(policies, OWN_ROWS, policyMoved(6, "P1", "C1", "C2"));
        upward(losing, List.of(pair.get(0)));
        upward(gaining, List.of(pair.get(1)));
        gaining.tryProcess();

        assertThat(documents.load(List.of("C2")).rootPresent())
                .describedAs("only the root's own row brings a root back, never an element arriving")
                .isFalse();
    }

    /**
     * When the root does come back, everything that arrived while it was gone is there. That is what makes
     * the previous two safe rather than merely quiet: the rows were kept, not discarded, so the document
     * the source rebuilds is the document the source has.
     */
    @Test
    void whenTheRootComesBackWhatArrivedWhileItWasGoneIsThere() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor gaining = assembler();
        AssemblerProcessor losing = assembler();
        givenAPolicyWithAClaimUnderC1(policies, gaining);
        root(gaining, "C2", 1);
        deleteRoot(gaining, "C2", 5);
        List<Object> pair = through(policies, OWN_ROWS, policyMoved(6, "P1", "C1", "C2"));
        upward(losing, List.of(pair.get(0)));
        upward(gaining, List.of(pair.get(1)));
        gaining.tryProcess();

        root(gaining, "C2", 9);

        List<Map<String, Object>> gained = policiesOf("C2");
        assertThat(gained).hasSize(1);
        assertThat(claimsUnder(gained.get(0)))
                .describedAs("the subtree was kept while the root was gone, not dropped")
                .hasSize(1);
    }

    private void givenAPolicyWithAClaimUnderC1(ResolverProcessor policies, AssemblerProcessor gaining) {
        root(gaining, "C1", 1);
        upward(gaining, through(policies, OWN_ROWS, policy(2, "P1", "C1")));
        upward(gaining, through(policies, CLAIMS, claim(3, "K1", "P1")));
    }

    private ResolverProcessor policies() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(TOPOLOGY.vertexAt(List.of("policies")),
                new HeapNestStore<>(), (from, released) -> { });
        processor.init(resolverOut, new TestProcessorContext());
        return processor;
    }

    private AssemblerProcessor assembler() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                documents, "doc", null, null, io.tapstate.runtime.engine.ReplayFloor.NONE,
                NestSettings.defaults(), NestClock.SYSTEM, NestSendPolicy.within(0),
                stores.forParking(TOPOLOGY.assembler()));
        processor.init(assemblerOut, new TestProcessorContext());
        return processor;
    }

    private List<Object> through(ResolverProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        List<Object> routed = new ArrayList<>();
        resolverOut.drainQueueAndReset(0, routed, false);
        return routed;
    }

    private void upward(AssemblerProcessor processor, List<Object> routed) {
        if (routed.isEmpty()) {
            return;
        }
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(routed);
        processor.process(FROM_POLICIES, inbox);
        assemblerOut.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    private void root(AssemblerProcessor processor, String customerId, long seq) {
        feedOwn(processor, Envelope.insert(seq, "customer", row("customer_id", customerId), null)
                .withOrder(at(seq)));
    }

    private void deleteRoot(AssemblerProcessor processor, String customerId, long seq) {
        feedOwn(processor, Envelope.delete(seq, "customer", row("customer_id", customerId), null)
                .withOrder(at(seq)));
    }

    private void feedOwn(AssemblerProcessor processor, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(OWN_ROWS, inbox);
        assemblerOut.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> policiesOf(String customerId) {
        RootAssembly assembly = documents.load(List.of(customerId));
        assertThat(assembly).describedAs("a document exists for %s", customerId).isNotNull();
        Map<String, Object> document = assembly.render(TOPOLOGY.slots()).orElse(new LinkedHashMap<>());
        return (List<Map<String, Object>>) document.getOrDefault("policies", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> claimsUnder(Map<String, Object> policy) {
        Object claims = policy.get("claims");
        return claims == null ? List.of() : (List<Map<String, Object>>) claims;
    }

    private static Envelope policy(long seq, String policyId, String customerId) {
        return Envelope.insert(seq, "policy", row("policy_id", policyId, "customer_id", customerId,
                "policy_no", "PN-" + policyId), null).withOrder(at(seq));
    }

    private static Envelope policyMoved(long seq, String policyId, String was, String is) {
        return Envelope.update(seq, "policy",
                row("policy_id", policyId, "customer_id", was, "policy_no", "PN-" + policyId),
                row("policy_id", policyId, "customer_id", is, "policy_no", "PN-" + policyId), null)
                .withOrder(at(seq));
    }

    private static Envelope claim(long seq, String claimId, String policyId) {
        return Envelope.insert(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }
}
