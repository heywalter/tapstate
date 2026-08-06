package io.tapstate.app;

import io.tapstate.core.lifecycle.TableSnapshot;

import java.util.Map;
import java.util.Optional;

/**
 * Runs a pipeline's source-side capture alongside its Jet job. When a pipeline starts, its cdc capture must
 * run so the per-table change rings its topology reads are actually filled; when it stops, that capture must
 * be torn down so no capture daemon leaks. The actuator drives both this and the engine, composing the two
 * so a start fills the ring before the job reads it and a stop stops the job before the capture behind it.
 */
interface PipelineCaptureCoordinator {

    /** Starts the cdc capture for every source the pipeline reads, retaining the live handles for a later stop. */
    void startCapture(String pipelineId);

    /** Stops the cdc capture started for the pipeline, tearing down each source run and releasing its chain. */
    void stopCapture(String pipelineId);

    /**
     * The failure a running pipeline's cdc capture died with, or empty while it is healthy. The cdc stream runs
     * on its own thread feeding the ring the Jet job reads, so a tail that dies leaves the job running over a
     * quiet ring; this is how the actuator seam surfaces that death for the converge loop to act on. A
     * coordinator that runs no capture reports none.
     */
    default Optional<Throwable> captureFailure(String pipelineId) {
        return Optional.empty();
    }

    /**
     * How far each of the pipeline's tables got through its initial load, keyed by table, or empty when no
     * capture is running for it. A coordinator that runs no capture reports none.
     *
     * <p>What this reports is the finished load, not a live position in one: a table's bounded snapshot read
     * drains in one blocking pass, so its row count exists only once that pass returns. Until then the table
     * is simply absent, which the read face publishes as unavailable rather than as a table at zero rows.
     */
    default Map<String, TableSnapshot> snapshotProgress(String pipelineId) {
        return Map.of();
    }
}
