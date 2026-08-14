package io.tapstate.runtime.engine.nest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        Map<String, NestStore<ParkedSubtree>> parking = new ConcurrentHashMap<>();
        return new NestBinding.NestStores() {

            @Override
            public NestStore<ResolverState> forResolver(NestVertex vertex) {
                return new HeapNestStore<>();
            }

            @Override
            public NestStore<RootAssembly> forAssembler(NestVertex vertex) {
                return new HeapNestStore<>();
            }

            /**
             * Shared per name, unlike the two above. A parked subtree is written by whichever processor held
             * the document it left and read by whichever holds the one gaining it, so a fresh store per ask
             * would mean the hand-over never arrives - and the map behind this in a running system is one
             * map, reached by name from anywhere. The other two are read and written under the same key by
             * the same processor, so nothing there depends on the sharing.
             */
            @Override
            public NestStore<ParkedSubtree> forParking(NestVertex vertex) {
                return parking.computeIfAbsent(vertex.parkingMapName(), name -> new HeapNestStore<>());
            }
        };
    }
}
