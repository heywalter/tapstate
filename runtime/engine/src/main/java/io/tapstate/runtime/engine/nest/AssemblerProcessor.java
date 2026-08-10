package io.tapstate.runtime.engine.nest;

import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Inbox;
import com.hazelcast.jet.core.Watermark;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.runtime.engine.ChainAxes;
import io.tapstate.runtime.engine.LevelBounds;
import io.tapstate.runtime.engine.ReplayFloor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The vertex that holds whole documents: root rows arrive and become the document, elements arrive
 * already knowing where in it they belong, and what goes out is the document as it now stands.
 *
 * <p>Output is the state a document has reached, not the change that got it there - every emission is a
 * whole document, which is what makes it safe for a sink to apply out of order and for a restart to
 * repeat. One emission per document per drain rather than per event: a document touched by twenty
 * elements in one batch is rendered once, which is where most of the write amplification of assembling
 * documents goes.
 *
 * <p>While the root is absent there is no document to emit, whatever arrived - an element on its own
 * would render a skeleton with no root fields, and downstream that skeleton is a ghost document that
 * nothing later removes. A root that is deleted is the one thing that still goes out, because the sink
 * has a document to remove; it carries the key and nothing else, and is not an assembled document.
 */
public final class AssemblerProcessor extends AbstractProcessor {

    /**
     * The shortest gap between two sweeps for changes that may stop being held.
     *
     * <p>A vertex with nothing arriving is asked to make progress over and over, and a sweep reads the
     * layer behind the map — what each document is still holding, and whether the stream its root would
     * come on has finished loading. Sweeping every turn would put those reads on the idle path, which is
     * the one place they must never be. What is being bounded here is measured in hours.
     */
    private static final long SWEEP_INTERVAL_MILLIS = 1_000L;

    /** Where a vertex built without a watch would send what it let go of, since it lets go of nothing. */
    private static final NestDeadLetter NOTHING_IS_LET_GO_OF = (from, released) -> {
        throw new IllegalStateException("an assembler with no watch let go of " + released);
    };

    private final NestVertex vertex;
    private final List<EmbedSlot> slots;
    private final NestStore<RootAssembly> store;
    private final String outputStream;
    private final Deque<Object> outgoing = new ArrayDeque<>();
    private final ChainBounds held = new ChainBounds();
    private final LevelBounds bounds;
    private final ReplayFloor floor;
    private final NestDeadLetter deadLetter;

    /** What this vertex is held to over how long it may go on holding a change, or null when it is not. */
    private final PendingWatch watch;

    /** The stream this vertex's root rows — the rows every document here waits for — arrive on. */
    private final String rootStream;

    /**
     * How far that stream has been read, in its own clock, or null before any root row has arrived.
     *
     * <p>Read from what has arrived here rather than from anything global, and that is what makes it
     * answer the question at all: a root row and the elements of its document are keyed alike, so they are
     * partitioned alike and land on this same processor. A root row stamped later than an element still
     * held here therefore means the root that would have carried it does not exist — had it existed, it
     * would have come past here first.
     */
    private Long rootStreamReached;

    /**
     * Keys holding something, which is where the sweep has to look. Kept here because the store cannot be
     * asked which keys hold anything: listing what it has is exactly what reading through it on demand is
     * there to avoid.
     *
     * <p>A restart starts it empty, and the keys that matter re-enter it on their own: a change still held
     * is one the frontier never passed, so a restart replays it, and it is filed here again the moment its
     * key is touched.
     */
    private final Set<Object> holding = new LinkedHashSet<>();

    /** When the last sweep ran, or null before the first one. */
    private Long sweptAt;

    /**
     * How many elements any one document may hold. Read once: it is chosen where the job is built, not
     * here.
     */
    private final long elementLimit;

    /** How many changes one document here may hold for a root, or an ancestor, that has not arrived. */
    private final long pendingLimit;

    /**
     * Roots whose deletion has gone downstream and whose record is still kept, against what that deletion
     * covered. They are remembered as they happen rather than looked for later, because a store is not
     * something this can walk: asking one for its keys is what read-through exists to avoid.
     */
    private final Map<Object, Map<String, ChainPosition>> deleted = new LinkedHashMap<>();

    /** An assembler in a job that propagates no frontier: it promises nothing and passes nothing on. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream) {
        this(vertex, slots, store, outputStream, null, null, ReplayFloor.NONE);
    }

    /** An assembler that forgets deleted roots once {@code floor} says their deletion cannot come back. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, ReplayFloor floor) {
        this(vertex, slots, store, outputStream, null, null, floor);
    }

    /**
     * An assembler that also passes a frontier on. {@code chainsByOrdinal} says which chains each of its
     * edges is compiled to carry - all of them must have promised before it says anything about one - and
     * everything its documents are still holding tightens the answer further.
     */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal) {
        this(vertex, slots, store, outputStream, axes, chainsByOrdinal, ReplayFloor.NONE);
    }

    /** An assembler held to what {@code settings} allows this nest to hold, and to nothing else. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, NestSettings settings) {
        this(vertex, slots, store, outputStream, null, null, ReplayFloor.NONE, settings);
    }

    /** The whole of it: a frontier passed on, and deleted roots forgotten once it is safe to. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal,
            ReplayFloor floor) {
        this(vertex, slots, store, outputStream, axes, chainsByOrdinal, floor, NestSettings.defaults());
    }

    /** The whole of it, held to what this nest is allowed to hold. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal,
            ReplayFloor floor, NestSettings settings) {
        this(vertex, slots, store, outputStream, NOTHING_IS_LET_GO_OF, null, axes, chainsByOrdinal, floor,
                settings);
    }

    /**
     * An assembler held to {@code watch} over how long a document may go on holding a change for a root
     * that never arrives, with nowhere else to promise anything.
     */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, NestDeadLetter deadLetter, PendingWatch watch) {
        this(vertex, slots, store, outputStream, deadLetter, watch, null, null, ReplayFloor.NONE,
                NestSettings.defaults());
    }

    /**
     * All of it. The dead letter and the watch travel together and neither is optional here: a vertex that
     * may stop holding a change has to have somewhere to hand it, and one that never stops holding pins its
     * source's read position for as long as the job runs.
     */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, NestDeadLetter deadLetter, PendingWatch watch, ChainAxes axes,
            Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor, NestSettings settings) {
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.slots = List.copyOf(slots);
        this.store = Objects.requireNonNull(store, "store");
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream");
        this.deadLetter = Objects.requireNonNull(deadLetter, "deadLetter");
        this.watch = watch;
        this.bounds = axes == null ? null : new LevelBounds(chainsByOrdinal, axes, held::lowest);
        this.floor = Objects.requireNonNull(floor, "floor");
        this.elementLimit =
                Objects.requireNonNull(settings, "settings").elementsAllowedIn(vertex.mapName());
        this.pendingLimit = settings.pendingAllowedIn(vertex.mapName());
        if (!vertex.isAssembler()) {
            throw new IllegalArgumentException("a resolver does not assemble documents: " + vertex.name());
        }
        this.rootStream = vertex.inboundFor(List.of()).alias();
    }

    @Override
    public boolean isCooperative() {
        return false;
    }

    /**
     * Run when there is nothing arriving: the moment to forget the deleted roots that can no longer come
     * back. It sits here rather than on the path a change takes because reading the durable plane costs
     * more than assembling a document does, and because reclaiming memory later than possible costs
     * nothing - what a busy operator postpones, an idle one does.
     */
    @Override
    public boolean tryProcess() {
        Iterator<Map.Entry<Object, Map<String, ChainPosition>>> candidates = deleted.entrySet().iterator();
        while (candidates.hasNext()) {
            Map.Entry<Object, Map<String, ChainPosition>> candidate = candidates.next();
            RootAssembly assembly = store.load(candidate.getKey());
            if (assembly == null) {
                candidates.remove();
            } else if (assembly.rootPresent()) {
                candidates.remove();
            } else if (forgettable(assembly, candidate.getValue())) {
                store.remove(candidate.getKey());
                candidates.remove();
            }
        }
        stopHoldingWhereNothingIsComing();
        return true;
    }

    /**
     * Lets go of what is held here for a root, or for an ancestor, that is not coming, so the bound this
     * level reports can rise past it. Off the path a change takes, like the sweep above and for the same
     * reason: nothing here is urgent to the millisecond, and the price of being wrong is a row nobody can
     * get back.
     *
     * <p>What each document was holding is re-read into the reported bound whether or not anything went,
     * since a change let go of that still counted against the bound would leave the frontier exactly where
     * it was — with the change reported as let go of as well, which is both halves of the damage and
     * neither of the benefits.
     */
    private void stopHoldingWhereNothingIsComing() {
        if (watch == null || holding.isEmpty()) {
            return;
        }
        long now = watch.clock().millis();
        if (sweptAt != null && now - sweptAt < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        sweptAt = now;
        PendingRelease whileTheRootIsAbsent = rootReleaseRule();
        PendingRelease whileAnAncestorIsAbsent = ancestorReleaseRule();
        Iterator<Object> keys = holding.iterator();
        while (keys.hasNext()) {
            Object key = keys.next();
            RootAssembly assembly = store.load(key);
            if (assembly == null) {
                keys.remove();
                continue;
            }
            List<ReleasedChild> released =
                    assembly.letGo(whileTheRootIsAbsent, whileAnAncestorIsAbsent, now);
            if (!released.isEmpty()) {
                store.save(key, assembly);
                released.forEach(child -> deadLetter.unassemblable(vertex, child));
            }
            if (bounds != null) {
                held.holding(key, assembly.lowestHeldByChain());
            }
            if (!assembly.holdsAnything()) {
                keys.remove();
            }
        }
    }

    /**
     * The rule as it applies to what waits for a root row: the protection, with what is known about the
     * stream those rows come on filled in. Worked out once per sweep rather than per change, because every
     * document here waits for a row of that one stream.
     */
    private PendingRelease rootReleaseRule() {
        boolean loaded = watch.facts().loaded(rootStream);
        String rootClock = watch.facts().clockOf(rootStream);
        return (change, heldFor) -> watch.protection()
                .verdictOn(new ParentProgress(loaded, readPast(change, rootClock)), change.eventTime(),
                        heldFor);
    }

    /**
     * The rule as it applies to what waits for an ancestor element: the backstop and nothing else. That
     * element arrives already routed by the level below, on an edge that names no stream, so neither
     * whether its load has finished nor how far it has been read can be asked here — and answering with
     * what is known about the root's stream would end the wait on evidence about something else entirely.
     */
    private PendingRelease ancestorReleaseRule() {
        return (change, heldFor) -> watch.protection()
                .verdictOn(new ParentProgress(false, OptionalLong.empty()), change.eventTime(), heldFor);
    }

    /**
     * How far the root's stream has been read, in a clock this change may be compared against — absent
     * where there is no such clock. A change covering more than one stream has no single clock of its own
     * and is never comparable; nor is one whose stream is not known to share a clock with the root's.
     */
    private OptionalLong readPast(NestElement change, String rootClock) {
        if (rootStreamReached == null || rootClock == null || change.positions().size() != 1) {
            return OptionalLong.empty();
        }
        String stream = change.positions().keySet().iterator().next();
        return rootClock.equals(watch.facts().clockOf(stream))
                ? OptionalLong.of(rootStreamReached)
                : OptionalLong.empty();
    }

    /**
     * Whether what is left of a deleted root can be dropped: the assembly is holding nothing it has not
     * shown anyone, and every position its deletion covered sits below where a restart would resume.
     *
     * <p>A chain whose floor is not known holds the whole thing back. That is the safe way round: dropping
     * the record early lets a replayed insert build the root again with nothing left to say it was deleted,
     * whereas dropping it late costs one entry until the next sweep.
     */
    private boolean forgettable(RootAssembly assembly, Map<String, ChainPosition> covered) {
        if (!assembly.lowestHeldByChain().isEmpty()) {
            return false;
        }
        for (Map.Entry<String, ChainPosition> position : covered.entrySet()) {
            Optional<SourceOrder> resumesAt = floor.of(position.getKey());
            if (resumesAt.isEmpty() || position.getValue().order().compareTo(resumesAt.get()) >= 0) {
                return false;
            }
        }
        return !covered.isEmpty();
    }

    @Override
    public void process(int ordinal, Inbox inbox) {
        if (!flush()) {
            return;
        }
        NestInbound edge = vertex.inbound().get(ordinal);
        Map<Object, Touched> touched = new LinkedHashMap<>();
        try {
            for (Object item; (item = inbox.peek()) != null; ) {
                handle(edge, item, touched);
                inbox.remove();
                // A wide drain writes back what it holds rather than holding a document per key to the
                // end. A root touched again afterwards is read back and goes out a second time in the
                // same batch, which costs a write and is otherwise invisible: what goes out is the whole
                // document, so a sink upserting it twice lands where it would have landed once.
                if (touched.size() >= DrainFolding.MAX_KEYS_HELD) {
                    settle(touched);
                    touched.clear();
                    if (!flush()) {
                        return;
                    }
                }
            }
        } finally {
            settle(touched);
        }
        flush();
    }

    /**
     * Works out what this level may promise on the chain the bound travels on, and passes that on rather
     * than the bound itself. Everything its documents have taken in and not sent keeps the answer below it.
     *
     * <p>Anything already worked out goes first: a bound emitted ahead of the documents queued behind it
     * would claim they had left, and they are still right here.
     */
    @Override
    public boolean tryProcessWatermark(int ordinal, Watermark watermark) {
        if (bounds == null) {
            return true;
        }
        if (!flush()) {
            return false;
        }
        return bounds.advance(ordinal, watermark, this::tryEmit);
    }

    /**
     * Refuses to pass on a bound that arrived with no edge attached to it. Such a bound has already been
     * combined across every edge feeding this vertex, so sending it on would say "everything at or below
     * this has left here" — while the orphans held for a root that has not arrived, and the documents
     * touched but not yet rendered, sit below that value and have gone nowhere. The engine forwards it by
     * default, silently, which is why saying otherwise is explicit. What this vertex does promise is
     * worked out from the bounds arriving per edge and sent on its own.
     */
    @Override
    public boolean tryProcessWatermark(Watermark watermark) {
        return true;
    }

    private void handle(NestInbound edge, Object item, Map<Object, Touched> touched) {
        if (edge.isCascade()) {
            KeyedElement arrived = (KeyedElement) item;
            touched(arrived.key(), touched).assembly.take(arrived.element(), now());
            return;
        }
        Envelope event = (Envelope) item;
        Map<String, Object> row = NestKeys.rowOf(event);
        SourceOrder order = NestKeys.orderOf(event);
        if (edge.pathId().isEmpty()) {
            List<Object> key = NestKeys.valuesOf(row, vertex.partitionKey());
            Touched document = touched(key, touched);
            document.ts = event.ts();
            // How far this stream has been read, which is what says whether a root still missing from a key
            // is late or absent. Taken from every row of it, including one that loses to what a key already
            // holds: the read passed it either way, and that is all this records.
            rootStreamReached = rootStreamReached == null
                    ? event.ts()
                    : Math.max(rootStreamReached, event.ts());
            if (NestKeys.isDeletion(event)) {
                document.assembly.deleteRoot(order, event.positions());
                document.rootDeleted = true;
            } else {
                document.assembly.applyRoot(row, order, event.positions());
            }
            return;
        }
        List<Object> key = NestKeys.valuesOf(row, edge.keyFields());
        Touched document = touched(key, touched);
        document.ts = event.ts();
        ElementRef ref = new ElementRef(edge.pathId(), null,
                NestKeys.valuesOf(row, edge.elementKey()), null);
        document.assembly.take(new NestElement(ref, NestKeys.isDeletion(event) ? null : row, order,
                event.positions(), event.ts()), now());
    }

    /**
     * What time it is where a hold is timed from. A vertex built without a watch times nothing and lets
     * nothing go, so what it records is the one value that says exactly that.
     */
    private long now() {
        return watch == null ? RootAssembly.UNTIMED : watch.clock().millis();
    }

    /**
     * Stores every document this drain touched and emits each one once, in the state it now stands in.
     *
     * <p>A document goes out as a whole row and is applied by upserting it on its key, which is what makes
     * a resend harmless and lets any idempotent sink take it unchanged. It is deliberately not sent as a
     * change: there is no before image to offer - the elements that moved came from other rows entirely -
     * and a sink handed a change with no before image matches nothing, so it writes nothing and reports
     * nothing wrong.
     *
     * <p>A document going out is also what releases the changes it carried, and it goes out saying which
     * chains it drew on and how far - the only thing that ever leaves here, so a sink that is told nothing
     * can never ack a chain that ran through a nest. A deleted root's key row says the same of the deletion
     * alone: it carries no element, so an element absorbed alongside that deletion has still been shown to
     * nobody and goes on holding the frontier back. The state is stored after that, so what is written down
     * is what is still owed rather than what has just been paid.
     */
    private void settle(Map<Object, Touched> touched) {
        touched.forEach((key, document) -> {
            document.assembly.render(slots).ifPresentOrElse(
                    rendered -> {
                        outgoing.add(Envelope.insert(document.ts, outputStream, rendered, null)
                                .withPositions(document.assembly.covered()));
                        document.assembly.documentSent();
                        deleted.remove(key);
                    },
                    () -> {
                        if (document.rootDeleted) {
                            Map<String, ChainPosition> covered = document.assembly.coveredByADeletion();
                            outgoing.add(Envelope.delete(document.ts, outputStream, keyRow(key), null)
                                    .withPositions(covered));
                            document.assembly.deletionSent();
                            deleted.put(key, covered);
                        }
                    });
            store.save(key, document.assembly);
            refuseToLetOneDocumentGrowPastItsWidth(key, document.assembly);
            NestLimits.refuse(vertex, key, document.assembly.pending(), pendingLimit);
            if (bounds != null) {
                held.holding(key, document.assembly.lowestHeldByChain());
            }
            if (document.assembly.holdsAnything()) {
                holding.add(key);
            } else {
                holding.remove(key);
            }
        });
    }

    /**
     * Stops the job once one document has absorbed more elements than it is allowed to. Checked per
     * document rather than across the nest: how wide one has grown says nothing about the others, and a
     * limit spent by whichever document happened to be assembled first would fail the rest for its width.
     *
     * <p>This bounds memory where a count of entries cannot. A document is rendered whole, so however much
     * it holds is what has to be there at once, and no eviction reaches inside one.
     */
    private void refuseToLetOneDocumentGrowPastItsWidth(Object key, RootAssembly assembly) {
        long elements = assembly.elements();
        if (elements > elementLimit) {
            throw new TapstateException(NestError.ROOT_FANOUT_LIMIT_EXCEEDED,
                    Map.of("rootKey", String.valueOf(keyRow(key)), "elements", elements,
                            "limit", elementLimit), null);
        }
    }

    /** The key of a document that is gone, as the row a sink needs to find and remove it. */
    private Map<String, Object> keyRow(Object key) {
        List<?> values = (List<?>) key;
        Map<String, Object> row = new LinkedHashMap<>();
        List<String> fields = vertex.partitionKey();
        for (int i = 0; i < fields.size(); i++) {
            row.put(fields.get(i), values.get(i));
        }
        return row;
    }

    private Touched touched(Object key, Map<Object, Touched> touched) {
        return touched.computeIfAbsent(key, k -> {
            RootAssembly held = store.load(k);
            return new Touched(held == null ? new RootAssembly() : held);
        });
    }

    private boolean flush() {
        while (!outgoing.isEmpty()) {
            if (!tryEmit(outgoing.peek())) {
                return false;
            }
            outgoing.poll();
        }
        return true;
    }

    /** One document being worked on during a drain, before it is stored and emitted. */
    private static final class Touched {

        private final RootAssembly assembly;
        private boolean rootDeleted;
        private long ts;

        private Touched(RootAssembly assembly) {
            this.assembly = assembly;
        }
    }
}
