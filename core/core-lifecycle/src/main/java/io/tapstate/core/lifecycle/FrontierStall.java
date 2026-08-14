package io.tapstate.core.lifecycle;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * One chain whose durable position has stopped where it is: how long it has been there, and how far the
 * bound combined for it has run on in the meantime.
 *
 * <p>Both numbers are needed and neither is the other's summary. How long says the position is pinned at
 * all, which the distance cannot: a chain held back by changes still pending upstream sits exactly on its
 * bound and reports a distance of zero, and so does a chain keeping up perfectly. The distance says which
 * of the two pins this is, which the duration cannot. They are acted on from opposite ends — one upstream
 * on whatever is holding changes, one at the sink which has run out of positions to advance to — so an
 * alarm carrying only one of them tells an operator to go and look somewhere, without saying where.
 *
 * <p>What makes a pin urgent rather than merely true is that it is racing the source's retention window.
 * That window is kept in time, which is why the reading that raises anything here is the duration and the
 * distance rides along.
 *
 * @param chain the chain whose durable position is pinned
 * @param pinnedMillis how long it has been pinned where it is
 * @param gap how far the bound combined for the chain runs ahead of the position it reached, absent where
 *        the chain reports no distance at all — which is a chain that has never advanced, not one at zero
 */
public record FrontierStall(String chain, long pinnedMillis, OptionalLong gap) {

    public FrontierStall {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(gap, "gap");
        if (pinnedMillis < 0) {
            throw new IllegalArgumentException("pinnedMillis cannot be negative: " + pinnedMillis);
        }
    }

    /**
     * Whether the bound has run on past positions this sink was never given, which is the pin nothing
     * upstream is stuck for. Only supplying the positions shortens it, so the work is at the sink end.
     *
     * <p>A chain reporting no distance is not this. It has never advanced at all, so there is no position
     * to have run on from — reading its absence as a zero would put it in the other case by default, and
     * a chain that never advanced is the one worth waking someone for.
     */
    public boolean starvedOfPositions() {
        return gap.isPresent() && gap.getAsLong() > 0;
    }
}
