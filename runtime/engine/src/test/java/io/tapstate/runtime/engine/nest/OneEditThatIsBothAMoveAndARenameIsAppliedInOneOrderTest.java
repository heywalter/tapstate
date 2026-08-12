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
 * The common shape nobody declares on purpose: an embed whose array key - the value the document shows an
 * element by - is the same column its own children point at.
 *
 * <p>It is what you get by writing nothing. An embed with no {@code arrayKey} takes its table's primary key,
 * and children under it join on that same primary key, so the two are one column in most trees that were
 * never thought about. Editing it is therefore <b>one edit that is two structural changes at once</b>: the
 * element has to move to a different slot inside its document, and every child filed under the value it used
 * to answer to has to be carried to the value it answers to now.
 *
 * <p>The two are applied in a fixed order - the slot first, the children after - and the order is not a
 * preference. The second half is keyed by what the element is called, so applying it against an element that
 * is still filed under its old name resolves the children to a name nothing answers to any more: they are
 * carried nowhere, and the document that should hold them never learns they existed.
 *
 * <p>Written down because nothing else in the tree reaches it. Every other case here edits one of the two,
 * and both pass with the order wrong.
 */
class OneEditThatIsBothAMoveAndARenameIsAppliedInOneOrderTest {

    /**
     * The tree with the collision in it: policies are shown by {@code policy_id} and claims point at
     * {@code policy_id}, so one column is both the array key and the identity.
     */
    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_id"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;
    private static final int FROM_POLICIES = 1;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
    private final HeapNestStore<ResolverState> policyState = new HeapNestStore<>();
    private final TestOutbox resolverOut = new TestOutbox(256);
    private final TestOutbox assemblerOut = new TestOutbox(256);

    @Test
    void theElementIsShownUnderItsNewKeyAndNotItsOld() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor assembler = assembler();
        givenAPolicyWithOneClaim(policies, assembler);

        upward(assembler, through(policies, OWN_ROWS, policyRenamed()));

        assertThat(shownKeysOf("C1"))
                .describedAs("one element, under the value the source now shows it by")
                .containsExactly("P2");
    }

    @Test
    void whatHungUnderItIsStillUnderItAfterwards() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor assembler = assembler();
        givenAPolicyWithOneClaim(policies, assembler);

        upward(assembler, through(policies, OWN_ROWS, policyRenamed()));

        assertThat(claimsUnderTheOnlyPolicy("C1"))
                .describedAs("the slot changed and the subtree went with it, rather than being stranded "
                        + "under a name nothing answers to")
                .hasSize(1);
    }

    /**
     * The half the fixed order exists for, and the only one that fails silently. A child arriving <em>after</em>
     * the rename names the new value, so it is resolved against what the element is called now - and it finds
     * it only if the renaming half ran against an element that had already moved.
     */
    @Test
    void aChildArrivingAfterwardsUnderTheNewNameFindsIt() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor assembler = assembler();
        givenAPolicyWithOneClaim(policies, assembler);
        upward(assembler, through(policies, OWN_ROWS, policyRenamed()));

        upward(assembler, through(policies, CLAIMS, claim(9, "K2", "P2")));

        assertThat(claimsUnderTheOnlyPolicy("C1"))
                .describedAs("a child of the renamed element lands under it rather than waiting forever "
                        + "for a name that no longer exists")
                .hasSize(2);
    }

    /** The control: an edit that touches neither key leaves both halves alone. */
    @Test
    void anEditThatChangesNeitherIsAnOrdinaryUpdate() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor assembler = assembler();
        givenAPolicyWithOneClaim(policies, assembler);

        upward(assembler, through(policies, OWN_ROWS, Envelope.update(8, "policy",
                row("policy_id", "P1", "customer_id", "C1", "note", "before"),
                row("policy_id", "P1", "customer_id", "C1", "note", "after"), null).withOrder(at(8))));

        assertThat(shownKeysOf("C1")).containsExactly("P1");
        assertThat(claimsUnderTheOnlyPolicy("C1")).hasSize(1);
    }

    // ---- harness ------------------------------------------------------------------------

    private void givenAPolicyWithOneClaim(ResolverProcessor policies, AssemblerProcessor assembler)
            throws Exception {
        root(assembler, "C1");
        upward(assembler, through(policies, OWN_ROWS, Envelope.insert(2, "policy",
                row("policy_id", "P1", "customer_id", "C1", "note", "before"), null).withOrder(at(2))));
        upward(assembler, through(policies, CLAIMS, claim(3, "K1", "P1")));
        assertThat(claimsUnderTheOnlyPolicy("C1")).describedAs("the claim really is there first").hasSize(1);
    }

    private static Envelope policyRenamed() {
        return Envelope.update(7, "policy",
                row("policy_id", "P1", "customer_id", "C1", "note", "before"),
                row("policy_id", "P2", "customer_id", "C1", "note", "before"), null).withOrder(at(7));
    }

    private static Envelope claim(long seq, String claimId, String policyId) {
        return Envelope.insert(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }

    private ResolverProcessor policies() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(TOPOLOGY.vertexAt(List.of("policies")),
                policyState, (from, released) -> { }, null, null, ReplayFloor.NONE, NestClock.SYSTEM,
                NestSettings.defaults(), stores.forParking(TOPOLOGY.vertexAt(List.of("policies"))));
        processor.init(resolverOut, new TestProcessorContext());
        return processor;
    }

    private AssemblerProcessor assembler() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                documents, "doc", null, null, ReplayFloor.NONE, NestSettings.defaults(), NestClock.SYSTEM,
                NestSendPolicy.within(0), stores.forParking(TOPOLOGY.assembler()), (from, released) -> { });
        processor.init(assemblerOut, new TestProcessorContext());
        return processor;
    }

    private List<Object> through(ResolverProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        processor.tryProcess();
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
        processor.tryProcess();
        assemblerOut.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    private void root(AssemblerProcessor processor, String customerId) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(Envelope.insert(1, "customer", row("customer_id", customerId), null)
                .withOrder(at(1)));
        processor.process(OWN_ROWS, inbox);
        assemblerOut.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    /** The values the document shows its policies by, which is the array key it was compiled with. */
    private List<Object> shownKeysOf(String customerId) {
        return policiesOf(customerId).stream().map(policy -> policy.get("policy_id")).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> claimsUnderTheOnlyPolicy(String customerId) {
        List<Map<String, Object>> policies = policiesOf(customerId);
        if (policies.isEmpty()) {
            return List.of();
        }
        Object claims = policies.get(0).get("claims");
        return claims == null ? List.of() : (List<Map<String, Object>>) claims;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> policiesOf(String customerId) {
        RootAssembly assembly = documents.load(List.of(customerId));
        if (assembly == null) {
            return List.of();
        }
        Map<String, Object> document = assembly.render(TOPOLOGY.slots()).orElse(new LinkedHashMap<>());
        return (List<Map<String, Object>>) document.getOrDefault("policies", List.of());
    }
}
