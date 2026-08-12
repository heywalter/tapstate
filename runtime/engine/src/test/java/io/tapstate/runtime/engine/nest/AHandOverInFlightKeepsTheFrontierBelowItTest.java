package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.jet.core.Watermark;
import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ChainAxes;
import io.tapstate.runtime.engine.FrontierOrders;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A subtree parked between documents has to keep the durable frontier below the change that started the
 * move, for as long as it sits there.
 *
 * <p>It is the same shape as a document a window is holding back and it fails the same way, but it is a
 * separate exclusion because the reason differs. A folded document is somewhere durable and simply has
 * nobody left to send it; a parked subtree is in <em>no document at all</em> - the one that had those rows
 * has let them go, and the one that will have them has not taken them. Let the frontier past and a restart
 * resumes above the change that moved the element: nothing replays the move, nothing collects what was
 * parked, and the rows are gone from both documents with the job RUNNING and no error counted anywhere.
 *
 * <p>The pair of tests is what makes this mean anything. Holding the bound down forever would be trivially
 * "safe" and would pin the frontier on every move ever made, so the second test is the one that says the
 * hold is released - and released by the hand-over landing, not by time passing.
 */
class AHandOverInFlightKeepsTheFrontierBelowItTest {

    private static final String CUSTOMERS = "customer";
    private static final String POLICIES = "policy";
    private static final ChainAxes AXES = ChainAxes.assign(List.of(CUSTOMERS, POLICIES));

    private static final int ROOT_ROWS = 0;
    private static final int FROM_POLICIES = 1;

    private static final Map<Integer, List<String>> CHAINS =
            Map.of(ROOT_ROWS, List.of(CUSTOMERS), FROM_POLICIES, List.of(POLICIES));

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    /** Far above anything that arrives, so a level passing its edge's bound straight on is unmistakable. */
    private static final long FAR_ABOVE = FrontierOrders.pack(POLICIES, new SourceOrder(1, 900));

    /** Where the move is read as having happened, and the position the hold has to stay below. */
    private static final long MOVED_AT = 200L;

    private final HeapNestStore<RootAssembly> store = new HeapNestStore<>();
    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final TestOutbox outbox = new TestOutbox(128);

    @Test
    void theBoundStaysBelowAMoveWhoseSubtreeIsStillParked() throws Exception {
        AssemblerProcessor processor = assembler();
        givenAPolicyWithAClaimUnder("C1", processor);

        feed(processor, FROM_POLICIES, departureOfP1From("C1"));

        assertThat(boundsAfter(processor, FAR_ABOVE))
                .describedAs("those rows are in no document while they sit parked; a frontier past the "
                        + "move means a restart resumes above it, nothing replays it, and the subtree is "
                        + "gone from both documents with the job RUNNING")
                .containsExactly(FrontierOrders.pack(POLICIES, at(MOVED_AT)) - 1);
    }

    @Test
    void theBoundGoesPastOnceTheDocumentThatWasOwedItHasTakenIt() throws Exception {
        AssemblerProcessor processor = assembler();
        givenAPolicyWithAClaimUnder("C1", processor);
        feed(processor, ROOT_ROWS, customer(1, "C2"));
        feed(processor, FROM_POLICIES, departureOfP1From("C1"));
        boundsAfter(processor, FAR_ABOVE);

        feed(processor, FROM_POLICIES, arrivalOfP1At("C2"));

        assertThat(boundsAfter(processor, FAR_ABOVE + 1))
                .describedAs("the hold is released by the hand-over landing, not by time passing")
                .containsExactly(FAR_ABOVE + 1);
    }

    private void givenAPolicyWithAClaimUnder(String customerId, AssemblerProcessor processor) {
        feed(processor, ROOT_ROWS, customer(1, customerId));
        feed(processor, FROM_POLICIES, policyElement(2, customerId));
        feed(processor, FROM_POLICIES, claimElement(3, customerId));
    }

    private AssemblerProcessor assembler() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(), store,
                "doc", AXES, CHAINS, ReplayFloor.NONE, NestSettings.defaults(), NestClock.SYSTEM,
                NestSendPolicy.within(0), stores.forParking(TOPOLOGY.assembler()));
        processor.init(outbox, new TestProcessorContext());
        return processor;
    }

    private static SourceOrder at(long seq) {
        return new SourceOrder(1L, seq);
    }

    private static ElementRef policyRef() {
        return new ElementRef(List.of("policies"), null, List.of("PN-P1"), List.of("P1"));
    }

    private static Envelope customer(long seq, String id) {
        return Envelope.insert(seq, "customer", row("customer_id", id), null).withOrder(at(seq));
    }

    private static KeyedElement policyElement(long seq, String customerId) {
        return new KeyedElement(List.of(customerId), new NestElement(policyRef(),
                row("policy_id", "P1", "policy_no", "PN-P1"), at(seq),
                Map.of(POLICIES, new ChainPosition(at(seq), null))));
    }

    private static KeyedElement claimElement(long seq, String customerId) {
        ElementRef ref = new ElementRef(List.of("policies", "claims"), List.of("P1"), List.of("CL1"), null);
        return new KeyedElement(List.of(customerId), new NestElement(ref,
                row("claim_id", "CL1", "policy_id", "P1"), at(seq),
                Map.of(POLICIES, new ChainPosition(at(seq), null))));
    }

    /**
     * The half of the move that stays behind, as the policies resolver would have routed it. Both halves say
     * a departure was sent, because one was: that is what tells the arriving side it may be owed a subtree,
     * and an element merely renamed inside its own document says the opposite.
     */
    private static KeyedElement departureOfP1From(String customerId) {
        return new KeyedElement(List.of(customerId), new NestElement(policyRef(), null, at(MOVED_AT),
                Map.of(POLICIES, new ChainPosition(at(MOVED_AT), null)), policyRef(), true));
    }

    /** The half that arrives, which is what collects whatever was parked for the element. */
    private static KeyedElement arrivalOfP1At(String customerId) {
        return new KeyedElement(List.of(customerId), new NestElement(policyRef(),
                row("policy_id", "P1", "policy_no", "PN-P1"), at(MOVED_AT),
                Map.of(POLICIES, new ChainPosition(at(MOVED_AT), null)), policyRef(), true));
    }

    private void feed(AssemblerProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
        drained();
    }

    private List<Long> boundsAfter(AssemblerProcessor processor, long arriving) {
        processor.tryProcessWatermark(FROM_POLICIES, new Watermark(arriving, AXES.axisOf(POLICIES)));
        List<Long> bounds = new ArrayList<>();
        for (Object item : drained()) {
            if (item instanceof Watermark bound) {
                bounds.add(bound.timestamp());
            }
        }
        return bounds;
    }

    private List<Object> drained() {
        List<Object> out = new ArrayList<>();
        outbox.drainQueueAndReset(0, out, false);
        return out;
    }
}
