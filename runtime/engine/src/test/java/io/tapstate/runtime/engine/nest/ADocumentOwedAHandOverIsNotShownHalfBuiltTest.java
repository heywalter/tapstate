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

    /**
     * A window that has already been opened over the key a move is arriving at is the way round the hold,
     * and it is reached without anything unusual happening: the key was being written to before the move
     * began, so its window is open with a version folded into it. Nothing puts that version down again
     * when the move arrives - the hold refuses the render and leaves the window alone - so a window that
     * flushed on the clock would send the very document the hold exists to keep from being seen, out of a
     * path the hold was never consulted on.
     *
     * <p>What makes it worth a case of its own is that the version it sends is not obviously wrong: it is
     * the document as it stood, correct for the key it used to be about, and short only the tree that is
     * still parked. A sink upserts it and nothing counts an error.
     */
    @Test
    void aWindowThatRunsOutIsNotAWayRoundTheHold() throws Exception {
        AssemblerProcessor losing = assembler();
        AssemblerProcessor gaining = windowed();
        customer(losing, "C1");
        policies(losing, "C1", 2);
        // C2 exists and is being written to, so its window is open with a version folded into it.
        feed(gaining, OWN_ROWS, customerAt(1, "C2"));
        feed(gaining, OWN_ROWS, customerAt(2, "C2"));

        feed(gaining, OWN_ROWS, renamed("C1", "C2"));
        clock.advance(PAST_THE_WINDOW);
        List<Object> emitted = new ArrayList<>();
        gaining.tryProcess();
        out.drainQueueAndReset(0, emitted, false);

        assertThat(documentsIn(emitted))
                .describedAs("the window ran out while the tree was still parked, and what it would have "
                        + "sent is the same half-built document the render refused")
                .isEmpty();
    }

    /**
     * The positive control for the case above, and the one that says the hold is a hold rather than a
     * wedge: the same window, the same clock, the tree landed. Without it "nothing went out" would be
     * satisfied just as well by a window that never flushes at all.
     */
    @Test
    void theHeldBackWindowGoesOutWholeOnceTheTreeLands() throws Exception {
        AssemblerProcessor losing = assembler();
        AssemblerProcessor gaining = windowed();
        customer(losing, "C1");
        policies(losing, "C1", 2);
        feed(gaining, OWN_ROWS, customerAt(1, "C2"));
        feed(gaining, OWN_ROWS, customerAt(2, "C2"));
        feed(gaining, OWN_ROWS, renamed("C1", "C2"));

        feed(losing, DEPARTURES, renamed("C1", "C2"));
        clock.advance(PAST_THE_WINDOW);
        List<Object> emitted = new ArrayList<>();
        gaining.tryProcess();
        out.drainQueueAndReset(0, emitted, false);

        assertThat(documentsIn(emitted))
                .describedAs("one send, and it is the whole document")
                .containsExactly("C2:2");
    }

    /**
     * The other control: a window over a key owed nothing still flushes on the clock. It is what says the
     * refusal above is about being owed rows rather than about windows having quietly stopped working.
     */
    @Test
    void aWindowOverAKeyOwedNothingStillFlushes() throws Exception {
        AssemblerProcessor assembler = windowed();
        feed(assembler, OWN_ROWS, customerAt(1, "C9"));
        feed(assembler, OWN_ROWS, customerAt(2, "C9"));

        clock.advance(PAST_THE_WINDOW);
        List<Object> emitted = new ArrayList<>();
        assembler.tryProcess();
        out.drainQueueAndReset(0, emitted, false);

        assertThat(documentsIn(emitted)).containsExactly("C9:0");
    }

    // ---- harness ------------------------------------------------------------------------

    /** Long enough that a folded version is due to go out, far short of the protection beside it. */
    private static final long SEND_WINDOW_MILLIS = 50L;
    private static final long PAST_THE_WINDOW = 100L;

    private AssemblerProcessor assembler() throws Exception {
        return assembler(NestSendPolicy.within(0), 1_000L);
    }

    /**
     * The same assembler sending on a window, and protected for far longer than the case advances the
     * clock - so what holds the document back is being owed a tree rather than the wait not being up.
     */
    private AssemblerProcessor windowed() throws Exception {
        return assembler(NestSendPolicy.within(SEND_WINDOW_MILLIS), 1_000_000L);
    }

    private AssemblerProcessor assembler(NestSendPolicy sending, long protection) throws Exception {
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                documents, "doc", null, null, ReplayFloor.NONE,
                NestSettings.defaults().withMigrationProtection(NAMESPACE, protection), clock,
                sending, stores.forParking(TOPOLOGY.assembler()), (from, released) -> { });
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
        feed(processor, OWN_ROWS, customerAt(1, customerId));
    }

    /** A root row for {@code customerId} at {@code seq}, so a case can write to one key twice over. */
    private static Envelope customerAt(long seq, String customerId) {
        return Envelope.insert(seq, "customer", row("customer_id", customerId, "name", "n" + seq), null)
                .withOrder(at(seq));
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
