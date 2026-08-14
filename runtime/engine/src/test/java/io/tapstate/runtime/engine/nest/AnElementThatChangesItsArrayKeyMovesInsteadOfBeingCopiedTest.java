package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The first of the structural key changes: the value a document shows an element by is edited, and the
 * element has to end up in its new slot rather than in both.
 *
 * <p>Nothing else in the tree moves for this. Which document the element belongs to is decided by the key
 * it joins on, and that key is untouched here, so the element is provably still in the same document -
 * which is what makes this the one family that is settled inside a single assembly, with no batches and no
 * parking anywhere.
 *
 * <p>The pair of cases either side of the switch is the whole point. Turned off, the change arrives as an
 * ordinary edit and the document ends up holding both the row as it was and the row as it is - a ghost
 * element that the source has no counterpart for. That is not a defect being demonstrated: it is the
 * accepted price of not requiring a before image, and it is what the switch buys off. A test that only
 * pinned the tracked half would pass just as well against an implementation that quietly moved everything,
 * which would take that choice away from whoever declined to pay for it.
 */
class AnElementThatChangesItsArrayKeyMovesInsteadOfBeingCopiedTest {

    private static final TransformBody.Nest TRACKED = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"))));

    private static final TransformBody.Nest UNTRACKED = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no")));

    /** A tree with something hanging beneath the element that moves, for the subtree case. */
    private static final TransformBody.Nest DEEP = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final int ROOT_ROWS = 0;
    private static final int POLICIES = 1;

    private TestOutbox outbox;

    @Test
    void anElementWhoseShownKeyChangesEndsUpUnderTheNewOneAlone() throws Exception {
        AssemblerProcessor assembler = assembler(TRACKED);
        feed(assembler, ROOT_ROWS, customer(1, "C1"));
        feed(assembler, POLICIES, policyInsert(2, "P1", "C1", "PN-1"));

        List<Envelope> out = feed(assembler, POLICIES,
                policyUpdate(3, "P1", "C1", "PN-1", "PN-2"));

        List<Map<String, Object>> policies = arrayAt(last(out), "policies");
        assertThat(policies)
                .describedAs("one element, not the old one and the new one")
                .hasSize(1);
        assertThat(policies.get(0).get("policy_no")).isEqualTo("PN-2");
    }

    /**
     * The other side of the switch, and the reason it is a switch: without a before image the change is an
     * ordinary edit of a row that happens to sit under a key nobody claimed was stable, so the old element
     * stays where it was and the new one is added beside it.
     */
    @Test
    void anElementWhoseShownKeyChangesUntrackedIsLeftInBothPlaces() throws Exception {
        AssemblerProcessor assembler = assembler(UNTRACKED);
        feed(assembler, ROOT_ROWS, customer(1, "C1"));
        feed(assembler, POLICIES, policyInsert(2, "P1", "C1", "PN-1"));

        List<Envelope> out = feed(assembler, POLICIES,
                policyUpdate(3, "P1", "C1", "PN-1", "PN-2"));

        assertThat(arrayAt(last(out), "policies"))
                .describedAs("the ghost element the switch buys off, kept deliberately")
                .hasSize(2);
    }

    /**
     * The discrimination that stops "a move" from being read as "delete the old, insert the new": what
     * hangs beneath the element travels with it. Deleting and re-inserting would strand the subtree under a
     * record of a deletion, and nothing will ever resend those rows.
     */
    @Test
    void whatHangsBeneathAMovedElementTravelsWithIt() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.take(new NestElement(element(List.of("policies"), null, "PN-1", "P1"),
                row("policy_no", "PN-1"), at(2), noPositions()));
        assembly.take(new NestElement(element(List.of("policies", "claims"), "P1", "K1", null),
                row("claim_id", "K1"), at(3), noPositions()));

        assembly.take(new NestElement(element(List.of("policies"), null, "PN-2", "P1"),
                row("policy_no", "PN-2"), at(4), noPositions(),
                element(List.of("policies"), null, "PN-1", "P1")));

        Map<String, Object> document = assembly.render(slotsOf(DEEP)).orElseThrow();
        List<Map<String, Object>> policies = NestFixtures.listAt(document, "policies");
        assertThat(policies).hasSize(1);
        assertThat(policies.get(0).get("policy_no")).isEqualTo("PN-2");
        assertThat(NestFixtures.listAt(document, "policies", "claims"))
                .describedAs("the claim hung under the element, and the element moved")
                .hasSize(1);
    }

    /** An edit that leaves every key alone is not a move, and must not churn the element it names. */
    @Test
    void anUpdateThatLeavesTheKeysAloneIsAnOrdinaryEdit() throws Exception {
        AssemblerProcessor assembler = assembler(TRACKED);
        feed(assembler, ROOT_ROWS, customer(1, "C1"));
        feed(assembler, POLICIES, policyInsert(2, "P1", "C1", "PN-1"));

        List<Envelope> out = feed(assembler, POLICIES, Envelope.update(3, "policy",
                row("policy_id", "P1", "customer_id", "C1", "policy_no", "PN-1", "tier", "silver"),
                row("policy_id", "P1", "customer_id", "C1", "policy_no", "PN-1", "tier", "gold"), null)
                .withOrder(at(3)));

        List<Map<String, Object>> policies = arrayAt(last(out), "policies");
        assertThat(policies).hasSize(1);
        assertThat(policies.get(0).get("tier")).isEqualTo("gold");
        assertThat(policies.get(0).get("policy_no")).isEqualTo("PN-1");
    }

    private AssemblerProcessor assembler(TransformBody.Nest tree) throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", tree, tables());
        AssemblerProcessor processor = new AssemblerProcessor(topology.assembler(), topology.slots(),
                new HeapNestStore<>(), "doc");
        outbox = new TestOutbox(256);
        processor.init(outbox, new TestProcessorContext());
        return processor;
    }

    private static List<EmbedSlot> slotsOf(TransformBody.Nest tree) {
        return NestTopology.compile("p", "doc", tree, tables()).slots();
    }

    private List<Envelope> feed(AssemblerProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
        List<Object> drained = new ArrayList<>();
        outbox.drainQueueAndReset(0, drained, false);
        return drained.stream().map(Envelope.class::cast).toList();
    }

    private static Envelope last(List<Envelope> documents) {
        assertThat(documents).describedAs("a document went out for the change").isNotEmpty();
        return documents.get(documents.size() - 1);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> arrayAt(Envelope document, String field) {
        return (List<Map<String, Object>>) document.after().get(field);
    }

    private static Envelope customer(long seq, String id) {
        return Envelope.insert(seq, "customer", row("customer_id", id, "name", "n" + id), null)
                .withOrder(at(seq));
    }

    private static Envelope policyInsert(long seq, String policyId, String customerId, String policyNo) {
        return Envelope.insert(seq, "policy",
                row("policy_id", policyId, "customer_id", customerId, "policy_no", policyNo), null)
                .withOrder(at(seq));
    }

    private static Envelope policyUpdate(long seq, String policyId, String customerId, String was, String is) {
        return Envelope.update(seq, "policy",
                row("policy_id", policyId, "customer_id", customerId, "policy_no", was),
                row("policy_id", policyId, "customer_id", customerId, "policy_no", is), null)
                .withOrder(at(seq));
    }
}
