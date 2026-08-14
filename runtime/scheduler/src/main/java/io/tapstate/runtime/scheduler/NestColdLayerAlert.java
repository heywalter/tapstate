package io.tapstate.runtime.scheduler;

import io.tapstate.core.lifecycle.NestStateWindow;

/**
 * Where it is said that a nest namespace has stopped being served from memory, and where it is said that it
 * has started being served from memory again.
 *
 * <p>A port rather than a logger, for the same reason the statistics behind it are ports: this layer knows
 * when the thing happened and nothing about how it should be told. What reaches an operator is worded and
 * coded at the assembly layer, which is where such text belongs.
 *
 * <p>Both halves are reported, and each is reported once per crossing. What is being described is a
 * condition rather than an occurrence — it either holds right now or it does not — so repeating it on every
 * pass while it holds would say nothing new, and never withdrawing it would leave a reader believing a
 * namespace that recovered hours ago is still on disk.
 */
public interface NestColdLayerAlert {

    /** Says nothing at all, for a caller that has nowhere to report to. */
    NestColdLayerAlert NONE = new NestColdLayerAlert() {

        @Override
        public void crossed(String pipelineId, String namespace, NestStateWindow window) {
        }

        @Override
        public void cleared(String pipelineId, String namespace, NestStateWindow window) {
        }
    };

    /**
     * {@code namespace} of {@code pipelineId} is being served from the layer behind its memory rather than
     * from the memory, as {@code window} measured over the interval that established it.
     */
    void crossed(String pipelineId, String namespace, NestStateWindow window);

    /**
     * {@code namespace} of {@code pipelineId} is being served from its memory again, as {@code window}
     * measured over the interval that established it.
     */
    void cleared(String pipelineId, String namespace, NestStateWindow window);
}
