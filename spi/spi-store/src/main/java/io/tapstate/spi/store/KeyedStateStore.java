package io.tapstate.spi.store;

import java.util.Optional;

/**
 * The cold layer under a stateful operator: one opaque state document per key, within a namespace.
 *
 * <p>Distinct from {@link StateStore}, which keeps one epoch-fenced checkpoint per pipeline and is read
 * and written by the control plane. This one is on the data path — an entry per key a running operator
 * is partitioned by, written as that key is handled and read back when a key that is no longer in memory
 * is asked for again. It carries no epoch and no fencing: the caller above it is the single writer of any
 * one key, because the key is what the work was partitioned by in the first place.
 *
 * <p><b>There is deliberately no way to enumerate.</b> A store that could list a namespace would be asked
 * to on the way up from a restart, and the whole keyspace would be read to serve the first event — which
 * is the warm-up this layer exists to avoid. The absence is the contract: an implementation must not add
 * a listing method, and a caller must never need one. Dropping a namespace whole is the one bulk
 * operation, and it names the namespace rather than the keys in it, so it needs no listing either.
 *
 * <p>The value is bytes and stays bytes. What is inside is the running operator's business and changes
 * with it; an implementation that looked in would become a second place that has to agree about the
 * shape of something it does not own.
 */
public interface KeyedStateStore {

    /** The state held under {@code key} in {@code namespace}, or empty if there is none. */
    Optional<byte[]> load(String namespace, String key);

    /**
     * Stores {@code state} under {@code key}, replacing whatever was there. It must have reached durable
     * storage by the time this returns: the caller keeps no queue and no replica of what it handed over,
     * so a write that is still in flight is a write that a crash loses with nothing reporting it.
     */
    void save(String namespace, String key, byte[] state);

    /** Removes the entry under {@code key}, if there is one. Removing what is not there is not an error. */
    void delete(String namespace, String key);

    /**
     * Removes everything in {@code namespace}. This is how a pipeline that is being taken down for good
     * lets go of its state, and why nothing needs to be able to list the keys in order to drop them.
     */
    void dropNamespace(String namespace);
}
