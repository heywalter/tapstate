package io.tapstate.runtime.engine.nest;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.tapstate.runtime.engine.ReplayFloorFactory;
import java.io.Serializable;
import java.util.Objects;
import java.util.function.Function;

/**
 * What the assembly root supplies for a nest node: where each embedded stream's table key comes from,
 * where each vertex keeps its state, where changes that can never reach a document go, and how to read
 * back where a restart would resume.
 *
 * <p>All four are things the engine deliberately does not decide. Resolving an alias to a table would
 * mean knowing the table universe; choosing a store would mean choosing between a heap and a disk;
 * choosing what happens to an unassemblable change is a product question, not a graph one; and the durable
 * position a restart resumes from lives in a store the engine ring is not allowed to reach for itself.
 *
 * <p>{@code tables} and {@code ledger} are asked while the graph is built and stay behind; the other three
 * are carried to the member that runs each vertex, which is why they are serializable.
 */
public record NestBinding(
        Function<String, NestTable> tables,
        NestStores stores,
        NestDeadLetter deadLetter,
        ReplayFloorFactory replayFloor,
        NestStateLedger ledger) implements Serializable {

    /**
     * A binding with nothing to read a durable resume position through, for a job assembled without one.
     * Deleted roots then keep their record for as long as the job runs, which costs memory and never
     * correctness - forgetting one is an optimisation, and not taking it is always allowed.
     */
    public NestBinding(Function<String, NestTable> tables, NestStores stores, NestDeadLetter deadLetter) {
        this(tables, stores, deadLetter, ReplayFloorFactory.NONE);
    }

    /**
     * A binding that remembers nothing about where this nest last kept its state, and so compares nothing
     * on the way up. It is the right default only where the state does not outlive the job: a heap-held
     * nest starts empty every time, and there is nothing a renamed path could abandon.
     */
    public NestBinding(Function<String, NestTable> tables, NestStores stores, NestDeadLetter deadLetter,
            ReplayFloorFactory replayFloor) {
        this(tables, stores, deadLetter, replayFloor, NestStateLedger.NONE);
    }

    /** Where each kind of nest vertex keeps its state. */
    public interface NestStores extends Serializable {

        /**
         * Binds this factory to the member whose vertices it is about to serve, returning what to ask from
         * there on. It exists because the factory is serialized when the job is submitted while the thing a
         * distributed store is reached through is not: only coordinates travel, and the live handle is
         * picked up once the vertex is where it will run. A factory that needs nothing from the member
         * answers itself, which is why the default is to do so.
         */
        default NestStores bind(HazelcastInstance member) {
            return this;
        }

        /** The store for one resolver's mappings and the children waiting in it. */
        NestStore<ResolverState> forResolver(NestVertex vertex);

        /** The store for the assembler's documents. */
        NestStore<RootAssembly> forAssembler(NestVertex vertex);
    }

    /** Stores that keep everything on the heap of the member running the vertex, and outlive nothing. */
    public static NestStores onHeap() {
        return new NestStores() {

            @Override
            public NestStore<ResolverState> forResolver(NestVertex vertex) {
                return new HeapNestStore<>();
            }

            @Override
            public NestStore<RootAssembly> forAssembler(NestVertex vertex) {
                return new HeapNestStore<>();
            }
        };
    }

    /**
     * Stores backed by one distributed map per vertex, the map named by whatever name the topology computed
     * for that vertex. That name has until now been derived and never consulted; here is where it starts to
     * decide which entries a vertex addresses, and so where two vertices stop being able to answer each
     * other's keys.
     *
     * <p>The returned factory carries no member and must be {@link NestStores#bind bound} to one before it
     * is asked for a store. Asking too early is a wiring mistake rather than anything a user did, so it
     * fails on the spot rather than degrading to a store that quietly keeps nothing.
     */
    public static NestStores onMap() {
        return new MapNestStores(null);
    }

    /** The factory behind {@link #onMap()}: coordinates while it travels, a live handle once it lands. */
    private static final class MapNestStores implements NestStores {

        private static final long serialVersionUID = 1L;

        private final transient HazelcastInstance member;

        private MapNestStores(HazelcastInstance member) {
            this.member = member;
        }

        @Override
        public NestStores bind(HazelcastInstance member) {
            return new MapNestStores(Objects.requireNonNull(member, "member"));
        }

        @Override
        public NestStore<ResolverState> forResolver(NestVertex vertex) {
            return new MapNestStore<>(mapOf(vertex));
        }

        @Override
        public NestStore<RootAssembly> forAssembler(NestVertex vertex) {
            return new MapNestStore<>(mapOf(vertex));
        }

        private <S> IMap<Object, S> mapOf(NestVertex vertex) {
            if (member == null) {
                throw new IllegalStateException(
                        "nest stores were asked for " + vertex.name() + " before being bound to a member");
            }
            return member.getMap(vertex.mapName());
        }
    }
}
