package io.tapstate.runtime.engine.nest;

/**
 * Nest stores that keep everything on the heap of the member running the vertex, for cases whose subject
 * is not durability.
 *
 * <p>It sits in test sources on purpose. A running system has nowhere to get this shape from: the member
 * declares state maps only where there is a store behind them, so the compiler - not a convention - is
 * what keeps a pipeline off state that dies with the process.
 */
public final class HeapNestStores {

    private HeapNestStores() {
    }

    /** Stores that keep everything on the heap of the member running the vertex, and outlive nothing. */
    public static NestBinding.NestStores onHeap() {
        return new NestBinding.NestStores() {

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
