package io.tapstate.runtime.scheduler;

import io.tapstate.core.lifecycle.FrontierStall;

/**
 * Where it is said that a chain's durable position has been pinned too long, and where it is said that it
 * is moving again.
 *
 * <p>A port rather than a logger, for the same reason the statistics behind it are ports: this layer knows
 * when the thing happened and nothing about how it should be told. What reaches an operator is worded and
 * coded at the assembly layer, which is where such text belongs.
 *
 * <p>Both halves are reported, and each once per crossing. What is described is a condition rather than an
 * occurrence — the position either is pinned right now or it is not — so repeating it every pass while it
 * holds would say nothing new, and never withdrawing it would leave a reader believing a chain that
 * started moving hours ago is still stuck.
 */
public interface FrontierStallAlert {

    /** Says nothing at all, for a caller that has nowhere to report to. */
    FrontierStallAlert NONE = new FrontierStallAlert() {

        @Override
        public void crossed(String pipelineId, FrontierStall stall) {
        }

        @Override
        public void cleared(String pipelineId, FrontierStall stall) {
        }
    };

    /**
     * A chain of {@code pipelineId} has had its durable position pinned longer than is worth tolerating,
     * as {@code stall} stood when it crossed.
     */
    void crossed(String pipelineId, FrontierStall stall);

    /**
     * A chain of {@code pipelineId} is advancing its durable position again, as {@code stall} stood when
     * it recovered.
     */
    void cleared(String pipelineId, FrontierStall stall);
}
