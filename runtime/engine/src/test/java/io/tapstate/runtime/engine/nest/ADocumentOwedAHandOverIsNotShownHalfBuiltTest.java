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
import io.tapstate.core.event.Op;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.NestRoot;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A document that is owed rows is not shown until it has them.
 *
 * <p>The two halves of a move are worked independently, so the key gaining a tree routinely has its root row
 * in hand before anything has been parked for it. Rendered then, what goes downstream is a document with its
 * whole tree missing - and a sink applying it writes exactly that. The version after it is correct, so
 * nothing is permanently wrong; what is wrong is that anything reading in between sees a document the source
 * never had, and a sink that fans out to something else has already passed it on.
 *
 * <p>Held back, what a reader sees is the version before the move for a moment longer, and then the whole
 * thing. That is the trade this makes: latency on one document during a structural change, against never
 * publishing a document that was never true.
 *
 * <p><b>It cannot be an unbounded wait.</b> The half that would complete it may never be worked at all, and
 * a document held for something that is not coming is a document nothing ever sends - the same silent shape
 * this is meant to prevent, arrived at from the other side. So the wait has the same protection the parking
 * area itself has, and at the end of it the document goes out as it stands.
 */
class ADocumentOwedAHandOverIsNotShownHalfBuiltTest {

    private static final TransformBody.Nest TREE = nest(new NestRoot("customer", List.of("customer_id"),
            null, true,
            List.of(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());
    private static final String NAMESPACE = TOPOLOGY.assembler().mapName();

    private static final int OWN_ROWS = 0;
    private static final int POLICIES = 1;
    private static final int DEPARTURES = 2;

    private final NestBinding.NestStores stores = HeapNestStores.onHeap();
    private final HeapNestStore<RootAssembly> documents = new HeapNestStore<>();
    private final TestOutbox out = new TestOutbox(512);
    private final Ticking clock = new Ticking();

    @Test
    void nothingGoesOutForAKeyWhileItIsStillOwedItsTree() throws Exception {
        AssemblerProcessor losing = assembler();
        AssemblerProcessor gaining = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 2);

        List<Object> emitted = feed(gaining, OWN_ROWS, renamed("C1", "C2"));

        assertThat(documentsIn(emitted))
                .describedAs("a document with its whole tree missing is one the source never had, and a "
                        + "sink applying it has already written it")
                .isEmpty();
    }

    @Test
    void itGoesOutWholeOnceTheTreeLands() throws Exception {
        AssemblerProcessor losing = assembler();
        AssemblerProcessor gaining = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 2);
        feed(gaining, OWN_ROWS, renamed("C1", "C2"));

        feed(losing, DEPARTURES, renamed("C1", "C2"));
        List<Object> emitted = new ArrayList<>();
        gaining.tryProcess();
        out.drainQueueAndReset(0, emitted, false);

        assertThat(documentsIn(emitted))
                .describedAs("one send, and it is the whole document")
                .containsExactly("C2:2");
    }

    /**
     * The positive control. A root row that is not part of a move is not owed anything, so holding it back
     * would be holding back every ordinary change - and this is what says the rule is about being owed rows
     * rather than about tracking being switched on.
     */
    @Test
    void anOrdinaryRootRowIsNotHeldBack() throws Exception {
        AssemblerProcessor assembler = assembler();

        List<Object> emitted = feed(assembler, OWN_ROWS, Envelope.insert(1, "customer",
                row("customer_id", "C9", "name", "n"), null).withOrder(at(1)));

        assertThat(documentsIn(emitted)).containsExactly("C9:0");
    }

    /** A key emptied by a move still says so at once: the row it named is gone whatever else is in flight. */
    @Test
    void theKeyBeingLeftIsStillRemovedAtOnce() throws Exception {
        AssemblerProcessor losing = assembler();
        customer(losing, "C1");
        policies(losing, "C1", 2);

        List<Object> emitted = feed(losing, DEPARTURES, renamed("C1", "C2"));

        assertThat(emitted).anyMatch(item -> item instanceof Envelope event && event.op() == Op.DELETE);
    }

    @Test
    void aDocumentOwedSomethingThatNeverComesGoesOutAsItStands() throws Exception {
        AssemblerProcessor gaining = assembler();
        feed(gaining, OWN_ROWS, renamed("C1", "C2"));

        clock.advance(1_000L);
        List<Object> emitted = new ArrayList<>();
        gaining.tryProcess();
        out.drainQueueAndReset(0, emitted, false);

        assertThat(documentsIn(emitted))
                .describedAs("held for something that is not coming is a document nothing ever sends, "
                        + "which is the shape being held back was meant to prevent")
                .containsExactly("C2:0");
    }

    // ---- harness ------------------------------------------------------------------------

    private AssemblerProcessor assembler() throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                documents, "doc", null, null, ReplayFloor.NONE,
                NestSettings.defaults().withMigrationProtection(NAMESPACE, 1_000L), clock,
                NestSendPolicy.within(0), stores.forParking(TOPOLOGY.assembler()), (from, released) -> { });
        processor.init(out, new TestProcessorContext());
        return processor;
    }

    private List<Object> feed(AssemblerProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        List<Object> emitted = new ArrayList<>();
        out.drainQueueAndReset(0, emitted, false);
        return emitted;
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

    private static Envelope renamed(String was, String is) {
        return Envelope.update(9, "customer",
                row("customer_id", was, "name", "n"),
                row("customer_id", is, "name", "n"), null).withOrder(at(90));
    }

    /** Every assembled document that went downstream, as {@code customer_id:policyCount}. */
    private static List<String> documentsIn(List<Object> emitted) {
        List<String> documents = new ArrayList<>();
        for (Object item : emitted) {
            if (item instanceof Envelope event && event.op() != Op.DELETE && event.after() != null) {
                Object policies = event.after().get("policies");
                documents.add(event.after().get("customer_id") + ":"
                        + (policies instanceof List<?> held ? held.size() : 0));
            }
        }
        return documents;
    }

    /** A clock that only moves when the test moves it, so a protection is measured rather than waited for. */
    private static final class Ticking implements NestClock {

        private long now = 1_000_000L;

        void advance(long millis) {
            now += millis;
        }

        @Override
        public long millis() {
            return now;
        }
    }
}
