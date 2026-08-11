package io.tapstate.spi.store;

import java.util.List;

/**
 * Where changes a stateful operator can never place in a document are kept.
 *
 * <p>This exists because the alternative is a number. A discarded change leaves a count and a sampled log
 * line today, and both are gone at the next restart, so "what was dropped" has no answer at the time it is
 * asked. The rows here were never going to appear in any document, which is exactly why nothing about the
 * output can stand in for them: a nest discarding a million rows and a nest discarding none produce the
 * same documents.
 *
 * <p><b>Unlike {@link KeyedStateStore}, this one is meant to be read.</b> That store forbids enumeration
 * because enumerating it would be a warm-up on the event path; here, listing is the entire point, and it
 * is affordable for the same reason — nothing on the event path reads this, only whoever is looking into
 * what a pipeline threw away.
 *
 * <p>Filed per element rather than per occurrence, so a replay leaves what it left the first time. No cap:
 * what accumulates here is bounded by how many distinct rows in the source point at nothing, which is a
 * property of the data rather than of how long the pipeline has run — and it sits in the same layer as the
 * state it came from, which has no cap either. A pipeline taken down for good lets go of it by namespace.
 */
public interface NestDeadLetterStore {

    /**
     * Files one discarded change, replacing whatever was filed for the same element. It must have reached
     * durable storage by the time this returns, for the same reason the state beside it must: a caller on
     * the event path keeps no replica of what it handed over.
     */
    void record(NestDeadLetterRecord record);

    /**
     * The changes filed for {@code namespace}, most recently discarded first, up to {@code limit}. An empty
     * list means nothing was discarded there — the honest reading, since a namespace that discarded nothing
     * and a namespace that was never asked about are the same from here.
     */
    List<NestDeadLetterRecord> read(String namespace, int limit);

    /** Removes everything filed for {@code namespace}, as a pipeline being taken down lets go of its state. */
    void dropNamespace(String namespace);
}
