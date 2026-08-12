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
import io.tapstate.core.model.NestRoot;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A hand-over that lands after the document has already gone out has to send the document again.
 *
 * <p>The two halves of a move are routed by different keys, so the half that arrives may perfectly well run
 * before anything has been parked for it - and when it does, the document it renders is the one <em>without</em>
 * what is being handed over. The rows land later, on an idle turn, into a document nobody is going to change
 * again.
 *
 * <p>Storing them and stopping there is the silent shape of losing them. The state is right, so every reading
 * is healthy and no error is counted; what is wrong is downstream, where the sink is holding a document that
 * is missing rows and will go on holding it for as long as nothing else touches that key. Meanwhile the hold
 * on the frontier is let go of at that same moment, so a restart resumes above the change and nothing replays
 * it either.
 *
 * <p>Asserted on <b>what left the vertex</b> and never on what the store holds. The store being right is
 * exactly the condition under which this fails, so a test reading the store is green on the broken code -
 * which is how it went unnoticed on the element path.
 */
class ADocumentThatGainsWhatWasParkedGoesOutSayingSoTest {

    private static final TransformBody.Nest ELEMENT_TREE = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final TransformBody.Nest ROOT_TREE = nest(new NestRoot("customer", List.of("customer_id"),
            null, true,
            List.of(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no")))));

    private static final int OWN_ROWS = 0;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
    private final TestOutbox out = new TestOutbox(256);
    private final TestOutbox resolverOut = new TestOutbox(256);

    /**
     * A subtree between two documents, collected on the idle turn after the element itself had already been
     * rendered and sent.
     */
    @Test
    void aSubtreeCollectedLaterIsShownToWhoeverIsDownstream() throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", ELEMENT_TREE, tables());
        ResolverProcessor policies = resolver(topology);
        AssemblerProcessor gaining = assembler(topology);
        AssemblerProcessor losing = assembler(topology);
        root(gaining, topology, "C1");
        root(gaining, topology, "C2");
        upward(gaining, through(policies, 0, policy(2, "P1", "C1")));
        upward(gaining, through(policies, 1, claim(3, "K1", "P1")));

        List<Object> halves = through(policies, 0, policyMoved(4, "P1", "C1", "C2"));
        upward(gaining, List.of(halves.get(1)));
        upward(losing, List.of(halves.get(0)));
        out.drainQueueAndReset(0, new ArrayList<>(), false);

        gaining.tryProcess();

        assertThat(claimsIn(lastSentFor("C2")))
                .describedAs("the sink was handed a document with no claims and nothing else is coming for "
                        + "that key; the rows are in the state and in nothing anyone downstream can see")
                .hasSize(1);
    }

    /** The same, for a whole document parked while its root row changed key. */
    @Test
    void aWholeDocumentCollectedLaterIsShownToWhoeverIsDownstream() throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", ROOT_TREE, tables());
        AssemblerProcessor gaining = assembler(topology);
        AssemblerProcessor losing = assembler(topology);
        root(gaining, topology, "C1");
        feed(gaining, 1, Envelope.insert(2, "policy",
                row("policy_id", "P1", "customer_id", "C1", "policy_no", "PN-P1"), null).withOrder(at(2)));

        Envelope renamed = Envelope.update(9, "customer",
                row("customer_id", "C1", "name", "n"),
                row("customer_id", "C2", "name", "n"), null).withOrder(at(9));
        feed(gaining, OWN_ROWS, renamed);
        feed(losing, 2, renamed);
        out.drainQueueAndReset(0, new ArrayList<>(), false);

        gaining.tryProcess();

        assertThat(policiesIn(lastSentFor("C2")))
                .describedAs("the document went out empty and the tree landed afterwards; without a second "
                        + "send the sink keeps the empty one for good")
                .hasSize(1);
    }

    // ---- harness ------------------------------------------------------------------------

    private final List<Object> sent = new ArrayList<>();

    private AssemblerProcessor assembler(NestTopology topology) throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(topology.assembler(), topology.slots(),
                documents, "doc", null, null, ReplayFloor.NONE, NestSettings.defaults(), NestClock.SYSTEM,
                NestSendPolicy.within(0), stores.forParking(topology.assembler()));
        processor.init(out, new TestProcessorContext());
        return processor;
    }

    private ResolverProcessor resolver(NestTopology topology) throws Exception {
        ResolverProcessor processor = new ResolverProcessor(topology.vertexAt(List.of("policies")),
                new HeapNestStore<>(), (from, released) -> { });
        processor.init(resolverOut, new TestProcessorContext());
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
        processor.process(1, inbox);
    }

    private void feed(AssemblerProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
    }

    private void root(AssemblerProcessor processor, NestTopology topology, String customerId) {
        feed(processor, OWN_ROWS, Envelope.insert(1, "customer",
                row("customer_id", customerId, "name", "n"), null).withOrder(at(1)));
    }

    /** The last row this vertex sent for {@code customerId}, or an empty row if it never sent one. */
    private Map<String, Object> lastSentFor(String customerId) {
        out.drainQueueAndReset(0, sent, false);
        Map<String, Object> latest = new LinkedHashMap<>();
        for (Object item : sent) {
            if (item instanceof Envelope event && event.after() != null
                    && customerId.equals(event.after().get("customer_id"))) {
                latest = event.after();
            }
        }
        return latest;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> policiesIn(Map<String, Object> document) {
        Object policies = document.get("policies");
        return policies == null ? List.of() : (List<Map<String, Object>>) policies;
    }

    private static List<Map<String, Object>> claimsIn(Map<String, Object> document) {
        List<Map<String, Object>> policies = policiesIn(document);
        if (policies.isEmpty()) {
            return List.of();
        }
        Object claims = policies.get(0).get("claims");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> held = claims == null ? List.of() : (List<Map<String, Object>>) claims;
        return held;
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
