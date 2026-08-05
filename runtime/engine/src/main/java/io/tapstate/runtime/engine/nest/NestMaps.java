package io.tapstate.runtime.engine.nest;

import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MapStoreConfig;
import com.hazelcast.config.MaxSizePolicy;
import io.tapstate.spi.store.KeyedStateStore;

/**
 * What every map holding nest state is configured to be. The maps themselves are created on demand, by
 * name, as vertices start asking for them; this is the one place that decides what they are when they
 * appear, and it does so by a pattern over the name every nest namespace shares.
 *
 * <p>The alternative is not "no configuration" but the substrate's own defaults, and each of those is
 * wrong here in a way that does not announce itself:
 *
 * <ul>
 *   <li><b>A backup replica per entry.</b> Paid on every write, and redundant twice over: this state is
 *       rebuildable from the source, and the member that would hold the replica is the same one that
 *       holds the entry.</li>
 *   <li><b>Expiry.</b> A resolver mapping that expires stops answering for the children that point at
 *       it, and they wait forever for a parent that is still there. An assembly that expires is rebuilt
 *       from whatever arrives next and emitted as though it were whole.</li>
 *   <li><b>Eviction.</b> The same silent loss, reached by size rather than by time. Eviction is what
 *       turns a bounded heap into a bounded heap plus a disk - but only once something stands behind the
 *       map to load an evicted entry back from. Until then, evicting is losing.</li>
 * </ul>
 *
 * <p>Values are kept in their serialized form. Keeping them as objects sounds cheaper and is not under
 * the way these maps are read: a get returns a copy, so an object-format map serializes on the way out
 * where a binary one does not, and the copy is only avoided by an access path that never leaves the
 * partition thread. That access path is not the one in use, so the format follows the access rather
 * than the intuition.
 */
public final class NestMaps {

    /**
     * The prefix every nest state map name begins with. It is shared with the naming so that the pattern
     * and the names cannot drift apart: a rename on one side alone would leave every map on the
     * substrate defaults, which costs a replica per write and evicts nothing yet reports nothing either.
     */
    static final String NAMESPACE_PREFIX = "nest.";

    private NestMaps() {
    }

    /**
     * The configuration every nest state map takes with a store behind it: read through {@code store}
     * when a key that is not in memory is asked for, and written through it as a key is handled.
     * when a key that is not in memory is asked for, and written through it as a key is handled.
     *
     * <p>The write is through rather than behind, and that is the decision here rather than the default
     * that happens to agree with it. A queued write would be held in memory, and the only copy of that
     * queue would be a backup replica - which these maps deliberately do not keep. So a crash with a
     * queue outstanding loses the tail of it, and loses it silently: nothing failed, the entries simply
     * were never there. Delay buys throughput only where a replica makes the queue survivable.
     *
     * <p>Loading stays lazy, which here means nothing is loaded up front at all: the store answers the
     * "which keys do you have" question with none, so there is no keyspace to preload and a restart pays
     * only for the keys it is actually asked about.
     */
    public static MapConfig stateMaps(KeyedStateStore store) {
        return stateMaps().setMapStoreConfig(new MapStoreConfig()
                .setEnabled(true)
                .setWriteDelaySeconds(0)
                .setInitialLoadMode(MapStoreConfig.InitialLoadMode.LAZY)
                .setFactoryImplementation(new NestStateMapStore.Factory(store)));
    }

    /**
     * The configuration every nest state map takes, named by the pattern that matches all of them, with
     * nothing behind it: what it holds lives only as long as the member does. A namespace that must
     * diverge is configured under its own exact name, which wins over this pattern for that namespace
     * alone.
     */
    public static MapConfig stateMaps() {
        return new MapConfig(NAMESPACE_PREFIX + "*")
                .setBackupCount(0)
                .setAsyncBackupCount(0)
                .setInMemoryFormat(InMemoryFormat.BINARY)
                .setTimeToLiveSeconds(0)
                .setMaxIdleSeconds(0)
                .setStatisticsEnabled(true)
                .setEvictionConfig(new EvictionConfig()
                        .setEvictionPolicy(EvictionPolicy.NONE)
                        .setMaxSizePolicy(MaxSizePolicy.PER_NODE)
                        .setSize(Integer.MAX_VALUE));
    }
}
