package io.tapstate.runtime.engine.nest;

/**
 * Where a namespace's running total of unassemblable changes is left for whoever is watching the run.
 *
 * <p>A seam rather than a direct call, for the reason the state readings have one: the only place a run
 * statistic can be left is a thread executing the job's processors, and reaching for one anywhere else
 * throws. Without this, what a discarded change turns into could be witnessed only through a running job -
 * which is the least likely place for this path to be exercised at all, since it takes a source with a
 * dangling reference in it to reach.
 */
@FunctionalInterface
interface NestDeadLetterGauge {

    /** A gauge for somewhere with no run to report to, which reports nothing rather than pretending. */
    NestDeadLetterGauge NONE = (namespace, handedOver) -> { };

    /** Leaves {@code handedOver} as how many changes {@code namespace} has failed to place so far. */
    void handedOver(String namespace, long handedOver);
}
