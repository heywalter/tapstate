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

    /**
     * The shortest gap between two sweeps for changes that may stop waiting.
     *
     * <p>A vertex with nothing arriving is asked to make progress over and over, and a sweep reads the
     * layer behind the map twice over - what each key still holds, and whether the stream a parent would
     * come on has finished loading. Sweeping every turn would put those reads on the idle path, which is
     * the one place they must never be. Nothing here is urgent to the second, let alone the millisecond:
     * what is being bounded is measured in hours.
     */
    private static final long SWEEP_INTERVAL_MILLIS = 1_000L;

    private final NestVertex vertex;
    private final NestStore<ResolverState> store;
    private final NestDeadLetter deadLetter;
    private final Deque<Object> outgoing = new ArrayDeque<>();
    private final ChainBounds held = new ChainBounds();
    private final LevelBounds bounds;
    private final ReplayFloor floor;

    /**
     * Keys whose mapping is a tombstone and whose record is still kept, against what that deletion
     * covered. A tombstone occupies a key just as a live mapping does, so it is counted by whatever caps
     * this vertex, which is why dropping it once it is safe to is worth doing at all.
     */
    private final Map<Object, Map<String, ChainPosition>> deleted = new LinkedHashMap<>();

    /**
     * Keys with something waiting in them, which is where the sweep for changes that may stop waiting has
     * to look. Kept here because the store cannot be asked which keys hold anything: listing what it has
     * is exactly what reading through it on demand is there to avoid.
     *
     * <p>A restart starts it empty, and the keys that matter re-enter it on their own: a change still
     * being held is one the frontier never passed, so a restart replays it, and it is filed here again the
     * moment its key is touched.
     */
    private final Set<Object> holding = new LinkedHashSet<>();

    /** The stream this vertex's own rows - the parents everything here waits for - arrive on. */
    private final String parentStream;

    /**
     * How far that stream has been read, in its own clock, or null before any of its rows has arrived.
     *
     * <p>Read from what has arrived here rather than from anything global, and that is what makes it
     * answer the question at all: a parent row and the children waiting for it are keyed alike, so they
     * are partitioned alike and land on this same processor. A row of that stream stamped later than a
     * change still waiting here therefore means the row that would have answered it does not exist -
     * had it existed, it would have come past here first.
     */
    private Long parentStreamReached;

    /** When the last sweep ran, or null before the first one. */
    private Long sweptAt;

    private final PendingWatch watch;

    /** How many changes one key here may hold for a parent that has not arrived. */
    private final long pendingLimit;

    /** A resolver in a job that propagates no frontier: it promises nothing and passes nothing on. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter) {
        this(vertex, store, deadLetter, null, null, ReplayFloor.NONE);
    }

    /** A resolver held to what {@code settings} allows one of its keys to hold. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            NestSettings settings) {
        this(vertex, store, deadLetter, null, null, ReplayFloor.NONE, PendingWatch.defaults(), settings);
    }

    /** A resolver that forgets a tombstone once {@code floor} says its deletion cannot come back. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ReplayFloor floor) {
        this(vertex, store, deadLetter, null, null, floor);
    }

    /** A resolver held to {@code watch} over what it may go on holding for a parent that never arrives. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            PendingWatch watch) {
        this(vertex, store, deadLetter, null, null, ReplayFloor.NONE, watch);
    }

    /**
     * A resolver that also passes a frontier on. {@code chainsByOrdinal} says which chains each of its
     * edges is compiled to carry - all of them must have promised before it says anything about one - and
     * what it is itself holding tightens the answer further.
     */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal) {
        this(vertex, store, deadLetter, axes, chainsByOrdinal, ReplayFloor.NONE);
    }

    /** The whole of it: a frontier passed on, and tombstones forgotten once it is safe to. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor) {
        this(vertex, store, deadLetter, axes, chainsByOrdinal, floor, PendingWatch.defaults());
    }

    /** All of the above, and held to {@code watch} over how long it may wait for a parent. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor,
            PendingWatch watch) {
        this(vertex, store, deadLetter, axes, chainsByOrdinal, floor, watch, NestSettings.defaults());
    }

    /** The whole of it, held to both of what it may wait: how long for one change, and how many at once. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal, ReplayFloor floor,
            PendingWatch watch, NestSettings settings) {
        this.pendingLimit =
                Objects.requireNonNull(settings, "settings").pendingAllowedIn(vertex.mapName());
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.store = Objects.requireNonNull(store, "store");
        this.deadLetter = Objects.requireNonNull(deadLetter, "deadLetter");
        this.bounds = axes == null ? null : new LevelBounds(chainsByOrdinal, axes, held::lowest);
        this.floor = Objects.requireNonNull(floor, "floor");
        this.watch = Objects.requireNonNull(watch, "watch");
        if (vertex.isAssembler()) {
            throw new IllegalArgumentException("the assembler is not a resolver: " + vertex.name());
        }
        this.parentStream = vertex.inboundFor(vertex.pathId()).alias();
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
        stopWaitingWhereNothingIsComing();
        return true;
    }

    /**
     * Lets go of the changes held here for parents that are not coming, so the bound this level reports
     * can rise past them. Off the path a change takes, like the tombstone sweep above and for the same
     * reason: nothing here is urgent to the millisecond, and the price of being wrong is a row nobody can
     * get back.
     *
     * <p>What each key was holding is re-read into the reported bound whether or not anything went, since
     * a change let go of that still counted against the bound would leave the frontier exactly where it
     * was - with the change dead-lettered as well, which is both halves of the damage and neither of the
     * benefits. A key left holding nothing and saying nothing is dropped outright: it is indistinguishable
     * from one that was never there, and keeping it would leak an entry per dangling reference.
     */
    private void stopWaitingWhereNothingIsComing() {
        if (holding.isEmpty()) {
            return;
        }
        long now = watch.clock().millis();
        if (sweptAt != null && now - sweptAt < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        sweptAt = now;
        PendingRelease rule = releaseRule();
        Iterator<Object> keys = holding.iterator();
        while (keys.hasNext()) {
            Object key = keys.next();
            ResolverState state = store.load(key);
            if (state == null) {
                keys.remove();
                continue;
            }
            List<ReleasedChild> released = state.letGo(rule, now);
            if (!released.isEmpty()) {
                if (state.vacant()) {
                    store.remove(key);
                } else {
                    store.save(key, state);
                }
                released.forEach(child -> deadLetter.unassemblable(vertex, child));
            }
            if (bounds != null) {
                held.holding(key, state.lowestHeldByChain());
            }
            if (!state.holdsChildren()) {
                keys.remove();
            }
        }
    }

    /**
     * The rule as this level applies it: the protection, with what is known about the stream a parent
     * would come on filled in. Worked out once per sweep rather than per change, because it is the same
     * answer for every change held here - they are all waiting for a row of the one stream.
     */
    private PendingRelease releaseRule() {
        boolean loaded = watch.facts().loaded(parentStream);
        String parentClock = watch.facts().clockOf(parentStream);
        return (child, heldFor) -> watch.protection()
                .verdictOn(new ParentProgress(loaded, readPast(child, parentClock)), child.eventTime(), heldFor);
    }

    /**
     * How far the parent's stream has been read, in a clock this change may be compared against - absent
     * where there is no such clock. A change covering more than one stream has no single clock of its own
     * and is never comparable; nor is one whose stream is not known to share a clock with the parent's.
     */
    private OptionalLong readPast(NestElement child, String parentClock) {
        if (parentStreamReached == null || parentClock == null || child.positions().size() != 1) {
            return OptionalLong.empty();
        }
        String stream = child.positions().keySet().iterator().next();
        return parentClock.equals(watch.facts().clockOf(stream))
                ? OptionalLong.of(parentStreamReached)
                : OptionalLong.empty();
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
     * Stores every entry this drain touched and re-reads what each is now holding, which is what keeps the
     * bound below a child that has been taken off the stream. Read from the state rather than accumulated
     * as events go by: the state is what actually holds the child, and after a restart it is the only
     * thing that still knows.
     */
    private void settle(Map<Object, ResolverState> touched) {
        touched.forEach((key, state) -> {
            store.save(key, state);
            refuseToLetOneKeyHoldMoreThanItMay(key, state.pending());
            if (bounds != null) {
                held.holding(key, state.lowestHeldByChain());
            }
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
        Map<String, Object> row = NestKeys.rowOf(event);
        if (edge.pathId().equals(vertex.pathId())) {
            own(edge, event, row, touched);
        } else {
            List<Object> parent = NestKeys.valuesOf(row, edge.keyFields());
            route(parent, element(edge, event, row, parentIdentity(edge, parent), null), touched);
        }
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
        // How far this stream has been read, which is what says whether a parent still missing from a key
        // is late or absent. Taken from every row of it, including one that loses to what a key already
        // holds: the read passed it either way, and that is all this records.
        parentStreamReached = parentStreamReached == null
                ? event.ts()
                : Math.max(parentStreamReached, event.ts());
        emit(new KeyedElement(parent, element(edge, event, row, parentIdentity(edge, parent), key)));
        if (NestKeys.isDeletion(event)) {
            deleted.put(key, event.positions());
            for (ReleasedChild child : state.deleteMapping(order, watch.clock().millis())) {
                deadLetter.unassemblable(vertex, child);
            }
        } else {
            deleted.remove(key);
            for (NestElement child : state.declare(parent, order)) {
                emit(new KeyedElement(parent, child));
            }
        }
        if (!state.holdsChildren()) {
            holding.remove(key);
        }
    }

    /** One change from beneath, offered to the key its join field names. */
    private void route(Object key, NestElement element, Map<Object, ResolverState> touched) {
        ResolverState state = stateFor(key, touched);
        switch (state.resolve(element, watch.clock().millis())) {
            case RESOLVED -> emit(new KeyedElement(state.parentKey(), element));
            case HELD -> holding.add(key);
            case PARENT_ABSENT -> deadLetter.unassemblable(vertex,
                    new ReleasedChild(element, PendingVerdict.PARENT_ABSENT, Duration.ZERO));
        }
    }

    /**
     * Where an element sits in the document, read off its own row. At depth one the parent is the root
     * itself and there is no element above to name.
     */
    private static Object parentIdentity(NestInbound edge, Object joinKey) {
        return edge.pathId().size() == 1 ? null : joinKey;
    }

    private static NestElement element(NestInbound edge, Envelope event, Map<String, Object> row,
            Object parentIdentity, Object identity) {
        ElementRef ref = new ElementRef(edge.pathId(), parentIdentity,
                NestKeys.valuesOf(row, edge.elementKey()), identity);
        return new NestElement(ref, NestKeys.isDeletion(event) ? null : row,
                NestKeys.orderOf(event), event.positions(), event.ts());
    }

    private ResolverState stateFor(Object key, Map<Object, ResolverState> touched) {
        return touched.computeIfAbsent(key, k -> {
            ResolverState kept = store.load(k);
            if (kept == null) {
                return new ResolverState();
            }
            // Reading a key back is the one moment a bucket filled before this run - or before this entry
            // was last evicted - becomes visible again, so it is where it re-enters the sweep.
            if (kept.holdsChildren()) {
                holding.add(k);
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
