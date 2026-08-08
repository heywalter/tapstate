package io.tapstate.runtime.engine.nest;

import java.time.Duration;
import java.util.Optional;

/**
 * Whether one change held for a parent may be let go, asked of the level holding it.
 *
 * <p>Two things have to meet for that question to be answerable and neither side has both. The level
 * knows what it is holding and how long each has been held; only the vertex knows which chain a change
 * came in on, how far the parent's chain has been read, and whether the two clocks can be compared at
 * all. So the vertex hands the rule down already bound to what it knows, and the level asks it.
 */
@FunctionalInterface
public interface PendingRelease {

    /** The grounds this change may be let go on, or empty while it must go on being held. */
    Optional<PendingVerdict> verdictOn(NestElement child, Duration held);
}
