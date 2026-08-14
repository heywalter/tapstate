package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.SourceOrder;

import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one key of one embed knows: which parent row the rows under this key hang from, and the children
 * that arrived before that was known. It is the state a resolver vertex holds per identity value —
 * declared by the embed's own row, read by the rows of the embed beneath it.
 *
 * <p><b>The higher order wins</b>, as everywhere else: a declaration at or beneath the one already held
 * is refused. <b>A deletion leaves a versioned tombstone rather than removing the entry.</b> The row's
 * children may still be on their way — a change not yet delivered, or one a replay will deliver again —
 * and an entry removed outright would leave them unable to resolve, so they would pile up waiting for a
 * parent that no longer exists and hold the frontier still. A tombstone answers them instead: a child
 * replayed from beneath it is told the parent is absent, and a genuine rebuild above it revives the
 * mapping.
 *
 * <p><b>A deletion empties the waiting bucket at once, and does not wait for a timeout.</b> Those
 * children were waiting for a row that is now known to be gone, so they can never resolve and would
 * otherwise pin the frontier for nothing. They are handed back to the caller to route — where they go
 * is the caller's business, but they must go somewhere: dropping them silently loses data that a
 * document-level assertion can never see, because those rows were never going to appear in a document.
 *
 * <p>The bucket is therefore non-empty only while there is no entry at all — a live mapping resolves on
 * the spot, a tombstone answers absent — which keeps the pending set small and makes "what is waiting
 * here" a question with one answer.
 *
 * <p><b>A held child keeps the position it arrived with.</b> It has been consumed and not emitted, so
 * the durable frontier must stay below it; {@link #lowestHeldByChain()} is what a vertex reports so
 * that can be enforced. Both halves of a position are kept: the order to compare on, the token to
 * persist and hand back to the connector.
 *
 * <p>{@link Serializable} because this state outlives a single run. An order is never null — a null
 * order is an engine invariant violation and crashes bare rather than being reported as a diagnosable
 * error.
 */
public final class ResolverState implements Serializable {

    /**
     * Said rather than derived, so this state's identity does not move when its fields do. Derived, it
     * changes with every field added or dropped, and every mapping already written becomes unreadable on
     * the first key that has to come back from the cold layer - an upgrade that looked clean until the
     * first miss. Held still, bytes written before a field existed still load, and that field reads back
     * at its zero. Bump this deliberately when that is the wrong answer and the old bytes should be
     * refused outright instead.
     */
    private static final long serialVersionUID = 1L;

    private Object parentKey;
    private SourceOrder order;
    private boolean deleted;
    private final List<Waiting> waiting = new ArrayList<>();

    /**
     * One change held here, and when it was first held. The time is a property of the holding rather than
     * of the change — the same change replayed after a restart starts waiting again — which is why it is
     * kept beside the change rather than inside it.
     */
    private record Waiting(NestElement child, long arrivedAt) implements Serializable {
    }

    /** The parent row these children hang from, or null before it is known and after it is deleted. */
    public Object parentKey() {
        return parentKey;
    }

    /** Whether the row that declared this mapping is known to be deleted. */
    public boolean deleted() {
        return deleted;
    }

    /** The children held here, in the order they arrived. */
    public List<NestElement> waiting() {
        return waiting.stream().map(Waiting::child).toList();
    }

    /** Whether anything is waiting here at all - asked far more often than what, and without building it. */
    public boolean holdsChildren() {
        return !waiting.isEmpty();
    }

    /** How many changes are held here for a parent that has not arrived. */
    public int pending() {
        return waiting.size();
    }

    /**
     * Whether this entry now says nothing: no mapping, no tombstone, nothing waiting. Such an entry is
     * indistinguishable from one that was never written, so keeping it costs a key and answers nothing.
     */
    public boolean vacant() {
        return order == null && !deleted && waiting.isEmpty();
    }

    /**
     * Declares that rows under this key hang from {@code parentKey}, and releases whatever was waiting
     * for it. Returns the released children — empty when the declaration is refused as already
     * superseded, or when nothing was waiting.
     */
    public List<NestElement> declare(Object parentKey, SourceOrder order) {
        Objects.requireNonNull(order, "order");
        if (!wins(order)) {
            return List.of();
        }
        this.parentKey = parentKey;
        this.order = order;
        this.deleted = false;
        return drain();
    }

    /**
     * Stops this entry answering for a parent, without saying the parent is gone. It is what a value being
     * vacated leaves behind: the row that answered to it now answers to another, so nothing carries this
     * value any more - but a row may yet declare it, and children naming it are waiting for exactly that.
     *
     * <p>Held rather than released, which is the difference from a deletion. A deletion says the parent
     * will not come back and its children are unassemblable; this says only that the value is unclaimed
     * right now. Left answering instead, the mapping the row abandoned goes on placing children under a
     * parent that is not there - in a document they do not belong to, with nothing counting it.
     */
    public void stopAnswering(SourceOrder order) {
        Objects.requireNonNull(order, "order");
        if (!wins(order)) {
            return;
        }
        this.parentKey = null;
        this.order = order;
    }

    /**
     * Marks the declaring row deleted at {@code order}, keeping a tombstone in place of the mapping.
     * Returns the children that were waiting: they can never resolve now, and the caller must route
     * them rather than drop them.
     */
    public List<ReleasedChild> deleteMapping(SourceOrder order, long now) {
        Objects.requireNonNull(order, "order");
        if (!wins(order)) {
            return List.of();
        }
        this.parentKey = null;
        this.order = order;
        this.deleted = true;
        List<ReleasedChild> released = heldFor(now);
        waiting.clear();
        return released;
    }

    /**
     * What deleting this mapping at {@code order} would release, without releasing it - empty where the
     * deletion does not win, exactly as applying it would be.
     *
     * <p>Separate from applying it for the same reason {@link #forgetWaiting()} is separate from
     * {@link #waiting()}: a released child is released by being written somewhere, and until that write is
     * done this entry is the only place it is. Emptied first and the write then failing leaves it nowhere,
     * with the replay that would rebuild it rejected as a change already seen.
     */
    public List<ReleasedChild> wouldRelease(SourceOrder order, long now) {
        Objects.requireNonNull(order, "order");
        return wins(order) ? heldFor(now) : List.of();
    }

    private List<ReleasedChild> heldFor(long now) {
        return waiting.stream()
                .map(held -> new ReleasedChild(held.child(), Duration.ofMillis(now - held.arrivedAt())))
                .toList();
    }

    /**
     * Offers one child event to this key: resolved when the parent row is known, absent when it is known
     * deleted, held otherwise — and a held child is kept until a declaration or a deletion releases it.
     */
    public Resolution resolve(NestElement child, long arrivedAt) {
        Objects.requireNonNull(child, "child");
        if (deleted) {
            return Resolution.PARENT_ABSENT;
        }
        // No declaration yet, or one that has been given up: a value nothing carries right now is not the
        // same as one whose row is gone, and the difference is what these children are waiting on. The
        // second case is only reachable through stopAnswering - a deletion leaves the tombstone above.
        if (order == null || parentKey == null) {
            waiting.add(new Waiting(child, arrivedAt));
            return Resolution.HELD;
        }
        return Resolution.RESOLVED;
    }

    /**
     * The lowest position held per chain — the bound below which the durable frontier must stay for the
     * events waiting here. Chains with nothing waiting do not appear.
     */
    public Map<String, ChainPosition> lowestHeldByChain() {
        Map<String, ChainPosition> lowest = new LinkedHashMap<>();
        for (Waiting held : waiting) {
            held.child().positions().forEach((chain, position) -> lowest.merge(chain, position,
                    (kept, candidate) -> candidate.order().compareTo(kept.order()) < 0 ? candidate : kept));
        }
        return lowest;
    }

    /**
     * Stops waiting for everything held here, leaving the mapping itself alone. It is what a key being
     * vacated gives up: those children asked a question of a value the row no longer answers to, and the
     * row now answers to another one, so the answer they are waiting for will never come here. The mapping
     * is not given up with them - the row declares it again where it now belongs.
     *
     * <p><b>Separate from reading them on purpose.</b> The rows have to be somewhere durable before this
     * entry stops being the only place they are: a drain stores what it touched however it ended, so an
     * entry emptied for a hand-over that then failed to land is stored empty, and the replay that would
     * rebuild it is rejected as a change already seen. Read with {@link #waiting()}, publish, then call
     * this.
     */
    public void forgetWaiting() {
        drain();
    }

    /**
     * Whether a row at {@code candidate} is newer than whatever this entry has already applied - asked
     * before anything is sent on, so that what leaves this level agrees with what it kept. A row it would
     * reject and that went out anyway puts an element back into a document a later change took it out of,
     * which no count anywhere is about.
     */
    public boolean accepts(SourceOrder candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return wins(candidate);
    }

    private boolean wins(SourceOrder candidate) {
        return order == null || candidate.compareTo(order) > 0;
    }

    private List<NestElement> drain() {
        if (waiting.isEmpty()) {
            return List.of();
        }
        List<NestElement> released = waiting();
        waiting.clear();
        return released;
    }
}
