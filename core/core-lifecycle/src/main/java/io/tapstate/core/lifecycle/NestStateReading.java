package io.tapstate.core.lifecycle;

/**
 * What one namespace of a nest's state looks like while the run is going: how much of it there is, how
 * often it was reached for, and how much of that reaching had to go to the layer behind it.
 *
 * <p>The three answer questions that look at each other. {@code entries} alone says how full something is
 * but not whether that costs anything; {@code backfills} against {@code accesses} says how much of the
 * reaching is being served from memory, which is the difference between a state layer that is holding up
 * and one that has quietly turned into a disk seek per event; and {@code backfillMillis} says what each of
 * those seeks is worth, which is what turns a ratio into a time.
 *
 * <p>They are counts since the run began rather than rates, deliberately. A rate computed here would be an
 * average over the whole run - the one window in which a cliff that started five minutes ago is invisible.
 * Two readings and the time between them give any window a reader actually wants; a single ratio gives
 * only the one nobody asked for.
 *
 * @param entries how many keys the namespace holds now
 * @param accesses how many times the state was reached for since the run began, read or written
 * @param backfills how many of those found nothing in memory and went to the layer behind
 * @param backfillMillis how long those took in total
 */
public record NestStateReading(long entries, long accesses, long backfills, long backfillMillis) {
}
