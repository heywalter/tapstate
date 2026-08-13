package io.tapstate.e2e;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bounded, condition-driven waiting for the cases that drive the product directly rather than through a
 * declarative specification.
 *
 * <p>The same rule the specification runner follows: poll until the condition holds or the bound expires,
 * and never sleep a fixed duration. A sleep long enough to be reliable wastes that long on every green
 * run and is not quite reliable anyway. An expired bound reports what was last read, because "timed out"
 * on its own sends an author looking at the wait instead of at the product.
 */
final class Await {

    private static final Duration BOUND = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofMillis(250);

    private Await() {
    }

    /** Waits for {@code condition}, failing with {@code what} and the last reading when the bound expires. */
    static void until(String what, BooleanSupplier condition, Supplier<String> lastReading) {
        long start = System.nanoTime();
        long deadline = start + BOUND.toNanos();
        while (true) {
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("timed out after " + Duration.ofNanos(System.nanoTime() - start)
                        + " (bound " + BOUND + ") waiting for " + what + "; last read " + lastReading.get());
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a condition", e);
        }
    }
}
