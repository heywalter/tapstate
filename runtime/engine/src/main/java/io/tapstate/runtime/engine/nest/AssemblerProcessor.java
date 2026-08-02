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

    private final NestVertex vertex;
    private final List<EmbedSlot> slots;
    private final NestStore<RootAssembly> store;
    private final String outputStream;
    private final Deque<Object> outgoing = new ArrayDeque<>();

    public AssemblerProcessor(NestVertex vertex, List<EmbedSlot> slots, NestStore<RootAssembly> store,
            String outputStream) {
        this.vertex = Objects.requireNonNull(vertex, "vertex");
        this.slots = List.copyOf(slots);
        this.store = Objects.requireNonNull(store, "store");
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream");
        if (!vertex.isAssembler()) {
            throw new IllegalArgumentException("a resolver does not assemble documents: " + vertex.name());
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
        Map<Object, Touched> touched = new LinkedHashMap<>();
        try {
            for (Object item; (item = inbox.peek()) != null; ) {
                handle(edge, item, touched);
                inbox.remove();
            }
        } finally {
            settle(touched);
        }
        flush();
    }

    private void handle(NestInbound edge, Object item, Map<Object, Touched> touched) {
        if (edge.isCascade()) {
            KeyedElement arrived = (KeyedElement) item;
            apply(touched(arrived.key(), touched), arrived.element());
            return;
        }
        Envelope event = (Envelope) item;
        Map<String, Object> row = NestKeys.rowOf(event);
        SourceOrder order = NestKeys.orderOf(event);
        if (edge.pathId().isEmpty()) {
            List<Object> key = NestKeys.valuesOf(row, vertex.partitionKey());
            Touched document = touched(key, touched);
            document.ts = event.ts();
            if (NestKeys.isDeletion(event)) {
                document.assembly.deleteRoot(order);
                document.rootDeleted = true;
            } else {
                document.assembly.applyRoot(row, order);
            }
            return;
        }
        List<Object> key = NestKeys.valuesOf(row, edge.keyFields());
        Touched document = touched(key, touched);
        document.ts = event.ts();
        ElementRef ref = new ElementRef(edge.pathId(), null,
                NestKeys.valuesOf(row, edge.elementKey()), null);
        apply(document, new NestElement(ref, NestKeys.isDeletion(event) ? null : row, order,
                NestKeys.positionsOf(event)));
    }

    private static void apply(Touched document, NestElement element) {
        if (element.deletion()) {
            document.assembly.deleteElement(element.ref(), element.order());
        } else {
            document.assembly.applyElement(element.ref(), element.fields(), element.order());
        }
    }

    /** Stores every document this drain touched and emits each one once, in the state it now stands in. */
    private void settle(Map<Object, Touched> touched) {
        touched.forEach((key, document) -> {
            store.save(key, document.assembly);
            document.assembly.render(slots).ifPresentOrElse(
                    rendered -> outgoing.add(Envelope.update(document.ts, outputStream, null, rendered, null)),
                    () -> {
                        if (document.rootDeleted) {
                            outgoing.add(Envelope.delete(document.ts, outputStream, keyRow(key), null));
                        }
                    });
        });
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
