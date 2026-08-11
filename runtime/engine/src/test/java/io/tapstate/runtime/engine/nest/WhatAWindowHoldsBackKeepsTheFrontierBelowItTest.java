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
 * A change the window is holding back has to keep the durable frontier below it. The frontier says
 * everything at or below it is either downstream or somewhere a restart brings it back from - and a
 * document folded into an open window is neither of those two things in the one way that matters: the
 * state carrying it is written through and so survives, but <em>nothing will ever trigger that send
 * again</em>. No event is coming for that root; a restart replays from above the change; the document on
 * the sink stays at its previous version for as long as the pipeline runs, with the job RUNNING and no
 * error counted anywhere.
 *
 * <p>That is why this is asserted here and not left to the frontier's own tests: durable and
 * <em>someone will finish it</em> are two different properties, and folding is the one place a change has
 * the first without the second. Everything else this level holds has both - an element under a root that
 * has not arrived is written through with the root's state and comes out when the root does - and the last
 * test is the control that keeps this exclusion that narrow. Widening it to "anything held" would pin the
 * frontier on a dangling foreign key for as long as the job runs, which is the failure the durable state
 * was meant to remove.
 */
class WhatAWindowHoldsBackKeepsTheFrontierBelowItTest {

    private static final long WINDOW = 50L;

    /** The chains are named for the streams that carry them, which is where a position is stamped. */
    private static final String CUSTOMERS = "customer";
    private static final String POLICIES = "policy";
    private static final ChainAxes AXES = ChainAxes.assign(List.of(CUSTOMERS, POLICIES));

    private static final int ROOT_ROWS = 0;
    private static final int FROM_POLICIES = 1;

    /** Which chain each of the assembler's edges is compiled to carry. */
    private static final Map<Integer, List<String>> CHAINS =
            Map.of(ROOT_ROWS, List.of(CUSTOMERS), FROM_POLICIES, List.of(POLICIES));

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    /** Far above anything that arrives, so a level passing its edge's bound straight on is unmistakable. */
    private static final long FAR_ABOVE = FrontierOrders.pack(POLICIES, new SourceOrder(1, 900));

    private final Ticking clock = new Ticking();
    private final HeapNestStore<RootAssembly> store = new HeapNestStore<>();
    private final TestOutbox outbox = new TestOutbox(128);

    private static final class Ticking implements NestClock {

        private static final long serialVersionUID = 1L;

        private long now = 1_000L;

        @Override
        public long millis() {
            return now;
        }

        void advance(long millis) {
            now += millis;
        }
    }

    private AssemblerProcessor throttled() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(), store,
                "doc", AXES, CHAINS, ReplayFloor.NONE, NestSettings.defaults(), clock,
                NestSendPolicy.within(WINDOW));
        processor.init(outbox, new TestProcessorContext());
        return processor;
    }

    private static SourceOrder at(long seq) {
        return new SourceOrder(1L, seq);
    }

    private static Envelope customer(long seq, String id) {
        return Envelope.insert(seq, "customer", row("customer_id", id, "name", "Ada"), null).withOrder(at(seq));
    }

    private static KeyedElement policyElement(long seq, String customerId, String policyId) {
        ElementRef ref = new ElementRef(List.of("policies"), null, List.of("PN-" + policyId), List.of(policyId));
        return new KeyedElement(List.of(customerId),
                new NestElement(ref, row("policy_id", policyId, "policy_no", "PN-" + policyId), at(seq),
                        Map.of(POLICIES, new ChainPosition(at(seq), null))));
    }

    /** A claim that hangs under a policy which has not arrived: held for an ancestor, not by a window. */
    private static KeyedElement claimWaitingForItsPolicy(long seq, String customerId, String policyId) {
        ElementRef ref = new ElementRef(List.of("policies", "claims"), List.of(policyId), List.of("CL1"), null);
        return new KeyedElement(List.of(customerId),
                new NestElement(ref, row("claim_id", "CL1", "policy_id", policyId), at(seq),
                        Map.of(POLICIES, new ChainPosition(at(seq), null))));
    }

    private void feed(AssemblerProcessor processor, int ordinal, Object... items) {
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(Arrays.asList(items));
        processor.process(ordinal, inbox);
        drained();
    }

    /** What this level promises on the policies chain after a bound far above it arrives on that edge. */
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

    @Test
    void theBoundStaysBelowADocumentAWindowIsHoldingBack() throws Exception {
        AssemblerProcessor processor = throttled();
        feed(processor, ROOT_ROWS, customer(1, "C1"));
        clock.advance(1);
        feed(processor, FROM_POLICIES, policyElement(200, "C1", "P1"));

        assertThat(boundsAfter(processor, FAR_ABOVE))
                .describedAs("letting the bound past a folded change lets the source's read offset past it "
                        + "too; after a restart nothing replays it and nothing else will ever send that "
                        + "document, so it stays at its previous version with the job RUNNING")
                .containsExactly(FrontierOrders.pack(POLICIES, at(200)) - 1);
    }

    @Test
    void theBoundGoesPastAsSoonAsTheWindowLetsTheDocumentGo() throws Exception {
        AssemblerProcessor processor = throttled();
        feed(processor, ROOT_ROWS, customer(1, "C1"));
        clock.advance(1);
        feed(processor, FROM_POLICIES, policyElement(200, "C1", "P1"));
        boundsAfter(processor, FAR_ABOVE);

        clock.advance(WINDOW);
        processor.tryProcess();
        drained();

        assertThat(boundsAfter(processor, FAR_ABOVE + 1))
                .describedAs("the change is downstream now, so what held the bound down is gone - a window "
                        + "that kept holding it would pin the frontier at the rate documents change")
                .containsExactly(FAR_ABOVE + 1);
    }

    @Test
    void anElementWaitingForItsAncestorDoesNotHoldTheBoundBack() throws Exception {
        AssemblerProcessor processor = throttled();
        feed(processor, ROOT_ROWS, customer(1, "C1"));
        clock.advance(1);
        // Same moment in the same open window as the test above, and the opposite answer: what decides is
        // which of the two properties the change has, not whether a window happens to be open over it.
        feed(processor, FROM_POLICIES, claimWaitingForItsPolicy(200, "C1", "P1"));

        assertThat(boundsAfter(processor, FAR_ABOVE))
                .describedAs("this one is durable and something does finish it: it is written through with "
                        + "the root's own state and comes out when its ancestor arrives. Holding the bound "
                        + "for it would pin the frontier on a dangling foreign key for the life of the job")
                .containsExactly(FAR_ABOVE);
    }
}
