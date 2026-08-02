package io.tapstate.runtime.engine.nest;

import java.io.Serializable;
import java.util.function.Function;

/**
 * What the assembly root supplies for a nest node: where each embedded stream's table key comes from,
 * where each vertex keeps its state, and where changes that can never reach a document go.
 *
 * <p>All three are things the engine deliberately does not decide. Resolving an alias to a table would
 * mean knowing the table universe; choosing a store would mean choosing between a heap and a disk; and
 * choosing what happens to an unassemblable change is a product question, not a graph one.
 *
 * <p>{@code tables} is asked while the graph is built and stays behind; the other two are carried to the
 * member that runs each vertex, which is why they are serializable.
 */
public record NestBinding(
        Function<String, NestTable> tables,
        NestStores stores,
        NestDeadLetter deadLetter) implements Serializable {

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
