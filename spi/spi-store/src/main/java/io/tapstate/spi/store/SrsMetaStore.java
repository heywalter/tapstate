package io.tapstate.spi.store;

import java.util.List;
import java.util.Optional;

/**
 * The durable SRS coordination store: one {@link SrsMeta} record per mining chain — the offset, consumer
 * cursor and schema truth that outlives the in-memory change ring. A pure interface over the store's own
 * value model (rule R2); a store backend persists it, and positions travel as opaque tokens, never as a
 * connector type.
 *
 * <p>{@link #create} seeds a chain's first record, carrying only the pass-through retention config. It is
 * insert-only: it must not overwrite an existing record, because doing so would discard the offset,
 * consumer-cursor and schema history the chain has accumulated. Seeding a chain that already has a record
 * is a caller ordering error; a caller that needs to know can {@link #read} first.
 *
 * <p>The mutators each update one facet of an already-seeded record — the source read offset, one
 * consumer's cursor, the cdc start position, or the schema history. A mutate on a chain that has not been
 * seeded is a caller ordering error, surfaced bare (an {@code IllegalStateException}), not laundered into
 * a coded diagnostic that would hide the defect. The durable-frontier bound on a source-read-offset
 * advance (an advance must not pass the slowest consumer's acked position) is the caller's concern; this
 * store persists the value the caller resolved.
 */
public interface SrsMetaStore {

    /** Returns the meta record for a mining chain, or empty if the chain has not been seeded. */
    Optional<SrsMeta> read(String miningChainId);

    /**
     * Seeds a mining chain's first record — no offsets, no consumers, no schema history, carrying only
     * the pass-through {@code retention} config (which may be absent). Insert-only: it must not overwrite
     * an existing record (which would discard the chain's accumulated offset / cursor / schema truth).
     */
    void create(String miningChainId, String retention);

    /**
     * Sets the chain's source read offset to {@code sourceReadOffset}, an opaque source capture
     * watermark. The durable-frontier bound is the caller's concern; this persists the resolved value.
     * A mutate on an unseeded chain is a caller ordering error.
     */
    void advanceSourceReadOffset(String miningChainId, String sourceReadOffset);

    /**
     * Inserts or replaces one consumer pipeline's cursor on the chain, keyed by its pipeline id. A
     * mutate on an unseeded chain is a caller ordering error.
     */
    void upsertConsumerOffset(String miningChainId, ConsumerOffset offset);

    /**
     * Advances one consumer pipeline's read cursor into one table's change ring — a scoped set of that
     * consumer's {@code perTableSeq} entry for the table alone. It touches only the read cursor, so a
     * reader advancing here never clobbers the {@code sinkAckedSrcpos} the pipeline's sink writes to the
     * same consumer record: the read cursor and the sink-ack are independent writers of one consumer, of
     * different lifetime. It creates the consumer entry when the pipeline has none yet, so a reader may
     * advance before the sink first acks. A mutate on an unseeded chain is a caller ordering error.
     */
    void advanceConsumerReadSeq(String miningChainId, String pipelineId, String table, long lastReadSeq);

    /**
     * Advances one consumer pipeline's durable sink-acked source position on the chain — a scoped set of
     * that consumer's {@code sinkAckedSrcpos} alone. It touches only the sink-ack, so a sink advancing here
     * never clobbers the {@code perTableSeq} read cursor the pipeline's reader writes to the same consumer
     * record: the sink-ack and the read cursor are independent writers of one consumer, of different
     * lifetime. It creates the consumer entry when the pipeline has none yet, so a sink may ack before the
     * reader first publishes a cursor. The caller advances a monotonically non-decreasing position (the
     * sink's contiguous acked prefix); this store persists the value the caller resolved. A mutate on an
     * unseeded chain is a caller ordering error.
     */
    void advanceSinkAckedSrcpos(String miningChainId, String pipelineId, String srcpos);

    /**
     * Sets the chain's cdc start position — the opaque position the cdc tail starts from, recorded at
     * the snapshot-to-cdc seam. A mutate on an unseeded chain is a caller ordering error.
     */
    void setCdcStartPosition(String miningChainId, String cdcStartPosition);

    /**
     * Appends a version to the chain's append-only schema history. A mutate on an unseeded chain is a
     * caller ordering error.
     */
    void appendSchemaVersion(String miningChainId, SchemaVersion version);

    /**
     * Lists the id of every mining chain that carries a cursor for {@code pipelineId} — exactly the
     * chains a departing consumer still has to be detached from. It answers from the chains' own records
     * rather than from the consumer's side, so a caller that cannot derive which chains a pipeline reads
     * (chain identity is resolved where captures are built, not where artifacts are removed) can still
     * detach from all of them, and a chain the pipeline never joined is never touched.
     *
     * <p>It returns ids only, never reconstructed records, so enumerating never fails on a single corrupt
     * document.
     */
    List<String> miningChainIdsWithConsumer(String pipelineId);

    /**
     * Removes one consumer pipeline's cursor from a chain, leaving the chain itself and every other
     * consumer untouched. A departing pipeline <em>must</em> be detached: its cursor is folded into two
     * independent minimums — the durable frontier over every consumer's acked position, and the cdc
     * write headroom over every consumer's read cursor — and a consumer that will never advance again
     * pins both, permanently and silently, for every other pipeline on the shared chain.
     *
     * <p>Unlike the other mutators, this one is idempotent rather than an ordering error on an unseeded
     * chain: a detach states the end condition "this consumer holds nothing here", which an absent chain
     * and an absent cursor already satisfy. Refusing them would let one benign race abort a removal
     * partway and leave the consumer attached to the chains not yet reached — the very residue this
     * exists to prevent.
     */
    void detachConsumer(String miningChainId, String pipelineId);
}
