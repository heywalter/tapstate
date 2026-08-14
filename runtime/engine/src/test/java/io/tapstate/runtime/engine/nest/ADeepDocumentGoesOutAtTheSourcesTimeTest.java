package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The time a document carries downstream when the change that touched it came up through a resolver.
 *
 * <p>A root row and a leaf embed both reach the assembler as envelopes, and the document is stamped from
 * the time they carry. A change beneath a level that has children of its own does not arrive that way: it
 * comes as an element on a cascade edge. Should the time not travel with it, the document goes out at the
 * epoch - placing the change downstream in 1970 rather than where the source put it - and the fold window
 * keeps that same value and re-sends it afterwards.
 */
class ADeepDocumentGoesOutAtTheSourcesTimeTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;
    private static final int FROM_POLICIES = 1;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
    private final TestOutbox resolverOut = new TestOutbox(256);
    private final TestOutbox assemblerOut = new TestOutbox(256);

    @Test
    void aChangeThatCameUpThroughAResolverKeepsTheTimeTheSourcePutOnIt() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor assembler = assembler();
        root(assembler, "C1");
        sent(assembler, through(policies, OWN_ROWS, policy(2, "P1", "C1")));

        List<Object> out = sent(assembler, through(policies, CLAIMS, claim(7, "K1", "P1")));

        assertThat(out).describedAs("the claim reaches the document it belongs to").isNotEmpty();
        assertThat(last(out).ts())
                .describedAs("the time the source put on the claim, not the epoch")
                .isEqualTo(7L);
    }

    /**
     * The discrimination that keeps the case above from passing on an implementation that stamps the
     * document with whatever it last saw on any edge: the root arrives late and at a different time, so a
     * document taking its time from the root rather than from the change that touched it reads as 9 here.
     */
    @Test
    void theTimeIsTheChangesOwnAndNotWhateverTouchedTheDocumentLast() throws Exception {
        ResolverProcessor policies = policies();
        AssemblerProcessor assembler = assembler();
        root(assembler, "C1");
        sent(assembler, through(policies, OWN_ROWS, policy(2, "P1", "C1")));
        sent(assembler, through(policies, CLAIMS, claim(7, "K1", "P1")));

        List<Object> out = sent(assembler, through(policies, CLAIMS, claim(5, "K2", "P1")));

        assertThat(out).isNotEmpty();
        assertThat(last(out).ts())
                .describedAs("the second claim's own time, even though an earlier one came before it")
                .isEqualTo(5L);
    }

    private static Envelope last(List<Object> out) {
        return (Envelope) out.get(out.size() - 1);
    }

    private ResolverProcessor policies() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(TOPOLOGY.vertexAt(List.of("policies")),
                new HeapNestStore<>(), (from, released) -> { });
        processor.init(resolverOut, new TestProcessorContext());
        return processor;
    }

    private AssemblerProcessor assembler() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                documents, "doc", null, null, ReplayFloor.NONE, NestSettings.defaults(), NestClock.SYSTEM,
                NestSendPolicy.within(0), stores.forParking(TOPOLOGY.assembler()));
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

    private List<Object> sent(AssemblerProcessor processor, List<Object> routed) {
        if (routed.isEmpty()) {
            return List.of();
        }
        TestInbox inbox = new TestInbox();
        inbox.queue().addAll(routed);
        processor.process(FROM_POLICIES, inbox);
        List<Object> out = new ArrayList<>();
        assemblerOut.drainQueueAndReset(0, out, false);
        return out;
    }

    private void root(AssemblerProcessor processor, String customerId) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(Envelope.insert(9, "customer", row("customer_id", customerId), null)
                .withOrder(at(1)));
        processor.process(OWN_ROWS, inbox);
        assemblerOut.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    private static Envelope policy(long seq, String policyId, String customerId) {
        return Envelope.insert(seq, "policy", row("policy_id", policyId, "customer_id", customerId,
                "policy_no", "PN-" + policyId), null).withOrder(at(seq));
    }

    private static Envelope claim(long seq, String claimId, String policyId) {
        return Envelope.insert(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }
}
