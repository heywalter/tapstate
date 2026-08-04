package io.tapstate.runtime.engine.nest;

import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Inbox;
import com.hazelcast.jet.core.Watermark;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.runtime.engine.ChainAxes;
import io.tapstate.runtime.engine.LevelBounds;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final ChainBounds held = new ChainBounds();
    private final LevelBounds bounds;

    /** A resolver in a job that propagates no frontier: it promises nothing and passes nothing on. */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter) {
        this(vertex, store, deadLetter, null, null);
    }

    /**
     * A resolver that also passes a frontier on. {@code chainsByOrdinal} says which chains each of its
     * edges is compiled to carry - all of them must have promised before it says anything about one - and
     * what it is itself holding tightens the answer further.
     */
    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter,
            ChainAxes axes, Map<Integer, List<String>> chainsByOrdinal) {
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.store = Objects.requireNonNull(store, "store");
        this.deadLetter = Objects.requireNonNull(deadLetter, "deadLetter");
        this.bounds = axes == null ? null : new LevelBounds(chainsByOrdinal, axes, held::lowest);
        if (vertex.isAssembler()) {
            throw new IllegalArgumentException("the assembler is not a resolver: " + vertex.name());
        }
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
        List<NestElement> released = NestKeys.isDeletion(event)
                ? state.deleteMapping(order)
                : state.declare(parent, order);
        emit(new KeyedElement(parent, element(edge, event, row, parentIdentity(edge, parent), key)));
        for (NestElement child : released) {
            if (NestKeys.isDeletion(event)) {
                deadLetter.parentAbsent(child);
            } else {
                emit(new KeyedElement(parent, child));
            }
        }
    }

    /** One change from beneath, offered to the key its join field names. */
    private void route(Object key, NestElement element, Map<Object, ResolverState> touched) {
        ResolverState state = stateFor(key, touched);
        switch (state.resolve(element)) {
            case RESOLVED -> emit(new KeyedElement(state.parentKey(), element));
            case HELD -> { }
            case PARENT_ABSENT -> deadLetter.parentAbsent(element);
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
                NestKeys.orderOf(event), event.positions());
    }

    private ResolverState stateFor(Object key, Map<Object, ResolverState> touched) {
        return touched.computeIfAbsent(key, k -> {
            ResolverState held = store.load(k);
            return held == null ? new ResolverState() : held;
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
