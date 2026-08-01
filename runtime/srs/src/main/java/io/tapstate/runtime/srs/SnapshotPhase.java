package io.tapstate.runtime.srs;

import io.tapstate.core.event.Envelope;
import io.tapstate.spi.capture.CaptureBatch;
import io.tapstate.spi.capture.CaptureConfig;
import io.tapstate.spi.capture.CapturePort;
import io.tapstate.spi.capture.SourcePosition;
import io.tapstate.spi.store.SrsMetaStore;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The bounded snapshot phase of a capture. Reads the current rows once and passes them straight to the
 * downstream stage — every event is a snapshot read (op {@code r}) and none is buffered in the change
 * ring: the snapshot is absorbed by the idempotent sink, not replicated into the volatile SRS ring, and
 * no source replica is materialized.
 */
public final class SnapshotPhase {

    private SnapshotPhase() {
    }

    /**
     * Runs the snapshot phase for one {@code table}: records the cdc-start position for the chain, drains
     * the bounded snapshot read straight to {@code sink}, then marks the table's snapshot complete.
     * Returns the number of events passed through.
     *
     * <p>The two marks bracket the drain and answer different questions. The cdc-start position — the
     * source log position sampled at snapshot start — is recorded before the batch drains, so the cdc tail
     * that follows resumes from before the snapshot and the idempotent sink absorbs the overlap; no change
     * made while the snapshot runs is missed. Its presence therefore means the snapshot has <em>started</em>.
     * The completion mark is written only after the drain returns, so its presence means the table has been
     * read to exhaustion. A drain that fails partway marks nothing: an aborted snapshot is not a completed
     * one, and a reader treating a partial drain as exhausted would conclude rows are absent from the source
     * that were merely never read. Events are passed through one by one, never buffered in the change ring,
     * and the batch is always closed.
     *
     * <p>Seeding the chain's meta record is a separate lifecycle step; recording the cdc-start position on
     * an unseeded chain is a caller ordering error surfaced by the store.
     */
    public static long run(
            CapturePort port,
            CaptureConfig config,
            String miningChainId,
            String table,
            SourcePosition cdcStart,
            SrsMetaStore meta,
            Consumer<Envelope> sink) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(miningChainId, "miningChainId");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(cdcStart, "cdcStart");
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(sink, "sink");

        meta.setCdcStartPosition(miningChainId, cdcStart.token());
        long count = drain(port, config, sink);
        meta.markSnapshotComplete(miningChainId, table);
        return count;
    }

    /**
     * Drains the bounded snapshot read straight to {@code sink}, returning the number of events passed
     * through. Pure pass-through: it records no cdc-start position and touches no meta record — the path a
     * {@code snapshot_only} or srs-disabled read takes, where there is no shared chain a cdc tail resumes
     * against. Events go one by one, never buffered in the change ring, and the batch is always closed.
     */
    public static long drain(CapturePort port, CaptureConfig config, Consumer<Envelope> sink) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sink, "sink");

        long count = 0;
        try (CaptureBatch batch = port.snapshot(config)) {
            while (batch.hasNext()) {
                sink.accept(batch.next());
                count++;
            }
        }
        return count;
    }
}
