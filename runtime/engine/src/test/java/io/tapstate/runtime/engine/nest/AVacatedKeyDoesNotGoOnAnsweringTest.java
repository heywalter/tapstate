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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a key that has been vacated does with a child that still names it.
 *
 * <p>A row whose children-facing key changes leaves the value it had answering to nothing: no row carries
 * it any more. The mapping this level held under that value is not moved with the row - the row declares
 * itself again under the value it now has - so what happens to a child arriving with the old value
 * afterwards is decided by whether the old entry still holds a mapping.
 */
class AVacatedKeyDoesNotGoOnAnsweringTest {

    private static final TransformBody.Nest TRACKED = nest("customer", List.of("customer_id"),
            tracking(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TRACKED, tables());
    private static final NestVertex POLICIES = TOPOLOGY.vertexAt(List.of("policies"));

    private static final int OWN_ROWS = 0;
    private static final int CLAIMS = 1;
    private static final int TWIN = twinOf(POLICIES.pathId());

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<ResolverState> mappings = new HeapNestStore<>();
    private final TestOutbox outbox = new TestOutbox(256);

    @Test
    void aChildNamingTheOldKeyIsNotAnsweredByTheMappingLeftBehind() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, OWN_ROWS, policy(1, "P1", "C1"));
        feed(policies, TWIN, policyRenamed(2, "P1", "P2", "C1"));
        feed(policies, OWN_ROWS, policyRenamed(2, "P1", "P2", "C1"));

        List<Object> out = feed(policies, CLAIMS, claim(3, "K1", "P1"));

        assertThat(out)
                .describedAs("no row carries P1 any more, so a claim naming it has no parent to be placed "
                        + "under; answering it from the entry the row left behind puts it in a document it "
                        + "does not belong to")
                .isEmpty();
    }

    // ---- harness ------------------------------------------------------------------------

    private ResolverProcessor resolver() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, mappings, (from, released) -> { },
                null, null, ReplayFloor.NONE, NestClock.SYSTEM, NestSettings.defaults(),
                stores.forParking(POLICIES));
        processor.init(outbox, new TestProcessorContext());
        return processor;
    }

    private static int twinOf(List<String> pathId) {
        for (NestInbound edge : POLICIES.inbound()) {
            if (edge.carriesDepartures() && edge.pathId().equals(pathId)) {
                return edge.ordinal();
            }
        }
        throw new AssertionError("no departure edge carries " + pathId);
    }

    private List<Object> feed(ResolverProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        List<Object> out = new ArrayList<>();
        outbox.drainQueueAndReset(0, out, false);
        return out;
    }

    private static Envelope policy(long seq, String policyId, String customerId) {
        return Envelope.insert(seq, "policy", row("policy_id", policyId, "customer_id", customerId,
                "policy_no", "PN-1"), null).withOrder(at(seq));
    }

    private static Envelope policyRenamed(long seq, String was, String is, String customerId) {
        return Envelope.update(seq, "policy",
                row("policy_id", was, "customer_id", customerId, "policy_no", "PN-1"),
                row("policy_id", is, "customer_id", customerId, "policy_no", "PN-1"), null)
                .withOrder(at(seq));
    }

    private static Envelope claim(long seq, String claimId, String policyId) {
        return Envelope.insert(seq, "claim", row("claim_id", claimId, "policy_id", policyId), null)
                .withOrder(at(seq));
    }
}
