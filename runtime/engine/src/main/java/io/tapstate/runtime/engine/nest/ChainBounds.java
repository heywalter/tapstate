package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.SourceOrder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * How far one vertex instance lets the durable frontier go, chain by chain. A bound of {@code X} on a
 * chain is a promise: every change on it at or below {@code X} that was this instance's to handle has
 * gone downstream. It is a lower bound and nothing more — it says nothing about what other instances
 * have done, which is why bounds from several of them are combined by taking the lowest.
 *
 * <p><b>The bound is always a change that really arrived, never a value constructed between two of
 * them.</b> A position is a pair — an order to compare on and a token to persist and hand back to the
 * connector — and a value made up to sit just below a held change would have no token at all, so the
 * frontier could not be written anywhere. The bound is therefore chosen from what went out: the highest
 * change emitted with nothing still held beneath it. When there is no such change the chain simply does
 * not advance, which is conservative in the safe direction.
 *
 * <p>A change is held when it is put in a pending bucket rather than emitted, and released when it
 * leaves — either because it can now be emitted, or because it never can and has been routed to the
 * dead letter. Release is what stops one unresolvable key from pinning a whole chain forever, and it is
 * safe for the same reason in both cases: a released change will not be emitted later.
 *
 * <p>Positions arrive in increasing order per chain, since each instance is fed a chain in order. That
 * is what makes the bookkeeping bounded: a change that went out is worth remembering only while some
 * held change beneath a later one could still make it the answer, so at most one change per gap between
 * held ones is kept.
 *
 * <p>Not serializable and not part of any stored state: a restart replays from the durable frontier, so
 * what an instance had promised before it stopped is re-derived rather than restored.
 */
final class ChainBounds {

    private final Map<String, Chain> chains = new LinkedHashMap<>();

    /** Records that the changes at {@code positions} have gone downstream. */
    void emitted(Map<String, ChainPosition> positions) {
        positions.forEach((chain, position) -> chainFor(chain).emitted(position));
    }

    /** Records that the changes at {@code positions} were put in a pending bucket instead of going out. */
    void held(Map<String, ChainPosition> positions) {
        positions.forEach((chain, position) -> chainFor(chain).held(position));
    }

    /** Records that the changes at {@code positions} have left the bucket that was holding them. */
    void released(Map<String, ChainPosition> positions) {
        positions.forEach((chain, position) -> chainFor(chain).released(position));
    }

    /**
     * What this instance now promises per chain. A chain with nothing it can promise yet is absent
     * rather than present with a made-up value, and a bound once reported is never lowered.
     */
    Map<String, ChainPosition> lowerBounds() {
        Map<String, ChainPosition> reported = new LinkedHashMap<>();
        chains.forEach((chain, tracked) -> {
            ChainPosition bound = tracked.bound();
            if (bound != null) {
                reported.put(chain, bound);
            }
        });
        return reported;
    }

    private Chain chainFor(String chain) {
        return chains.computeIfAbsent(chain, name -> new Chain());
    }

    /** One chain's bookkeeping: what went out, what is still held, and how far it has been promised. */
    private static final class Chain {

        // Emitted changes not yet covered by the bound, thinned to the highest one in each gap between
        // held changes: the others can never be chosen, because arrivals only ever climb and so nothing
        // will appear beneath them to make them the answer.
        private final NavigableMap<SourceOrder, String> emitted = new TreeMap<>();
        // Held changes by order, counted rather than set-valued: every row of one snapshot shares a
        // single order, so two of them can be held at once.
        private final NavigableMap<SourceOrder, Integer> held = new TreeMap<>();
        private ChainPosition bound;

        private void emitted(ChainPosition position) {
            SourceOrder order = position.order();
            if (bound != null && order.compareTo(bound.order()) <= 0) {
                return;
            }
            SourceOrder beneath = emitted.lowerKey(order);
            emitted.put(order, position.token());
            if (beneath != null && held.subMap(beneath, false, order, true).isEmpty()) {
                emitted.remove(beneath);
            }
        }

        private void held(ChainPosition position) {
            held.merge(position.order(), 1, Integer::sum);
        }

        private void released(ChainPosition position) {
            held.computeIfPresent(position.order(), (order, count) -> count == 1 ? null : count - 1);
        }

        private ChainPosition bound() {
            SourceOrder lowestHeld = held.isEmpty() ? null : held.firstKey();
            Map.Entry<SourceOrder, String> candidate =
                    lowestHeld == null ? emitted.lastEntry() : emitted.lowerEntry(lowestHeld);
            if (candidate != null && (bound == null || candidate.getKey().compareTo(bound.order()) > 0)) {
                bound = new ChainPosition(candidate.getKey(), candidate.getValue());
                emitted.headMap(candidate.getKey(), true).clear();
            }
            return bound;
        }
    }
}
