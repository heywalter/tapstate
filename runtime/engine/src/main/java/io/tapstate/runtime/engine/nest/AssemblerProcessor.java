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

    private final NestVertex vertex;
    private final List<EmbedSlot> slots;
    private final NestStore<RootAssembly> store;
    private final String outputStream;
    private final Deque<Object> outgoing = new ArrayDeque<>();
    private final LevelBounds bounds;
    private final ReplayFloor floor;

    /**
     * How many elements any one document may hold. Read once: it is chosen where the job is built, not
     * here.
     */
    private final long elementLimit;

    /** How many changes one document here may hold for a root, or an ancestor, that has not arrived. */
    private final long pendingLimit;

    /** How many records of deleted elements one document here may keep once what may go has gone. */
    private final long tombstoneLimit;

    /**
     * Keys keeping the record of a deletion, which is where the sweep that drops them has to look. Kept for
     * the same reason as {@link #holding}: the store cannot be asked which of its keys hold anything.
     *
     * <p>A restart starts it empty, and unlike {@link #holding} the keys do not all come back on their own —
     * a record whose deletion the frontier has already passed is not replayed, so nothing re-files it until
     * that key is touched again. What is left behind that way is bounded and idle: a key nothing touches
     * again is a key that has stopped growing, and one that is touched is filed here again on the spot.
     */
    private final Set<Object> keeping = new LinkedHashSet<>();

    /** When the records of deletion were last swept, or null before the first sweep. */
    private Long forgottenAt;

    /**
     * What the sweeps here measure their own interval against. It is only ever read to decide whether
     * enough time has passed to look again — nothing this vertex holds is ever given up because a clock
     * reached some time.
     */
    private final NestClock clock;

    /**
     * Roots whose deletion has gone downstream and whose record is still kept, against what that deletion
     * covered. They are remembered as they happen rather than looked for later, because a store is not
     * something this can walk: asking one for its keys is what read-through exists to avoid.
     */
    private final Map<Object, Map<String, ChainPosition>> deleted = new LinkedHashMap<>();

    /** How often a document may go out, and whether versions of it may be merged into one send. */
    private final NestSendPolicy sending;

    /**
     * The documents with a window open over them: when the window opened, and what a send folded into it is
     * keeping the frontier below. Only ever as many entries as there are roots changed within one window of
     * each other, and the sweep that ends them is what keeps it that way.
     *
     * <p>What is kept per entry is the position and not the state. Holding the assembly itself would spare a
     * read at the end of the window and put every recently changed document in the heap, outside the budget
     * that decides what stays in memory - a second, invisible copy of exactly what that budget is set to
     * bound.
     */
    private final Map<Object, Window> windows = new LinkedHashMap<>();

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
        this(vertex, slots, store, outputStream, axes, chainsByOrdinal, floor, settings, NestClock.SYSTEM);
    }

    /** All of it, sweeping on {@code clock} rather than on the system's. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal,
            ReplayFloor floor, NestSettings settings, NestClock clock) {
        this(vertex, slots, store, outputStream, axes, chainsByOrdinal, floor, settings, clock,
                NestSendPolicy.within(0L));
    }

    /** All of it, sending as {@code sending} allows rather than as each drain settles. */
    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream, ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal,
            ReplayFloor floor, NestSettings settings, NestClock clock, NestSendPolicy sending) {
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.slots = List.copyOf(slots);
        this.store = Objects.requireNonNull(store, "store");
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream");
        this.sending = Objects.requireNonNull(sending, "sending");
        // What lowers the bound is a document changed and waiting for its window: it survives a restart in
        // the state, and nothing will ever send it, because no further event is due for that root. An
        // orphan waiting for its ancestor is the other case and is not counted - it is in the document's
        // own state, written through as the drain settles, and comes out when the ancestor arrives.
        this.bounds = axes == null ? null : new LevelBounds(chainsByOrdinal, axes, this::lowestUnsentOn);
        this.floor = Objects.requireNonNull(floor, "floor");
        this.elementLimit =
                Objects.requireNonNull(settings, "settings").elementsAllowedIn(vertex.mapName());
        this.pendingLimit = settings.pendingAllowedIn(vertex.mapName());
        this.tombstoneLimit = settings.tombstonesAllowedIn(vertex.mapName());
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!vertex.isAssembler()) {
            throw new IllegalArgumentException("a resolver does not assemble documents: " + vertex.name());
        }
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
        if (!flush()) {
            return false;
        }
        if (!sendWhatWindowsHaveRunOutOn()) {
            return false;
        }
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
        forgetDeletionsReplayCannotReach();
        return true;
    }

    /**
     * Drops the records of deletion whose deletions a restart could no longer replay. Here rather than where
     * a change is applied because it reads the durable plane, and because dropping one later than possible
     * costs an entry while dropping it earlier costs a deleted row coming back.
     *
     * <p>Held to the same interval as the sweep above, for the reason that one is: a vertex with nothing
     * arriving is asked to make progress over and over, and each pass reads back every document keeping a
     * record. A document whose records are not droppable yet stays a candidate, so without an interval those
     * reads would repeat as fast as the idle loop turns.
     */
    private void forgetDeletionsReplayCannotReach() {
        if (keeping.isEmpty()) {
            return;
        }
        long now = clock.millis();
        if (forgottenAt != null && now - forgottenAt < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        forgottenAt = now;
        Iterator<Object> keys = keeping.iterator();
        while (keys.hasNext()) {
            Object key = keys.next();
            RootAssembly assembly = store.load(key);
            if (assembly == null) {
                keys.remove();
                continue;
            }
            if (assembly.forgetDeletionsBelow(floor) > 0) {
                store.save(key, assembly);
            }
            if (assembly.tombstones() == 0) {
                keys.remove();
            }
        }
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
                // Under append every change is a record of its own, so a drain may not be merged into one
                // document any more than a window may: settling here rather than at the end is what makes
                // the count of records that go out the count of changes that arrived.
                if (!sending.foldingAllowed()) {
                    settle(touched);
                    touched.clear();
                    if (!flush()) {
                        return;
                    }
                    continue;
                }
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
        // Here as well as on the idle path, because a vertex fed steadily on one key never reaches the idle
        // path at all - and the document whose window ran out while that was going on is the one nothing
        // else is coming for.
        sendWhatWindowsHaveRunOutOn();
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
        // Before the bound, and on this path as well as the other two, because a level whose rows have
        // stopped while its bounds have not is neither draining nor idle - the ordinary shape of a source
        // that has caught up. A window ending only on those two would hold its last version of every
        // document for as long as the bounds kept arriving.
        if (!sendWhatWindowsHaveRunOutOn()) {
            return false;
        }
        if (bounds == null) {
            return true;
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
            touched(arrived.key(), touched).assembly.take(arrived.element());
            return;
        }
        Envelope event = (Envelope) item;
        NestKeys.requireBeforeImageWhereKeysAreTracked(edge, event);
        Map<String, Object> row = NestKeys.rowOf(event);
        SourceOrder order = NestKeys.orderOf(event);
        if (edge.pathId().isEmpty()) {
            List<Object> key = NestKeys.valuesOf(row, vertex.partitionKey());
            Touched document = touched(key, touched);
            document.ts = event.ts();
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
        Map<String, Object> was = NestKeys.replacedRow(edge, event);
        ElementRef from = was == null ? null
                : new ElementRef(edge.pathId(), null, NestKeys.valuesOf(was, edge.elementKey()), null);
        NestElement arriving = new NestElement(ref, NestKeys.isDeletion(event) ? null : row, order,
                event.positions(), from);
        document.assembly.take(arriving);
        if (was == null) {
            return;
        }
        // A leaf hanging straight off the root belongs to whichever document its join key names, so a row
        // re-pointed at another root has to be taken out of the one it was in. Both are held here, so the
        // pair needs no routing: this vertex is where the two keys would have met anyway.
        List<Object> before = NestKeys.valuesOf(was, edge.keyFields());
        if (!before.equals(key)) {
            Touched left = touched(before, touched);
            left.ts = event.ts();
            left.assembly.take(new NestElement(ref, null, order, event.positions(), from));
        }
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
                        deleted.remove(key);
                        if (mayGoOutNow(key)) {
                            outgoing.add(Envelope.insert(document.ts, outputStream, rendered, null)
                                    .withPositions(document.assembly.covered()));
                            document.assembly.documentSent();
                        } else {
                            windows.get(key).holds(document.ts, document.assembly.lowestUnsentByChain());
                        }
                    },
                    () -> {
                        if (document.rootDeleted) {
                            Map<String, ChainPosition> covered = document.assembly.coveredByADeletion();
                            outgoing.add(Envelope.delete(document.ts, outputStream, keyRow(key), null)
                                    .withPositions(covered));
                            document.assembly.deletionSent();
                            deleted.put(key, covered);
                            // The window goes with it. What it was holding back names a key that is gone,
                            // and the row bringing that key back is a change nothing should delay.
                            windows.remove(key);
                        }
                    });
            store.save(key, document.assembly);
            refuseToLetOneDocumentGrowPastItsWidth(key, document.assembly);
            NestLimits.refuse(vertex, key, document.assembly.pending(), pendingLimit);
            if (refuseToKeepMoreDeletionsThanAllowed(key, document.assembly) > 0) {
                keeping.add(key);
            } else {
                keeping.remove(key);
            }
        });
    }

    /**
     * Whether a document that has just changed may go out now, opening or re-opening its window if it may.
     *
     * <p>Leading edge: a document nobody has been writing to goes out on the spot and opens the window
     * behind it, so a root changing less often than the window pays nothing for it at all. Trailing edge
     * would delay every change by the window to merge the ones that mostly are not there.
     */
    private boolean mayGoOutNow(Object key) {
        if (sending.windowMillis() <= 0) {
            return true;
        }
        long now = clock.millis();
        Window window = windows.get(key);
        if (window == null) {
            windows.put(key, new Window(now));
            return true;
        }
        if (now - window.openedAt < sending.windowMillis()) {
            return false;
        }
        window.reopen(now);
        return true;
    }

    /**
     * Sends every document whose window has run out with something folded into it, and ends the windows
     * that ran out with nothing. Without this a folded change waits for the next change to that same root,
     * which for the root that just went quiet is never: the sink keeps the version before it, the job stays
     * RUNNING, and nothing counts an error.
     *
     * <p>Reading the state back is the cost of not keeping it here, and it is bounded by what it is for:
     * once per window per document that actually changed, never per idle turn. A window that ran out with
     * nothing folded into it is dropped without reading anything.
     */
    private boolean sendWhatWindowsHaveRunOutOn() {
        return sendFolded(true);
    }

    /**
     * Sends what every window is still holding, run out or not. Nothing else will run: the inputs are
     * done, so no drain and no idle turn is coming, and a version folded into a window that never ends is
     * one this level took in and nobody will ever see. A finite run - a backfill, a test, a pipeline
     * stopped on purpose - would otherwise end with its last version of every recently changed document
     * missing, and the sink holding the version before it with nothing reported.
     */
    @Override
    public boolean complete() {
        return sendFolded(false);
    }

    private boolean sendFolded(boolean onlyWhatHasRunOut) {
        if (windows.isEmpty()) {
            return flush();
        }
        long now = clock.millis();
        boolean letGo = false;
        Iterator<Map.Entry<Object, Window>> open = windows.entrySet().iterator();
        while (open.hasNext()) {
            Map.Entry<Object, Window> entry = open.next();
            Window window = entry.getValue();
            if (onlyWhatHasRunOut && now - window.openedAt < sending.windowMillis()) {
                continue;
            }
            if (window.unsent == null) {
                open.remove();
                continue;
            }
            RootAssembly assembly = store.load(entry.getKey());
            Optional<Map<String, Object>> rendered =
                    assembly == null ? Optional.empty() : assembly.render(slots);
            if (rendered.isEmpty()) {
                open.remove();
                continue;
            }
            outgoing.add(Envelope.insert(window.ts, outputStream, rendered.get(), null)
                    .withPositions(assembly.covered()));
            assembly.documentSent();
            store.save(entry.getKey(), assembly);
            window.reopen(now);
            letGo = true;
        }
        if (!flush()) {
            return false;
        }
        // Only after they have actually left: a bound offered ahead of the documents queued behind it
        // would say they had gone. And it has to be offered at all, because what this level may promise
        // depends on what it is holding as well as on what its edges said - an upstream that has finished
        // speaking sends nothing more to prompt the recount, and the chain would stay pinned at whatever
        // the fold held it to.
        return !letGo || bounds == null || bounds.release(this::tryEmit);
    }

    /**
     * The lowest position on {@code chain} that a window is holding back, or null when none is. This is the
     * whole of what this level keeps the frontier below: everything else it holds is written through with
     * the state and comes back out on its own.
     */
    private SourceOrder lowestUnsentOn(String chain) {
        SourceOrder lowest = null;
        for (Window window : windows.values()) {
            if (window.unsent == null) {
                continue;
            }
            ChainPosition held = window.unsent.get(chain);
            if (held != null && (lowest == null || held.order().compareTo(lowest) < 0)) {
                lowest = held.order();
            }
        }
        return lowest;
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

    /**
     * Stops the job once one document keeps more records of deleted elements than it is allowed to, and
     * answers with how many it is left keeping.
     *
     * <p><b>What may be dropped is dropped before the count is weighed</b>, which is what makes this limit
     * mean something an operator can act on. The sweep that drops them runs when nothing is arriving, so a
     * vertex that never goes idle would otherwise fail for being busy — and a limit that fires on load says
     * nothing about the frontier, which is the one thing reaching it is supposed to report.
     *
     * <p>That crossing to the durable plane is paid only by a document already at its limit, never by every
     * document on every drain: below the limit this reads a count and returns.
     */
    private long refuseToKeepMoreDeletionsThanAllowed(Object key, RootAssembly assembly) {
        long kept = assembly.tombstones();
        if (kept <= tombstoneLimit) {
            return kept;
        }
        if (assembly.forgetDeletionsBelow(floor) > 0) {
            store.save(key, assembly);
            kept = assembly.tombstones();
        }
        if (kept > tombstoneLimit) {
            throw new TapstateException(NestError.TOMBSTONE_LIMIT_EXCEEDED,
                    Map.of("namespace", vertex.mapName(), "key", String.valueOf(key),
                            "tombstones", kept, "limit", tombstoneLimit), null);
        }
        return kept;
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

    /** The window open over one document: when it opened, and what it is holding back if anything. */
    private static final class Window {

        private long openedAt;

        /**
         * The lowest position per chain of what a send folded into this window is keeping from the
         * frontier, or null when nothing has been folded in. Null rather than an empty map: an empty map
         * would read as "holding nothing on any chain", which is true of both, and the two are told apart
         * everywhere else by which one ends the window.
         */
        private Map<String, ChainPosition> unsent;

        /** The event time of the last change folded in, which is what the document goes out carrying. */
        private long ts;

        private Window(long openedAt) {
            this.openedAt = openedAt;
        }

        private void holds(long ts, Map<String, ChainPosition> lowestUnsent) {
            this.ts = ts;
            this.unsent = lowestUnsent;
        }

        private void reopen(long now) {
            this.openedAt = now;
            this.unsent = null;
        }
    }
}
