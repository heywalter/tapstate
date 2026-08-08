package io.tapstate.runtime.engine.nest;

import java.time.Duration;

/**
 * A change let go of by the level that was holding it: the grounds it went on, and how long it had been
 * waiting when it did.
 *
 * <p>The duration is what it actually waited rather than what it was allowed to wait, because the two
 * differ for every release but one. Only a change ended by the backstop waited exactly the limit; a change
 * whose parent was established absent waited however long that took to establish, and reporting the limit
 * for it would describe a wait that never happened.
 */
public record ReleasedChild(NestElement child, PendingVerdict verdict, Duration heldFor) {
}
