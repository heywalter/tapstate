package io.tapstate.runtime.engine.nest;

import com.hazelcast.jet.core.AbstractProcessor;
import com.hazelcast.jet.core.Inbox;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;

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

    public ResolverProcessor(NestVertex vertex, NestStore<ResolverState> store, NestDeadLetter deadLetter) {
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.store = Objects.requireNonNull(store, "store");
        this.deadLetter = Objects.requireNonNull(deadLetter, "deadLetter");
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
            }
        } finally {
            touched.forEach(store::save);
        }
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
                NestKeys.orderOf(event), NestKeys.positionsOf(event));
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
