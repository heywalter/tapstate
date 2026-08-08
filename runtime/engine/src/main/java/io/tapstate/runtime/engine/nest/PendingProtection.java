package io.tapstate.runtime.engine.nest;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a change held for a parent that has not arrived may be let go, and on what grounds.
 *
 * <p>A parent that never comes is a lawful shape of data, not an error: a foreign key pointing nowhere, a
 * parent row filtered out, a parent table nobody captured. It trips no capacity limit either — one held
 * change is enough. But a change held here has been consumed and not emitted, so the durable frontier has
 * to stay below it, and a frontier that stays below one change forever pins the position its source reads
 * from. The log rotates past that position after a few days and the source has to be rebuilt from nothing,
 * with every pipeline mining the same chain taken down with it — the whole time reporting healthy, because
 * nothing failed.
 *
 * <p>So a change cannot be held forever, and letting one go early throws away a row that was going to
 * arrive in a document. Three tests decide it, and the wall clock is only the last of them:
 *
 * <ul>
 *   <li><b>The parent's level has finished loading.</b> This kills the common misjudgement — a parent that
 *       is merely slow, still to be read out of the initial load — and it is a boolean, so it says the
 *       same thing whichever chain either side travels.</li>
 *   <li><b>The parent's chain has been consumed past this change's own event time.</b> This kills the
 *       second one — a parent row written after the load finished and not yet read out of the log. The
 *       event time is read for liveness only and never to order anything, so nothing here compares
 *       positions across chains. It is absent, and this test therefore unmet, whenever the parent travels
 *       a chain whose clock cannot be compared with this change's: two sources are two clocks, and a
 *       child whose clock runs behind would have its parent declared absent while that parent was still
 *       on its way.</li>
 *   <li><b>It has been held as long as a change may be held.</b> The two above can be structurally unable
 *       to answer: a nest embedding its own table puts parent and child on one chain, so the change
 *       waiting here is itself what stops that chain from reaching the parent it waits for. Without this
 *       the frontier stays pinned for as long as the pipeline runs.</li>
 * </ul>
 *
 * <p>The first two together establish the parent absent. The third gives up without establishing anything,
 * and what it gives up on may well have been about to arrive — which is why the two answers are told
 * apart rather than both reading "released". An established absence outranks the backstop when both would
 * fire: whoever reads the dead letter needs to know which of "this is the shape of the data" and "I may
 * have just dropped a row" they are looking at, and the informative answer is the one to report.
 *
 * <p>{@link Serializable} because it is carried to the member that runs each vertex, which is where a
 * change is held and therefore where the question is asked.
 */
public record PendingProtection(Duration backstop) implements Serializable {

    public PendingProtection {
        Objects.requireNonNull(backstop, "backstop");
    }

    /** Whether the change may be let go now, and on which grounds; empty while it must go on being held. */
    public Optional<PendingVerdict> verdictOn(ParentProgress parent, long eventTime, Duration held) {
        if (establishedAbsent(parent, eventTime)) {
            return Optional.of(PendingVerdict.PARENT_ABSENT);
        }
        if (held.compareTo(backstop) >= 0) {
            return Optional.of(PendingVerdict.WALL_CLOCK_BACKSTOP);
        }
        return Optional.empty();
    }

    private static boolean establishedAbsent(ParentProgress parent, long eventTime) {
        return parent.levelLoaded()
                && parent.consumedEventTime().isPresent()
                && parent.consumedEventTime().getAsLong() >= eventTime;
    }
}
