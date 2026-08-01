package io.tapstate.core.event;

import java.io.Serializable;

/**
 * Where one event sits in the order the engine itself assigns: a generation of the chain's change ring
 * ({@code epoch}) and the position the ring gave the event within that generation ({@code seq}).
 *
 * <p>This — not the source position token — is what stateful nodes compare. The token is a connector
 * value the engine never parses: only equality is defined on it, no order, and no connector API offers
 * a comparison, so it can carry a read back to where it left off but can never decide which of two
 * events is later. The two travel together and answer different questions: this one orders, the token
 * resumes.
 *
 * <p>Ordering is lexicographic — epoch first, then sequence — so a higher epoch always wins. That is
 * sound because a new generation begins only where a replay does, above the durable frontier, and each
 * chain is delivered in order: nothing a later generation carries precedes state an earlier one wrote.
 * A bare ring sequence without its epoch is not comparable across generations and must never be used
 * as an order on its own. Both components are plain longs and a sequence may be negative: a value
 * below every sequence the ring assigns is how rows that arrive with no position of their own — the
 * rows of a snapshot — are ordered ahead of every change of their epoch.
 *
 * <p>{@link Serializable} because it is held inside operator state that outlives a single run.
 */
public record SourceOrder(long epoch, long seq) implements Comparable<SourceOrder>, Serializable {

    @Override
    public int compareTo(SourceOrder other) {
        int byEpoch = Long.compare(epoch, other.epoch);
        return byEpoch != 0 ? byEpoch : Long.compare(seq, other.seq);
    }
}
