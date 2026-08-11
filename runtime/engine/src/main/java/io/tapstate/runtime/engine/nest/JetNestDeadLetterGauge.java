package io.tapstate.runtime.engine.nest;

import com.hazelcast.jet.core.metrics.Metrics;

/**
 * Publishes a namespace's running total of unassemblable changes as a run statistic of the job holding it,
 * so it is read from outside the run through the statistics the engine already collects.
 *
 * <p>The handle is looked up per call rather than kept. A handle belongs to the thread that took it, this is
 * reached from every processor thread of a vertex, and a kept one would be a map written from all of them at
 * once - which, measured on the readings next door, takes the whole job down mid-run rather than merely
 * losing a number. Nothing is paid for it here: a change that cannot be placed is by definition not the path
 * events take.
 */
final class JetNestDeadLetterGauge implements NestDeadLetterGauge {

    @Override
    public void handedOver(String namespace, long handedOver) {
        Metrics.metric(NestDeadLetterMetricNames.nameOf(namespace)).set(handedOver);
    }
}
