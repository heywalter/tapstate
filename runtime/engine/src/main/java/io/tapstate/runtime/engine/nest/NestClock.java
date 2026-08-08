package io.tapstate.runtime.engine.nest;

import java.io.Serializable;

/**
 * What time it is, where the answer has to reach the member running a vertex.
 *
 * <p>A vertex reads the clock for one thing: how long it has been holding a change. That is wall-clock
 * time and not event time — what is being bounded is how long a source's read position may stay pinned,
 * which is measured in the same time as the log retention it is racing.
 *
 * <p>An interface rather than {@link java.time.Clock} because this travels with the graph to the member,
 * and the platform clocks are not serializable.
 */
@FunctionalInterface
public interface NestClock extends Serializable {

    /** The system clock, which is what everything but a test runs on. */
    NestClock SYSTEM = System::currentTimeMillis;

    /** Now, as epoch milliseconds. */
    long millis();
}
