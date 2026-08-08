package io.tapstate.runtime.engine.nest;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * What a level needs to stop holding a change for a parent that is not coming: the rule, what it may be
 * told about the streams involved, and what time it is.
 *
 * <p>The three travel together because none of them is any use alone, and because leaving one of them out
 * fails in a direction nobody would notice: a rule with nothing to ask about the streams can only ever
 * reach its backstop, and every release then reads as "a parent may have been given up on" when almost
 * none of them were.
 */
public record PendingWatch(PendingProtection protection, NestStreamFacts facts, NestClock clock)
        implements Serializable {

    /**
     * How long a change may be held before the backstop ends the wait when nothing else can.
     *
     * <p>Provisional. It is bounded on both sides and the sides are far apart: long enough that a parent
     * merely late by a working morning is not given up on, short enough that a source's read position is
     * freed well inside the shortest log retention anyone runs — a day, on the tighter deployments. What a
     * deployment should run with follows from its own retention, which is the number this cannot know.
     */
    public static final Duration DEFAULT_BACKSTOP = Duration.ofHours(6);

    public PendingWatch {
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * The backstop and nothing else: the right shape where nobody has said which streams share a clock or
     * which have finished loading, and the wrong one as soon as they have — every release then carries the
     * verdict that says a row may just have been dropped, whether or not one was.
     */
    public static PendingWatch defaults() {
        return new PendingWatch(
                new PendingProtection(DEFAULT_BACKSTOP), NestStreamFacts.UNKNOWN, NestClock.SYSTEM);
    }
}
