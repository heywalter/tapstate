package io.tapstate.app;

import com.hazelcast.jet.core.DAG;

import java.util.Set;

/**
 * Supplies the Jet topology a pipeline runs, and the namespaces that topology keeps state in. The actuator
 * asks for a pipeline's topology when it starts the pipeline's job, and for its namespaces when it takes
 * the pipeline down. Kept a seam so the topology a pipeline runs can vary — the store-backed builder in
 * production, an idle stand-in in a lifecycle test — without the actuator, which drives the job by
 * pipeline id alone, having to change.
 *
 * <p>Both are answered here rather than the second one somewhere else, because they have to come from the
 * same compiled tree: a topology built one way and dropped by names worked out another way would leave
 * state behind under names nothing would ever name again.
 */
interface DagSource {

    /** The topology to run for {@code pipelineId}. */
    DAG dagFor(String pipelineId);

    /**
     * The state namespaces {@code pipelineId} keeps its operator state in, empty where it keeps none.
     *
     * <p>Deliberately not a defaulted method. An implementation that quietly answered "none" would leave
     * every namespace it owns behind at stop, and nothing about that announces itself: the pipeline stops,
     * the state stays, and the next run reads it as its own.
     */
    Set<String> stateNamespacesOf(String pipelineId);
}
