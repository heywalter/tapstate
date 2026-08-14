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
import io.tapstate.core.model.NestRoot;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A move interrupted part way through is carried on rather than lost, and carried on once rather than twice.
 *
 * <p>Nothing about a hand-over is remembered across a restart except the entries themselves. What a vertex
 * knows about a move in flight - that a hold is outstanding, that a document is owed rows - is in memory and
 * goes with the process. What makes that safe is the ordering it is built on: the durable frontier is held
 * below the change that started the move for as long as it is outstanding, so a restart resumes at or below
 * that change and it arrives again, at both halves.
 *
 * <p>So the two halves have to be replayable, and they are replayable for different reasons. The half that
 * lets go moves whatever has not been moved yet, which after a completed move is nothing - it needs no record
 * of where it got to, because what is left in the document <em>is</em> where it got to. The half that takes
 * in applies changes that are decided by order, so applying them a second time settles on the same thing.
 *
 * <p><b>What is simulated here is losing the process, not losing the state.</b> The stores outlive the
 * processors, which is what they are for: a processor is built again on every restart and addresses the same
 * entries by name. That the entries themselves survive a member is a different claim with a witness of its
 * own - the one that runs them through a map with a real store behind it.
 */
class AMoveInFlightIsCarriedOnAfterARestartTest {

    private static final TransformBody.Nest TREE = nest(new NestRoot("customer", List.of("customer_id"),
            null, true,
            List.of(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int OWN_ROWS = 0;
    private static final int POLICIES = 1;
    private static final int DEPARTURES = 2;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
    private final TestOutbox out = new TestOutbox(512);

    @Test
    void aTreeParkedBeforeTheProcessWentIsCollectedAfterIt() throws Exception {
        AssemblerProcessor losing = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 3);
        feed(losing, DEPARTURES, renamed());
        assertThat(stores.forParking(TOPOLOGY.assembler()).count())
                .describedAs("the tree really is in flight when the process is lost")
                .isPositive();

        // The process goes. Everything either half knew about the move goes with it; only the entries stay.
        AssemblerProcessor losingAgain = assembler();
        AssemblerProcessor gainingAgain = assembler();
        feed(losingAgain, DEPARTURES, renamed());
        feed(gainingAgain, OWN_ROWS, renamed());

        // Named rather than counted: a hand-over that carried some of the tree and a hand-over that carried
        // none of it are both "fewer than three", and only one of them is the failure worth telling apart.
        assertThat(policyNumbersOf("C2"))
                .describedAs("the replay carried on from where the move had got to")
                .containsExactlyInAnyOrder("PN-0", "PN-1", "PN-2");
        assertThat(stores.forParking(TOPOLOGY.assembler()).count()).isZero();
    }

    /**
     * The half of "nothing lost, nothing twice" that a replay is most likely to get wrong. The departure
     * arrives again at a document that has already been emptied, and moving "whatever has not been moved"
     * from an empty document has to be nothing at all - not the tree a second time, and not a second entry
     * left parked for a document that has already taken the first.
     */
    @Test
    void replayingAMoveThatAlreadyCompletedChangesNothing() throws Exception {
        AssemblerProcessor losing = assembler();
        AssemblerProcessor gaining = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 3);
        feed(losing, DEPARTURES, renamed());
        feed(gaining, OWN_ROWS, renamed());
        assertThat(policiesOf("C2")).hasSize(3);

        feed(losing, DEPARTURES, renamed());
        feed(gaining, OWN_ROWS, renamed());

        assertThat(policiesOf("C2"))
                .describedAs("a replayed move settles on the same document rather than doubling it")
                .hasSize(3);
        assertThat(stores.forParking(TOPOLOGY.assembler()).count())
                .describedAs("and leaves nothing parked for a hand-over that already landed")
                .isZero();
    }

    /**
     * The other order a restart can produce: the arriving half is replayed before the parking area has been
     * looked at by anything, so it finds nothing and has to go on looking. A restart that dropped the wait
     * would leave the tree parked and the document that owns it never asking again - the rows still in the
     * state layer, and in no document at all.
     */
    @Test
    void theWaitIsPickedUpAgainWhenTheArrivingHalfIsReplayedFirst() throws Exception {
        AssemblerProcessor losing = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 3);

        AssemblerProcessor gainingAgain = assembler();
        feed(gainingAgain, OWN_ROWS, renamed());
        assertThat(policiesOf("C2")).isEmpty();

        feed(losing, DEPARTURES, renamed());
        gainingAgain.tryProcess();

        assertThat(policiesOf("C2")).hasSize(3);
    }

    /** The key that was left holds nothing after the restart either - it was emptied, not half emptied. */
    @Test
    void theKeyThatWasLeftIsStillEmptyAfterTheRestart() throws Exception {
        AssemblerProcessor losing = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 3);
        feed(losing, DEPARTURES, renamed());

        AssemblerProcessor losingAgain = assembler();
        AssemblerProcessor gainingAgain = assembler();
        feed(losingAgain, DEPARTURES, renamed());
        feed(gainingAgain, OWN_ROWS, renamed());

        assertThat(policiesOf("C1")).isEmpty();
    }

    // ---- harness ------------------------------------------------------------------------

    private AssemblerProcessor assembler() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                documents, "doc", null, null, ReplayFloor.NONE, NestSettings.defaults(), NestClock.SYSTEM,
                NestSendPolicy.within(0), stores.forParking(TOPOLOGY.assembler()), (from, released) -> { });
        processor.init(out, new TestProcessorContext());
        return processor;
    }

    private void feed(AssemblerProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        out.drainQueueAndReset(0, new ArrayList<>(), false);
    }

    private void customer(AssemblerProcessor processor, String customerId) {
        feed(processor, OWN_ROWS, Envelope.insert(1, "customer",
                row("customer_id", customerId, "name", "n"), null).withOrder(at(1)));
    }

    private void policies(AssemblerProcessor processor, String customerId, int count) {
        for (int i = 0; i < count; i++) {
            feed(processor, POLICIES, Envelope.insert(2, "policy",
                    row("policy_id", "P" + i, "customer_id", customerId, "policy_no", "PN-" + i), null)
                    .withOrder(at(2 + i)));
        }
    }

    /** The same event both halves see, and the same one a replay delivers again. */
    private static Envelope renamed() {
        return Envelope.update(9, "customer",
                row("customer_id", "C1", "name", "n"),
                row("customer_id", "C2", "name", "n"), null).withOrder(at(90));
    }

    /** Which policies a document holds, by the value the array is keyed on. */
    private List<Object> policyNumbersOf(String customerId) {
        return policiesOf(customerId).stream().map(policy -> policy.get("policy_no")).toList();
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
