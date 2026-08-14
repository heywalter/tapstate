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
 * What this level sends on has to agree with what it decided to keep.
 *
 * <p>Whether a row is applied is decided by its position: an entry that has taken a newer one rejects an
 * older, which is what makes a replay safe to run. Sending the row on before asking breaks that in one
 * direction only, and it is the direction nothing notices - the entry here is right, and the document
 * downstream has an element put back that a later change had taken out of it. A replay is the ordinary way
 * to reach it: resuming below a reparent replays the row the reparent superseded.
 */
class ARowOlderThanWhatWasAppliedIsNotSentOnTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());
    private static final NestVertex POLICIES = TOPOLOGY.vertexAt(List.of("policies"));

    private static final int OWN_ROWS = 0;

    private final TestOutbox outbox = new TestOutbox(256);

    @Test
    void aRowTheEntryHasAlreadySupersededIsNotSentOnAgain() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, OWN_ROWS, policyUnder(5, "P1", "C1"));
        feed(policies, OWN_ROWS, policyUnder(6, "P1", "C2"));

        List<Object> out = feed(policies, OWN_ROWS, policyUnder(5, "P1", "C1"));

        assertThat(out)
                .describedAs("the entry rejects this row as one it has already moved past, and what it "
                        + "sends on has to say the same: sent again it puts the element back in the "
                        + "document the newer row took it out of, and nothing counts that")
                .isEmpty();
    }

    /**
     * The discrimination that stops the case above from passing on an implementation that stopped sending
     * rows on at all: a row the entry does accept is still sent on.
     */
    @Test
    void aRowTheEntryAcceptsIsStillSentOn() throws Exception {
        ResolverProcessor policies = resolver();
        feed(policies, OWN_ROWS, policyUnder(5, "P1", "C1"));

        List<Object> out = feed(policies, OWN_ROWS, policyUnder(6, "P1", "C2"));

        assertThat(out).isNotEmpty();
    }

    // ---- harness ------------------------------------------------------------------------

    private ResolverProcessor resolver() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, new HeapNestStore<>(),
                (from, released) -> { }, null, null, ReplayFloor.NONE, NestClock.SYSTEM,
                NestSettings.defaults(), null);
        processor.init(outbox, new TestProcessorContext());
        return processor;
    }

    private List<Object> feed(ResolverProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        List<Object> out = new ArrayList<>();
        outbox.drainQueueAndReset(0, out, false);
        return out;
    }

    private static Envelope policyUnder(long seq, String policyId, String customerId) {
        return Envelope.insert(seq, "policy", row("policy_id", policyId, "customer_id", customerId,
                "policy_no", "PN-" + policyId), null).withOrder(at(seq));
    }
}
