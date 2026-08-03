package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.Op;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The vertex that holds whole documents. The tree is a customer with policies (each with claims) and a
 * profile object, so the assembler sees three kinds of arrival: its own root rows on ordinal 0, elements
 * cascading up from the policies resolver on ordinal 1, and profile rows arriving directly on ordinal 2
 * because a leaf hanging off the root has no resolver to pass through.
 *
 * <p>What goes out is the document as it stands, not the change that got it there.
 */
class AssemblerProcessorTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))),
            embed("profile", "customer_id", "customer_id", EmbedAs.OBJECT, "profile", List.of("customer_id")));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int ROOT_ROWS = 0;
    private static final int FROM_POLICIES = 1;
    private static final int PROFILE = 2;

    private final HeapNestStore<RootAssembly> store = new HeapNestStore<>();
    private final AssemblerProcessor processor =
            new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(), store, "doc");
    private final TestOutbox outbox = new TestOutbox(128);

    @BeforeEach
    void init() throws Exception {
        processor.init(outbox, new TestProcessorContext());
    }

    private static SourceOrder at(long seq) {
        return new SourceOrder(1L, seq);
    }

    private static Envelope customer(long seq, String id, String name) {
        return Envelope.insert(seq, "customer", row("customer_id", id, "name", name), null).withOrder(at(seq));
    }

    private static Envelope profileRow(long seq, String customerId, String tier) {
        return Envelope.insert(seq, "profile", row("customer_id", customerId, "tier", tier), null)
                .withOrder(at(seq));
    }

    /** A policy element as the policies resolver would have routed it: keyed by the customer it hangs from. */
    private static KeyedElement policyElement(long seq, String customerId, String policyId) {
        ElementRef ref = new ElementRef(List.of("policies"), null, List.of("PN-" + policyId), List.of(policyId));
        return new KeyedElement(List.of(customerId),
                new NestElement(ref, row("policy_id", policyId, "policy_no", "PN-" + policyId), at(seq),
                        Map.of("policy", new ChainPosition(at(seq), null))));
    }

    private List<Envelope> feed(int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
        List<Object> drained = new ArrayList<>();
        outbox.drainQueueAndReset(0, drained, false);
        return drained.stream().map(Envelope.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> arrayAt(Envelope document, String field) {
        return (List<Map<String, Object>>) document.after().get(field);
    }

    @Test
    void aRootRowOnItsOwnIsAlreadyADocument() {
        List<Envelope> out = feed(ROOT_ROWS, customer(1, "C1", "Ada"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).src()).isEqualTo("doc");
        assertThat(out.get(0).after()).containsEntry("customer_id", "C1").containsEntry("name", "Ada");
        assertThat(arrayAt(out.get(0), "policies"))
                .describedAs("an array embed with nothing in it renders empty rather than missing")
                .isEmpty();
        assertThat(out.get(0).after())
                .describedAs("an object embed with nothing in it omits its field rather than rendering null")
                .doesNotContainKey("profile");
    }

    @Test
    void anAssembledDocumentGoesOutAsAWholeRowAnUpsertCanApply() {
        List<Envelope> out = feed(ROOT_ROWS, customer(1, "C1", "Ada"));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).op())
                .describedAs("the resend unit is the whole document and a sink applies it by upserting; a "
                        + "change offering no before image is the one shape such a sink cannot apply, and it "
                        + "matches nothing rather than failing, so nothing is written and nothing is reported")
                .isEqualTo(Op.INSERT);
        assertThat(out.get(0).before())
                .describedAs("there is no before image to give: what goes out is the state, not the change")
                .isNull();
    }

    @Test
    void anElementArrivingBeforeItsRootProducesNoDocumentAtAll() {
        assertThat(feed(FROM_POLICIES, policyElement(2, "C1", "P1")))
                .describedAs("a document with no root fields is a ghost that nothing later removes")
                .isEmpty();

        List<Envelope> out = feed(ROOT_ROWS, customer(3, "C1", "Ada"));

        assertThat(out).hasSize(1);
        assertThat(arrayAt(out.get(0), "policies"))
                .describedAs("the element was kept and appears as soon as there is a document to put it in")
                .hasSize(1);
    }

    @Test
    void anElementLandsWhereItsPathSaysItBelongs() {
        feed(ROOT_ROWS, customer(1, "C1", "Ada"));

        List<Envelope> out = feed(FROM_POLICIES, policyElement(2, "C1", "P1"));

        assertThat(arrayAt(out.get(0), "policies")).hasSize(1);
        assertThat(arrayAt(out.get(0), "policies").get(0)).containsEntry("policy_no", "PN-P1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectAt(Envelope document, String field) {
        return (Map<String, Object>) document.after().get(field);
    }

    @Test
    void aLeafHangingOffTheRootArrivesDirectlyAndStillLands() {
        feed(ROOT_ROWS, customer(1, "C1", "Ada"));

        List<Envelope> out = feed(PROFILE, profileRow(2, "C1", "gold"));

        assertThat(out).hasSize(1);
        assertThat(objectAt(out.get(0), "profile")).containsEntry("tier", "gold");
    }

    @Test
    void rendersOneDocumentPerDrainHoweverManyElementsTouchedIt() {
        feed(ROOT_ROWS, customer(1, "C1", "Ada"));

        List<Envelope> out = feed(FROM_POLICIES,
                policyElement(2, "C1", "P1"), policyElement(3, "C1", "P2"), policyElement(4, "C1", "P3"));

        assertThat(out)
                .describedAs("three elements of one document render it once, not three times")
                .hasSize(1);
        assertThat(arrayAt(out.get(0), "policies")).hasSize(3);
    }

    @Test
    void twoDocumentsTouchedInOneDrainEachComeOutOnce() {
        feed(ROOT_ROWS, customer(1, "C1", "Ada"), customer(2, "C2", "Grace"));

        List<Envelope> out = feed(FROM_POLICIES, policyElement(3, "C1", "P1"), policyElement(4, "C2", "P2"));

        assertThat(out).hasSize(2);
        assertThat(out).extracting(document -> document.after().get("customer_id"))
                .containsExactly("C1", "C2");
    }

    @Test
    void aDeletedRootGoesOutAsADeletionCarryingOnlyItsKey() {
        feed(ROOT_ROWS, customer(1, "C1", "Ada"));

        Envelope deletion = Envelope.delete(5, "customer", row("customer_id", "C1", "name", "Ada"), null)
                .withOrder(at(5));
        List<Envelope> out = feed(ROOT_ROWS, deletion);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).op())
                .describedAs("the sink has a document to remove, so this one thing still goes out")
                .isEqualTo(Op.DELETE);
        assertThat(out.get(0).before()).containsExactly(Map.entry("customer_id", "C1"));
        assertThat(out.get(0).after()).isNull();
    }

    @Test
    void anElementOfANeverSeenRootStillProducesNoDeletion() {
        assertThat(feed(FROM_POLICIES, policyElement(2, "C9", "P9")))
                .describedAs("nothing was ever emitted for it, so there is nothing to tell a sink to remove")
                .isEmpty();
    }

    @Test
    void isNotCooperativeBecauseItsStoreMayBlock() {
        assertThat(processor.isCooperative()).isFalse();
    }

    @Test
    void refusesToBeBuiltOnAResolverVertex() {
        NestVertex resolver = TOPOLOGY.vertexAt(List.of("policies"));

        assertThatThrownBy(() -> new AssemblerProcessor(resolver, TOPOLOGY.slots(), store, "doc"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
