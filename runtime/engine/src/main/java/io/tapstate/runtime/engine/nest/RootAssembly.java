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
 * One nested document under assembly: the root row, every element attached to it, and the tombstones
 * that keep a replay from undoing a deletion. It is the state a stateful nest node holds per root key,
 * mutated in place as events arrive and rendered into the document that goes downstream.
 *
 * <p><b>Every mutation carries an order and the higher order wins.</b> A mutation whose order is not
 * strictly greater than what the slot already holds is refused and reported as no change, so a replay,
 * a re-delivered snapshot row and an out-of-order arrival all converge on the same document. A tie
 * keeps what is already there: two events that compare equal are never two versions of one row.
 *
 * <p><b>A delete leaves a versioned tombstone; it never drops the slot.</b> The element disappears
 * from the document but its order stays behind, so an insert replayed from beneath it stays deleted
 * while a genuine rebuild above it brings the element back. Dropping the entry instead would let any
 * replay resurrect deleted data, and the deletion would be lost with no error anywhere.
 *
 * <p><b>Deleting the root keeps its elements attached.</b> A root tombstone is this assembly with the
 * root marked absent, not a small object that replaces it: a root that comes back finds everything
 * that had been attached to it, and a subtree still being moved out is still here to move. Reclaiming
 * the whole key is a memory optimisation performed elsewhere, under its own conditions, and is never
 * what makes the deletion correct.
 *
 * <p><b>A document is rendered only while the root is present.</b> Elements that arrive first are held
 * and rendered once their root does; until then there is no document at all, whatever triggered the
 * render — a child arriving, an emit window closing, a move finishing. A rootless skeleton would
 * become a permanent ghost document downstream. The deletion of the root is not an assembled document
 * and is not governed by this: emitting it is the caller's business.
 *
 * <p>An array embed with no live element renders an empty array; an object embed with none omits its
 * field rather than rendering null, so two correct implementations cannot differ in the shape they
 * produce for the same input. An object embed that ends up holding several rows — a one-to-one the
 * source contradicted — shows the one with the highest order.
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
    private final Map<String, Map<List<Object>, Element>> elements = new LinkedHashMap<>();

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
     * Applies one element of the embed at {@code path}, identified by {@code elementKey} — an update of
     * an element already there keeps its place in the array. Returns whether the assembly changed.
     */
    public boolean applyElement(String path, List<Object> elementKey, Map<String, Object> fields, SourceOrder order) {
        Objects.requireNonNull(fields, "fields");
        return mutate(path, elementKey, copyOf(fields), order);
    }

    /**
     * Deletes one element of the embed at {@code path}, leaving a tombstone at {@code order} in its
     * place. Returns whether the assembly changed.
     */
    public boolean deleteElement(String path, List<Object> elementKey, SourceOrder order) {
        return mutate(path, elementKey, null, order);
    }

    /**
     * The document as it now stands, or empty while the root is absent. {@code slots} is the declared
     * shape of the embeds directly under the root: it decides which field each one occupies and whether
     * an absent embed renders as an empty array or not at all.
     */
    public Optional<Map<String, Object>> render(List<EmbedSlot> slots) {
        Objects.requireNonNull(slots, "slots");
        if (!rootPresent) {
            return Optional.empty();
        }
        Map<String, Object> document = new LinkedHashMap<>(rootFields);
        for (EmbedSlot slot : slots) {
            Map<List<Object>, Element> held = elements.get(slot.path());
            switch (slot.as()) {
                case ARRAY -> document.put(slot.path(), liveOf(held));
                case OBJECT -> latestOf(held).ifPresent(element -> document.put(slot.path(), element));
            }
        }
        return Optional.of(document);
    }

    private boolean mutate(String path, List<Object> elementKey, Map<String, Object> fields, SourceOrder order) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(elementKey, "elementKey");
        Objects.requireNonNull(order, "order");
        Map<List<Object>, Element> slot = elements.computeIfAbsent(path, ignored -> new LinkedHashMap<>());
        List<Object> identity = copyOf(elementKey);
        Element current = slot.get(identity);
        if (current != null && !wins(order, current.order())) {
            return false;
        }
        slot.put(identity, new Element(fields, order));
        return true;
    }

    private static boolean wins(SourceOrder candidate, SourceOrder held) {
        return held == null || candidate.compareTo(held) > 0;
    }

    private static List<Map<String, Object>> liveOf(Map<List<Object>, Element> held) {
        List<Map<String, Object>> live = new ArrayList<>();
        if (held != null) {
            for (Element element : held.values()) {
                if (!element.deleted()) {
                    live.add(element.fields());
                }
            }
        }
        return live;
    }

    private static Optional<Map<String, Object>> latestOf(Map<List<Object>, Element> held) {
        Element latest = null;
        if (held != null) {
            for (Element element : held.values()) {
                if (!element.deleted() && (latest == null || element.order().compareTo(latest.order()) > 0)) {
                    latest = element;
                }
            }
        }
        return latest == null ? Optional.empty() : Optional.of(latest.fields());
    }

    private static Map<String, Object> copyOf(Map<String, Object> fields) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    private static List<Object> copyOf(List<Object> elementKey) {
        return Collections.unmodifiableList(new ArrayList<>(elementKey));
    }

    /** One element as last applied: its row, or null once deleted, and the order that put it there. */
    private record Element(Map<String, Object> fields, SourceOrder order) implements Serializable {

        boolean deleted() {
            return fields == null;
        }
    }
}
