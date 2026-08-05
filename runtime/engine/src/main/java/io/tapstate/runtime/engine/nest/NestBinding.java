package io.tapstate.runtime.engine.nest;

import io.tapstate.runtime.engine.ReplayFloorFactory;
import java.io.Serializable;
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
 * <p>{@code tables} is asked while the graph is built and stays behind; the other three are carried to the
 * member that runs each vertex, which is why they are serializable.
 */
public record NestBinding(
        Function<String, NestTable> tables,
        NestStores stores,
        NestDeadLetter deadLetter,
        ReplayFloorFactory replayFloor) implements Serializable {

    /**
     * A binding with nothing to read a durable resume position through, for a job assembled without one.
     * Deleted roots then keep their record for as long as the job runs, which costs memory and never
     * correctness - forgetting one is an optimisation, and not taking it is always allowed.
     */
    public NestBinding(Function<String, NestTable> tables, NestStores stores, NestDeadLetter deadLetter) {
        this(tables, stores, deadLetter, ReplayFloorFactory.NONE);
    }

    /** Where each kind of nest vertex keeps its state. */
    public interface NestStores extends Serializable {

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
}
