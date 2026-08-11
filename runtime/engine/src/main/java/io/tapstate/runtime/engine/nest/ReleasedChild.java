package io.tapstate.runtime.engine.nest;

import java.time.Duration;

/**
 * A change handed back by the level that was holding it, and how long it had been waiting when that
 * happened.
 *
 * <p>There is one ground for it and so no field saying which: the parent row this change hangs from is
 * known to be gone — deleted while this waited, or already tombstoned when it arrived. A wait is never
 * ended on a guess that a parent is not coming, so nothing here can be a change that would have been
 * placed had it waited longer.
 *
 * <p>The duration is what it actually waited, which for a change that arrived after the deletion is no
 * time at all. It is worth carrying because it says whether these rows are a long-standing dangling
 * reference or a deletion that just happened.
 */
public record ReleasedChild(NestElement child, Duration heldFor) {
}
