package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tracking;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.NestRoot;
import io.tapstate.core.model.TransformBody;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What asking for structural key changes to be followed costs the source: it has to say what the row was.
 *
 * <p>Without a before image the two updates that matter here are indistinguishable. A row that moved to
 * another parent and a row that merely had a column edited both arrive as an after image showing the row
 * where it now hangs, and nothing in either says it ever hung anywhere else. Followed blindly, the element
 * is written into its new place and left in the old one too, and the document disagrees with the source
 * with nothing to signal it. So a stream that tracks key changes stops the job when the image is missing,
 * and one that does not keeps running on whatever the source sends — which is the whole economics of the
 * switch: off means the source may run a minimal row image, on means it may not.
 *
 * <p>The check is on updates alone, and that is the discrimination worth having: an insert has no earlier
 * row by definition and a deletion carries one as its only row, so refusing either would be refusing the
 * shape of the event rather than the absence of the image.
 */
class AStreamThatTracksKeyChangesNeedsToSeeWhatTheRowWasTest {

    private static final TransformBody.Nest CLAIMS_TRACKED = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    tracking(embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims",
                            List.of("claim_id")))));

    private static final TransformBody.Nest POLICIES_TRACKED = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final TransformBody.Nest NOTHING_TRACKED = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final TransformBody.Nest ROOT_TRACKED = nest(new NestRoot("customer",
            List.of("customer_id"), null, true, List.of(
                    embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                            List.of("policy_no")))));

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;

    private static final NestDeadLetter UNUSED = (from, released) -> {
        throw new AssertionError("a missing before image is not something to dead-letter");
    };

    @Test
    void anUpdateWithNoBeforeImageFailsTheJobNamingTheStreamAndItsTable() throws Exception {
        ResolverProcessor policies = resolver(CLAIMS_TRACKED);

        assertThatThrownBy(() -> feed(policies, CLAIMS, claimUpdateWithNoBefore(1, "k1", "p1")))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.key-change-tracking-requires-before-image");
    }

    /**
     * The alias is what the author wrote and the table is what a DBA has to go and reconfigure. Naming only
     * one of them leaves whoever reads the failure to guess the other.
     */
    @Test
    void theFailureNamesBothWhatWasWrittenAndWhatHasToBeReconfigured() throws Exception {
        ResolverProcessor policies = resolver(CLAIMS_TRACKED);

        assertThatThrownBy(() -> feed(policies, CLAIMS, claimUpdateWithNoBefore(1, "k1", "p1")))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).args())
                .isEqualTo(Map.of("alias", "claim", "table", "claims"));
    }

    /** The vertex's own rows are a stream like any other, and the switch on that embed covers them. */
    @Test
    void theSwitchCoversTheVertexsOwnRowsAndNotOnlyTheOnesFromBeneath() throws Exception {
        ResolverProcessor policies = resolver(POLICIES_TRACKED);

        assertThatThrownBy(() -> feed(policies, OWN_ROWS, policyUpdateWithNoBefore(1, "p1", "c1")))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.key-change-tracking-requires-before-image");
    }

    /** The root has the same switch, and its own key can move exactly as an embed's can. */
    @Test
    void theRootsOwnRowsAreCoveredByTheSwitchOnTheRoot() throws Exception {
        AssemblerProcessor assembler = assembler(ROOT_TRACKED);

        assertThatThrownBy(() -> feed(assembler, OWN_ROWS, customerUpdateWithNoBefore(1, "c1")))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.key-change-tracking-requires-before-image");
    }

    /**
     * The other half of the economics, and the reason this is a switch rather than a requirement: a tree
     * that does not track key changes runs on a source that sends no before image at all.
     */
    @Test
    void aStreamThatTracksNothingRunsOnAnUpdateWithNoBeforeImage() throws Exception {
        ResolverProcessor policies = resolver(NOTHING_TRACKED);

        assertThatCode(() -> feed(policies, CLAIMS, claimUpdateWithNoBefore(1, "k1", "p1")))
                .doesNotThrowAnyException();
    }

    /** Tracking one embed says nothing about its siblings or its parent — the switch is per embed. */
    @Test
    void trackingOneEmbedLeavesTheOthersOnWhateverTheirSourceSends() throws Exception {
        ResolverProcessor policies = resolver(CLAIMS_TRACKED);

        assertThatCode(() -> feed(policies, OWN_ROWS, policyUpdateWithNoBefore(1, "p1", "c1")))
                .describedAs("claims are tracked, policies are not")
                .doesNotThrowAnyException();
    }

    @Test
    void anInsertNeedsNoBeforeImageWhereverKeysAreTracked() throws Exception {
        ResolverProcessor policies = resolver(CLAIMS_TRACKED);

        assertThatCode(() -> feed(policies, CLAIMS, claimInsert(1, "k1", "p1")))
                .doesNotThrowAnyException();
    }

    @Test
    void aDeletionCarriesTheOnlyRowItHasAndIsNotRefusedForIt() throws Exception {
        ResolverProcessor policies = resolver(CLAIMS_TRACKED);

        assertThatCode(() -> feed(policies, CLAIMS, claimDelete(1, "k1", "p1")))
                .doesNotThrowAnyException();
    }

    /**
     * A tracked stream reaches its vertex on two edges, and the second carries every row keyed by what it
     * is leaving. For a vertex's <em>own</em> rows there is nothing to do with that copy yet, and doing the
     * ordinary thing with it would be worse than nothing: it was routed by the identity the row is leaving,
     * while everything read off the row names the identity it now has, so it would file a mapping under a
     * key this instance does not hold - writing across instances, which is the whole failure the second
     * edge exists to remove.
     *
     * <p>Asserted as "nothing came out" because that is the observable half of "nothing was written". A
     * copy that had been handled would have travelled on as an element, exactly as its twin did.
     */
    @Test
    void aVertexsOwnRowArrivingOnTheDepartureEdgeIsNotHandledTwice() throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", POLICIES_TRACKED, tables());
        NestVertex policies = topology.vertexAt(List.of("policies"));
        int twin = departureTwinOf(policies, policies.pathId());
        ResolverProcessor processor = new ResolverProcessor(policies, new HeapNestStore<>(), UNUSED);
        TestOutbox outbox = new TestOutbox(256);
        processor.init(outbox, new TestProcessorContext());

        TestInbox inbox = new TestInbox();
        inbox.queue().add(policyUpdateMoved(1, "P1", "C1", "C2"));
        processor.process(twin, inbox);

        List<Object> routed = new ArrayList<>();
        outbox.drainQueueAndReset(0, routed, false);
        assertThat(routed)
                .describedAs("this copy belongs to the identity being vacated, which nothing handles yet")
                .isEmpty();
    }

    private static int departureTwinOf(NestVertex vertex, List<String> pathId) {
        for (NestInbound edge : vertex.inbound()) {
            if (edge.carriesDepartures() && edge.pathId().equals(pathId)) {
                return edge.ordinal();
            }
        }
        throw new AssertionError("no departure edge carries " + pathId);
    }

    private static Envelope policyUpdateMoved(long seq, String policyId, String was, String is) {
        return Envelope.update(seq, "policy",
                row("policy_id", policyId, "customer_id", was, "policy_no", "PN-" + policyId),
                row("policy_id", policyId, "customer_id", is, "policy_no", "PN-" + policyId), null)
                .withOrder(at(seq));
    }

    /**
     * What stops the whole of this from being unreachable: the switch has to travel from what the author
     * wrote to the edge the processor reads. Every case above builds its own tree, so a compiler that never
     * looked at the switch would leave them all passing and the deployment running unguarded.
     */
    @Test
    void theEdgesCarryTheSwitchTheAuthorWroteAndNoOthers() {
        NestTopology tracked = NestTopology.compile("p", "doc", CLAIMS_TRACKED, tables());
        NestVertex policies = tracked.vertexAt(List.of("policies"));

        assertThat(policies.inbound().get(CLAIMS).tracksKeyChanges())
                .describedAs("the embed the author marked")
                .isTrue();
        assertThat(policies.inbound().get(OWN_ROWS).tracksKeyChanges())
                .describedAs("its parent, which the author did not mark")
                .isFalse();
        assertThat(tracked.assembler().inbound().get(OWN_ROWS).tracksKeyChanges())
                .describedAs("the root, which the author did not mark")
                .isFalse();
    }

    /**
     * A cascading edge carries changes another vertex already routed off rows it has already seen. The row
     * itself never arrives here, so there is no before image to ask for and nothing to compare.
     */
    @Test
    void aCascadingEdgeTracksNothingBecauseNoRowArrivesOnIt() {
        NestTopology tracked = NestTopology.compile("p", "doc", POLICIES_TRACKED, tables());

        NestInbound cascade = tracked.assembler().inboundFor(List.of("policies"));
        assertThat(cascade.isCascade()).isTrue();
        assertThat(cascade.tracksKeyChanges()).isFalse();
    }

    private static ResolverProcessor resolver(TransformBody.Nest tree) throws Exception {
        NestVertex vertex = NestTopology.compile("p", "doc", tree, tables()).vertexAt(List.of("policies"));
        ResolverProcessor processor = new ResolverProcessor(vertex, new HeapNestStore<>(), UNUSED);
        processor.init(new TestOutbox(256), new TestProcessorContext());
        return processor;
    }

    private static AssemblerProcessor assembler(TransformBody.Nest tree) throws Exception {
        NestTopology topology = NestTopology.compile("p", "doc", tree, tables());
        AssemblerProcessor processor = new AssemblerProcessor(topology.assembler(), topology.slots(),
                new HeapNestStore<>(), "doc", NestSettings.defaults());
        processor.init(new TestOutbox(256), new TestProcessorContext());
        return processor;
    }

    private static void feed(ResolverProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
    }

    private static void feed(AssemblerProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
    }

    private static Envelope claimUpdateWithNoBefore(long seq, String claimId, String policyId) {
        return Envelope.update(seq, "claim", null,
                row("claim_id", claimId, "policy_id", policyId), null).withOrder(at(seq));
    }

    private static Envelope claimInsert(long seq, String claimId, String policyId) {
        return Envelope.insert(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }

    private static Envelope claimDelete(long seq, String claimId, String policyId) {
        return Envelope.delete(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }

    private static Envelope policyUpdateWithNoBefore(long seq, String policyId, String customerId) {
        return Envelope.update(seq, "policy", null, row("policy_id", policyId,
                "customer_id", customerId, "policy_no", "PN-" + policyId), null).withOrder(at(seq));
    }

    private static Envelope customerUpdateWithNoBefore(long seq, String customerId) {
        return Envelope.update(seq, "customer", null,
                row("customer_id", customerId, "name", "n" + customerId), null).withOrder(at(seq));
    }
}
