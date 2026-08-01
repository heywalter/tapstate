package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.SourceOrder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One nested document under assembly: the root row, the tree of elements attached beneath it, and the
 * tombstones that keep a replay from undoing a deletion. It is the state a stateful nest node holds per
 * root key, mutated in place as events arrive and rendered into the document that goes downstream.
 *
 * <p><b>Every mutation carries an order and the higher order wins.</b> A mutation whose order is not
 * strictly greater than what the element already holds is refused and reported as no change, so a
 * replay, a re-delivered snapshot row and an out-of-order arrival all converge on the same document. A
 * tie keeps what is already there: two events that compare equal are never two versions of one row.
 *
 * <p><b>A delete leaves a versioned tombstone; it never drops the element.</b> The element disappears
 * from the document but its order — and the subtree hanging beneath it — stay behind, so an insert
 * replayed from beneath it stays deleted while a genuine rebuild above it brings the element back with
 * everything that had been attached to it. Dropping the entry instead would let any replay resurrect
 * deleted data, and would lose a subtree that no later event will resend.
 *
 * <p><b>Deleting the root keeps its elements attached</b>, for the same reason and by the same means: a
 * root tombstone is this assembly with the root marked absent, not a small object that replaces it.
 * Reclaiming the whole key is a memory optimisation performed elsewhere, under its own conditions, and
 * is never what makes the deletion correct.
 *
 * <p><b>An element hangs under the row its join key points at, not under a level.</b> Elements are
 * placed by the path of field names from the root, so two embeds side by side stay apart even when
 * their rows carry the same key value — a policy 77 and an order 77 are different parents. A child
 * whose parent row has not arrived is held until it does and then attached, which is what lets a deep
 * row travel with nothing but its own parent's key. Held children are state like any other: they
 * survive being stored and restored, and a delete held that way still wins over an insert replayed
 * from beneath it.
 *
 * <p><b>A document is rendered only while the root is present.</b> Until the root arrives, and again
 * once it is deleted, there is no document at all, whatever triggered the render — a child arriving, an
 * emit window closing, a move finishing. A rootless skeleton would become a permanent ghost document
 * downstream. The deletion of the root is not an assembled document and is not governed by this:
 * emitting it is the caller's business.
 *
 * <p>An array embed with no live element renders an empty array; an object embed with none omits its
 * field rather than rendering null, so two correct implementations cannot differ in the shape they
 * produce for the same input. An object embed that ends up holding several rows — a one-to-one the
 * source contradicted — shows the one with the highest order.
 *
 * <p>An element's identity is taken when it first appears. A row whose identity value changes later is
 * a structural key change, which this state does not track: its existing children stay where they are.
 *
 * <p>Field maps handed in are copied and the rendered document is built fresh each time, so neither
 * side can mutate the other's data. {@link Serializable} because this state outlives a single run.
 *
 * <p>An order is never null. A null order is an engine invariant violation and crashes bare rather
 * than being reported as a diagnosable error: comparing it would silently reorder data instead.
 */
public final class RootAssembly implements Serializable {

    private Map<String, Object> rootFields;
    private SourceOrder rootOrder;
    private boolean rootPresent;

    /** The embeds directly under the root: field name, then element key. */
    private final Map<String, Map<List<Object>, ElementNode>> children = new LinkedHashMap<>();

    /** Which element each identity value names, per embed — how a child finds the row it hangs under. */
    private final Map<List<String>, Map<Object, ElementNode>> byIdentity = new LinkedHashMap<>();

    /** Children whose parent row has not arrived yet, by the parent they are waiting for. */
    private final Map<WaitingOn, List<Pending>> waiting = new LinkedHashMap<>();

    /** Whether the root row is currently in the document — false before it arrives and after it is deleted. */
    public boolean rootPresent() {
        return rootPresent;
    }

    /**
     * Applies the root row of an insert, update or snapshot read. Returns whether the assembly changed:
     * an order at or beneath the root's own is refused.
     */
    public boolean applyRoot(Map<String, Object> fields, SourceOrder order) {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(order, "order");
        if (!wins(order, rootOrder)) {
            return false;
        }
        rootFields = copyOf(fields);
        rootOrder = order;
        rootPresent = true;
        return true;
    }

    /**
     * Marks the root deleted at {@code order}, keeping every element attached for a root that returns.
     * Returns whether the assembly changed.
     */
    public boolean deleteRoot(SourceOrder order) {
        Objects.requireNonNull(order, "order");
        if (!wins(order, rootOrder)) {
            return false;
        }
        rootFields = null;
        rootOrder = order;
        rootPresent = false;
        return true;
    }

    /**
     * Applies one element's row — an update of an element already there keeps its place in the array and
     * the children beneath it. Returns whether the assembly changed; a child held for a parent that has
     * not arrived has changed it.
     */
    public boolean applyElement(ElementRef ref, Map<String, Object> fields, SourceOrder order) {
        Objects.requireNonNull(fields, "fields");
        return mutate(ref, copyOf(fields), order);
    }

    /**
     * Deletes one element, leaving a tombstone at {@code order} and its subtree in place. Returns
     * whether the assembly changed.
     */
    public boolean deleteElement(ElementRef ref, SourceOrder order) {
        return mutate(ref, null, order);
    }

    /**
     * Moves one element from the parent {@code from} names to the parent {@code to} names, within this
     * root. **The whole node travels, children and all** — moving only the row would strand the subtree
     * beneath it, and nothing will ever resend those descendants. Returns whether the assembly changed.
     *
     * <p>When the new parent has not arrived yet the element is held rather than left where it was:
     * the source has already said it belongs elsewhere, so showing the old placement states a
     * relationship that is no longer true, and if the new parent never arrives that stays wrong for good
     * with nothing to signal it. Held, it sits in the pending bucket where an unresolvable parent is
     * already accounted for. An element that was never at {@code from} is simply placed at {@code to}.
     */
    public boolean reparentElement(ElementRef from, ElementRef to, Map<String, Object> fields, SourceOrder order) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(order, "order");
        if (!from.pathId().equals(to.pathId()) || !from.elementKey().equals(to.elementKey())) {
            throw new IllegalArgumentException("a move names one element: " + from + " -> " + to);
        }
        Map<String, Map<List<Object>, ElementNode>> source = containerFor(from);
        Map<List<Object>, ElementNode> slot = source == null ? null : source.get(from.field());
        ElementNode moved = slot == null ? null : slot.get(from.elementKey());
        if (slot == null || moved == null || moved.deleted()) {
            return mutate(to, copyOf(fields), order);
        }
        if (!wins(order, moved.order())) {
            return false;
        }
        slot.remove(from.elementKey());
        moved.set(copyOf(fields), order);
        Map<String, Map<List<Object>, ElementNode>> target = containerFor(to);
        if (target == null) {
            waiting.computeIfAbsent(new WaitingOn(to.parentPathId(), to.parentIdentity()), on -> new ArrayList<>())
                    .add(Pending.of(to, moved, order));
            return true;
        }
        target.computeIfAbsent(to.field(), field -> new LinkedHashMap<>()).put(to.elementKey(), moved);
        return true;
    }

    /**
     * The document as it now stands, or empty while the root is absent. {@code slots} is the declared
     * shape of the embeds under the root, each carrying its own: it decides which field every embed
     * occupies and whether an absent one renders as an empty array or not at all.
     */
    public Optional<Map<String, Object>> render(List<EmbedSlot> slots) {
        Objects.requireNonNull(slots, "slots");
        if (!rootPresent) {
            return Optional.empty();
        }
        Map<String, Object> document = new LinkedHashMap<>(rootFields);
        renderInto(document, children, slots);
        return Optional.of(document);
    }

    private boolean mutate(ElementRef ref, Map<String, Object> fields, SourceOrder order) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(order, "order");
        Map<String, Map<List<Object>, ElementNode>> container = containerFor(ref);
        if (container == null) {
            waiting.computeIfAbsent(new WaitingOn(ref.parentPathId(), ref.parentIdentity()), on -> new ArrayList<>())
                    .add(Pending.of(ref, fields, order));
            return true;
        }
        Map<List<Object>, ElementNode> slot = container.computeIfAbsent(ref.field(), field -> new LinkedHashMap<>());
        ElementNode held = slot.get(ref.elementKey());
        if (held != null) {
            if (!wins(order, held.order())) {
                return false;
            }
            held.set(fields, order);
            return true;
        }
        ElementNode element = new ElementNode(fields, order);
        slot.put(ref.elementKey(), element);
        if (ref.identity() != null) {
            byIdentity.computeIfAbsent(ref.pathId(), path -> new LinkedHashMap<>()).put(ref.identity(), element);
            release(ref.pathId(), ref.identity());
        }
        return true;
    }

    /** Applies whatever was waiting for the element just added, which may in turn release its own children. */
    private void release(List<String> pathId, Object identity) {
        List<Pending> held = waiting.remove(new WaitingOn(pathId, identity));
        if (held == null) {
            return;
        }
        for (Pending pending : held) {
            if (pending.node() == null) {
                mutate(pending.ref(), pending.fields(), pending.order());
            } else {
                ElementRef ref = pending.ref();
                // Released only from the parent that just arrived, so its embed is there by construction;
                // if it were not, attaching would lose the node and its subtree with no error anywhere.
                Map<String, Map<List<Object>, ElementNode>> parent = Objects.requireNonNull(
                        containerFor(ref), "a held child is released only once its parent is present");
                parent.computeIfAbsent(ref.field(), field -> new LinkedHashMap<>())
                        .put(ref.elementKey(), pending.node());
            }
        }
    }

    /** Where {@code ref}'s embed lives, or null while the parent row it names has not arrived. */
    private Map<String, Map<List<Object>, ElementNode>> containerFor(ElementRef ref) {
        List<String> parentPath = ref.parentPathId();
        if (parentPath.isEmpty()) {
            return children;
        }
        ElementNode parent = byIdentity.getOrDefault(parentPath, Map.of()).get(ref.parentIdentity());
        return parent == null ? null : parent.children();
    }

    private static void renderInto(Map<String, Object> document,
            Map<String, Map<List<Object>, ElementNode>> held, List<EmbedSlot> slots) {
        for (EmbedSlot slot : slots) {
            Map<List<Object>, ElementNode> elements = held.get(slot.path());
            switch (slot.as()) {
                case ARRAY -> document.put(slot.path(), liveOf(elements, slot));
                case OBJECT -> latestOf(elements)
                        .ifPresent(element -> document.put(slot.path(), renderOne(element, slot)));
            }
        }
    }

    private static Map<String, Object> renderOne(ElementNode element, EmbedSlot slot) {
        Map<String, Object> rendered = new LinkedHashMap<>(element.fields());
        renderInto(rendered, element.children(), slot.children());
        return rendered;
    }

    private static List<Map<String, Object>> liveOf(Map<List<Object>, ElementNode> elements, EmbedSlot slot) {
        List<Map<String, Object>> live = new ArrayList<>();
        if (elements != null) {
            for (ElementNode element : elements.values()) {
                if (!element.deleted()) {
                    live.add(renderOne(element, slot));
                }
            }
        }
        return live;
    }

    private static Optional<ElementNode> latestOf(Map<List<Object>, ElementNode> elements) {
        ElementNode latest = null;
        if (elements != null) {
            for (ElementNode element : elements.values()) {
                if (!element.deleted() && (latest == null || element.order().compareTo(latest.order()) > 0)) {
                    latest = element;
                }
            }
        }
        return Optional.ofNullable(latest);
    }

    private static boolean wins(SourceOrder candidate, SourceOrder held) {
        return held == null || candidate.compareTo(held) > 0;
    }

    private static Map<String, Object> copyOf(Map<String, Object> fields) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** One element as last applied: its row, or null once deleted, the order that put it there, and its own embeds. */
    private static final class ElementNode implements Serializable {

        private Map<String, Object> fields;
        private SourceOrder order;
        private final Map<String, Map<List<Object>, ElementNode>> children = new LinkedHashMap<>();

        private ElementNode(Map<String, Object> fields, SourceOrder order) {
            this.fields = fields;
            this.order = order;
        }

        private void set(Map<String, Object> newFields, SourceOrder newOrder) {
            fields = newFields;
            order = newOrder;
        }

        private Map<String, Object> fields() {
            return fields;
        }

        private SourceOrder order() {
            return order;
        }

        private Map<String, Map<List<Object>, ElementNode>> children() {
            return children;
        }

        private boolean deleted() {
            return fields == null;
        }
    }

    /** The parent an element is waiting for: the embed the parent belongs to, and the value it answers to. */
    private record WaitingOn(List<String> pathId, Object identity) implements Serializable { }

    /**
     * Held until the row it hangs under arrives: either an element event to apply ({@code node} null,
     * a null {@code fields} meaning a deletion), or a whole node being moved, which is attached as it
     * stands so its subtree travels with it.
     */
    private record Pending(ElementRef ref, Map<String, Object> fields, SourceOrder order, ElementNode node)
            implements Serializable {

        private static Pending of(ElementRef ref, Map<String, Object> fields, SourceOrder order) {
            return new Pending(ref, fields, order, null);
        }

        private static Pending of(ElementRef ref, ElementNode node, SourceOrder order) {
            return new Pending(ref, null, order, node);
        }
    }
}
