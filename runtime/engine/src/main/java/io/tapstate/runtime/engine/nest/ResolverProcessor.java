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

import java.time.Duration;
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
 * One resolver vertex: it answers, for one embed, which row of the level above each of its keys hangs
 * from, and passes every change it sees one step nearer the document.
 *
 * <p>Two kinds of thing arrive on the same key and are meant to. A row of the embed itself declares
 * "my key hangs from that parent" and is also an element of the document in its own right, so it both
 * writes the mapping and travels on. A row from beneath asks the same key that question: answered, it
 * travels on; unanswered, it waits here until the row that answers it arrives; and answered with "that
 * parent is gone" it can never reach a document and goes to the dead-letter rather than being dropped
 * or held forever.
 *
 * <p>Which of the two an item is, is decided by the ordinal it came in on and nothing else - the edges
 * were laid out while compiling, so no inspection of the item is needed or allowed.
 *
 * <p>The vertex is not cooperative: it reads and writes a store that may go to disk, and a cooperative
 * processor may not block. State is written back per drain rather than per event - the assembly is held
 * locally while the batch is worked through and stored once per key at the end, so a key touched many
 * times in one drain costs one write. That is safe against eviction because nothing is written until the
 * batch is done: an entry evicted mid-drain is still the clean one already on disk, and the events that
 * would have changed it have not been acknowledged, so a crash replays them.
 */
public final class ResolverProcessor extends AbstractProcessor {

    private final NestVertex vertex;
    private final NestStore<ResolverState> store;
    private final NestDeadLetter deadLetter;
    private final Deque<Object> outgoing = new ArrayDeque<>();
    private final LevelBounds bounds;
    private final ReplayFloor floor;

    /**
     * Keys whose mapping is a tombstone and whose record is still kept, against what that deletion
     * covered. A tombstone occupies a key just as a live mapping does, so it is counted by whatever caps
     * this vertex, which is why dropping it once it is safe to is worth doing at all.
     */
    private final Map<Object, Map<String, ChainPosition>> deleted = new LinkedHashMap<>();

    /**
     * What time it is, which is stamped on a change as it starts waiting and read again when it is handed
     * over unassemblable. Nothing is decided by it — the wait ends on the parent arriving or on the parent
     * being known gone — it only says how long the wait was, which is what tells a dangling reference from
     * a deletion that just happened.
     */
    private final NestClock clock;

    /** How many changes one key here may hold for a parent that has not arrived. */
    private final long pendingLimit;

    /** A resolver in a job that propagates no frontier: it promises nothing and passes nothing on. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter) {
        this(vertex, store, deadLetter, null, null, ReplayFloor.NONE);
    }

    /** A resolver held to what {@code settings} allows one of its keys to hold. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            NestSettings settings) {
        this(vertex, store, deadLetter, null, null, ReplayFloor.NONE, NestClock.SYSTEM, settings);
    }

    /** A resolver that forgets a tombstone once {@code floor} says its deletion cannot come back. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ReplayFloor floor) {
        this(vertex, store, deadLetter, null, null, floor);
    }

    /** A resolver timing its waits by {@code clock} rather than by the system's. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            NestClock clock) {
        this(vertex, store, deadLetter, null, null, ReplayFloor.NONE, clock);
    }

    /**
     * A resolver that also passes a frontier on. {@code chainsByOrdinal} says which chains each of its
     * edges is compiled to carry, and all of them must have promised before it says anything about one.
     * What it is holding does not enter into it - see where the bound is built.
     */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal) {
        this(vertex, store, deadLetter, axes, chainsByOrdinal, ReplayFloor.NONE);
    }

    /** The whole of it: a frontier passed on, and tombstones forgotten once it is safe to. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor) {
        this(vertex, store, deadLetter, axes, chainsByOrdinal, floor, NestClock.SYSTEM);
    }

    /** All of the above, timing its waits by {@code clock}. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor,
            NestClock clock) {
        this(vertex, store, deadLetter, axes, chainsByOrdinal, floor, clock, NestSettings.defaults());
    }

    /** The whole of it, held to what {@code settings} allows one of its keys to hold at once. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor,
            NestClock clock, NestSettings settings) {
        this.pendingLimit =
                Objects.requireNonNull(settings, "settings").pendingAllowedIn(vertex.mapName());
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.store = Objects.requireNonNull(store, "store");
        this.deadLetter = Objects.requireNonNull(deadLetter, "deadLetter");
        // Nothing here lowers the bound. A change waiting for a parent is written through to the store as
        // the drain settles, so it is somewhere it comes back from: the frontier passing it costs nothing
        // to recover, because the parent's own arrival is what brings it out again. What would lower the
        // bound is a change taken in and passed on nowhere durable, which this level never has.
        this.bounds = axes == null ? null : new LevelBounds(chainsByOrdinal, axes, LevelBounds.HOLDS_NOTHING);
        this.floor = Objects.requireNonNull(floor, "floor");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (vertex.isAssembler()) {
            throw new IllegalArgumentException("the assembler is not a resolver: " + vertex.name());
        }
    }

    /**
     * Run when there is nothing arriving: the moment to drop the tombstones whose deletion can no longer
     * be delivered again. Off the path a change takes, for the same reason the assembler's sweep is -
     * reading the durable plane costs more than the work a change does, and reclaiming late costs nothing.
     */
    @Override
    public boolean tryProcess() {
        Iterator<Map.Entry<Object, Map<String, ChainPosition>>> candidates = deleted.entrySet().iterator();
        while (candidates.hasNext()) {
            Map.Entry<Object, Map<String, ChainPosition>> candidate = candidates.next();
            ResolverState state = store.load(candidate.getKey());
            if (state == null || !state.deleted()) {
                candidates.remove();
            } else if (forgettable(state, candidate.getValue())) {
                store.remove(candidate.getKey());
                candidates.remove();
            }
        }
        return true;
    }

    /**
     * Whether a tombstone can be dropped: nothing is waiting on the key, and every position its deletion
     * covered sits below where a restart would resume. A chain whose floor is not known holds it back -
     * dropping it early lets a child that arrives afterwards find no answer where there was one, and wait
     * for a parent that has been deleted rather than being told so.
     *
     * <p>Nothing waits on a deleted key today: a deletion drains what was waiting, and a child arriving
     * afterwards is told the parent is absent rather than held. The check is still made, because that is
     * what the rule says and because a later bucket landing in the same place inherits it for free.
     */
    private boolean forgettable(ResolverState state, Map<String, ChainPosition> covered) {
        if (!state.lowestHeldByChain().isEmpty()) {
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

    /**
     * Stops the job once one key is holding more than it may for a parent that has not arrived. Checked per
     * key rather than across the level: how much one key has waiting says nothing about the others, and a
     * limit spent by whichever key filled up first would fail the rest for its queue.
     *
     * <p>Failed rather than released. Nothing here says the parent is absent - only that this much arrived
     * before it did - and letting go on that would drop rows that were going to reach a document, which is
     * the whole reason a wait is ended by evidence about the parent's own stream instead.
     */
    private void refuseToLetOneKeyHoldMoreThanItMay(Object key, long pending) {
        NestLimits.refuse(vertex, key, pending, pendingLimit);
    }

    @Override
    public boolean isCooperative() {
        return false;
    }

    @Override
    public void process(int ordinal, Inbox inbox) {
        if (!flush()) {
            return;
        }
        NestInbound edge = vertex.inbound().get(ordinal);
        Map<Object, ResolverState> touched = new LinkedHashMap<>();
        try {
            for (Object item; (item = inbox.peek()) != null; ) {
                handle(edge, item, touched);
                inbox.remove();
                if (!flush()) {
                    return;
                }
                if (touched.size() >= DrainFolding.MAX_KEYS_HELD) {
                    settle(touched);
                    touched.clear();
                }
            }
        } finally {
            settle(touched);
        }
    }

    /**
     * Stores every entry this drain touched, and does so before this level says anything about how far the
     * frontier may go.
     *
     * <p><b>That order is the whole basis for letting the frontier past a change still held here.</b> The
     * bound is worked out and sent from the watermark callback, which the engine calls only once a drain
     * has returned - and a drain returns through here. So by the time a bound covering a change is sent,
     * the write holding that change has already come back from the store. Reverse the two and the promise
     * is made about a change that is in neither place, which no test that is not a crash would notice.
     */
    private void settle(Map<Object, ResolverState> touched) {
        touched.forEach((key, state) -> {
            store.save(key, state);
            refuseToLetOneKeyHoldMoreThanItMay(key, state.pending());
        });
    }

    /**
     * Works out what this level may promise on the chain the bound travels on, and passes that on rather
     * than the bound itself. Whatever is waiting here for a parent keeps the answer below it.
     *
     * <p>Anything already worked out goes first: a bound emitted ahead of the changes queued behind it
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
     * this has left here" — a claim about this vertex rather than about its upstream, and one it has not
     * made: the children waiting here for a parent sit below that value and have gone nowhere. The engine
     * forwards it by default, silently, which is why saying otherwise is explicit. What this vertex does
     * promise is worked out from the bounds arriving per edge and sent on its own.
     */
    @Override
    public boolean tryProcessWatermark(Watermark watermark) {
        return true;
    }

    private void handle(NestInbound edge, Object item, Map<Object, ResolverState> touched) {
        if (edge.isCascade()) {
            KeyedElement arrived = (KeyedElement) item;
            route(arrived.key(), arrived.element(), touched);
            return;
        }
        Envelope event = (Envelope) item;
        NestKeys.requireBeforeImageWhereKeysAreTracked(edge, event);
        Map<String, Object> row = NestKeys.rowOf(event);
        if (edge.pathId().equals(vertex.pathId())) {
            own(edge, event, row, touched);
        } else {
            List<Object> parent = NestKeys.valuesOf(row, edge.keyFields());
            Map<String, Object> was = NestKeys.replacedRow(edge, event);
            List<Object> parentBefore = was == null ? null : NestKeys.valuesOf(was, edge.keyFields());
            Object parentWas = was == null ? null : parentIdentity(edge, parentBefore);
            NestElement arriving =
                    element(edge, event, row, parentIdentity(edge, parent), null, was, parentWas);
            route(parent, arriving, touched);
            if (parentBefore != null && !parentBefore.equals(parent)) {
                route(parentBefore, departureOf(arriving), touched);
            }
        }
    }

    /**
     * Says on the key the element used to hang from that it has gone, whenever a row's join key now names
     * a different one. It travels the old key rather than the new one on purpose: only that key leads to
     * the document holding the element today, and whether that is the same document the element is going to
     * is not something this level can answer - the two keys resolve independently and may lead anywhere.
     *
     * <p>Nothing is sent where the key did not move. The element is where it always was, and a departure
     * from an address it never left would take it out of the only document it is in.
     */
    private void sendDeparture(List<Object> parentBefore, List<Object> parent, NestElement arriving) {
        if (parentBefore == null || parentBefore.equals(parent)) {
            return;
        }
        emit(new KeyedElement(parentBefore, departureOf(arriving)));
    }

    /** The half of a move that stays behind: the same change with no row, so it places nothing. */
    private static NestElement departureOf(NestElement arriving) {
        return new NestElement(arriving.ref(), null, arriving.order(), arriving.positions(),
                arriving.movedFrom());
    }

    /**
     * A row of this vertex's own embed: it names the parent its key hangs from, which releases whatever
     * was waiting on that key, and it is an element of the document itself, so it travels on either way.
     * A deletion leaves a tombstone rather than removing the mapping, because rows beneath it may still
     * be on their way and would otherwise wait for a parent that no longer exists.
     */
    private void own(NestInbound edge, Envelope event, Map<String, Object> row,
            Map<Object, ResolverState> touched) {
        SourceOrder order = NestKeys.orderOf(event);
        List<Object> key = NestKeys.valuesOf(row, vertex.partitionKey());
        List<Object> parent = NestKeys.valuesOf(row, vertex.parentKeyFields());
        ResolverState state = stateFor(key, touched);
        Map<String, Object> was = NestKeys.replacedRow(edge, event);
        List<Object> parentBefore = was == null ? null : NestKeys.valuesOf(was, vertex.parentKeyFields());
        Object parentWas = was == null ? null : parentIdentity(edge, parentBefore);
        NestElement arriving = element(edge, event, row, parentIdentity(edge, parent), key, was, parentWas);
        emit(new KeyedElement(parent, arriving));
        sendDeparture(parentBefore, parent, arriving);
        if (NestKeys.isDeletion(event)) {
            deleted.put(key, event.positions());
            for (ReleasedChild child : state.deleteMapping(order, clock.millis())) {
                deadLetter.unassemblable(vertex, child);
            }
        } else {
            deleted.remove(key);
            for (NestElement child : state.declare(parent, order)) {
                emit(new KeyedElement(parent, child));
            }
        }
    }

    /** One change from beneath, offered to the key its join field names. */
    private void route(Object key, NestElement element, Map<Object, ResolverState> touched) {
        ResolverState state = stateFor(key, touched);
        switch (state.resolve(element, clock.millis())) {
            case RESOLVED -> emit(new KeyedElement(state.parentKey(), element));
            // Held: it is in the state now, and the drain writes that through before this level promises
            // anything, so there is nothing further to do here and nothing to record about it.
            case HELD -> { }
            // Zero: this change arrived after the parent was already gone, so it waited no time at all.
            case PARENT_ABSENT -> deadLetter.unassemblable(vertex, new ReleasedChild(element, Duration.ZERO));
        }
    }

    /**
     * Where an element sits in the document, read off its own row. At depth one the parent is the root
     * itself and there is no element above to name.
     */
    private static Object parentIdentity(NestInbound edge, Object joinKey) {
        return edge.pathId().size() == 1 ? null : joinKey;
    }

    /**
     * One change as an element of the document, carrying where the element used to sit whenever the row it
     * replaces says somewhere else.
     *
     * <p>Where it used to sit is built from the earlier row's own address and never from its identity: the
     * identity is what rows beneath point at, not where this element is shown, and an entry filed under a
     * value that has changed is moved by the level that holds it rather than by the document.
     */
    private static NestElement element(NestInbound edge, Envelope event, Map<String, Object> row,
            Object parentIdentity, Object identity, Map<String, Object> was, Object parentIdentityWas) {
        ElementRef ref = new ElementRef(edge.pathId(), parentIdentity,
                NestKeys.valuesOf(row, edge.elementKey()), identity);
        ElementRef from = was == null ? null : new ElementRef(edge.pathId(), parentIdentityWas,
                NestKeys.valuesOf(was, edge.elementKey()), identity);
        return new NestElement(ref, NestKeys.isDeletion(event) ? null : row,
                NestKeys.orderOf(event), event.positions(), from);
    }

    private ResolverState stateFor(Object key, Map<Object, ResolverState> touched) {
        return touched.computeIfAbsent(key, k -> {
            ResolverState kept = store.load(k);
            if (kept == null) {
                return new ResolverState();
            }
            return kept;
        });
    }

    private void emit(Object item) {
        outgoing.add(item);
    }

    /** Empties what is waiting to go out, reporting whether it all got out. */
    private boolean flush() {
        while (!outgoing.isEmpty()) {
            if (!tryEmit(outgoing.peek())) {
                return false;
            }
            outgoing.poll();
        }
        return true;
    }
}
