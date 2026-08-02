package io.tapstate.runtime.engine.nest;

import com.hazelcast.function.FunctionEx;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.Processors;
import io.tapstate.core.event.Envelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Draws the vertices and edges one nest node compiles to. The shape was settled while compiling; this
 * only realises it, which is why nothing here decides anything about the tree.
 *
 * <p>Every edge into a vertex is partitioned by the same key and distributed, so the two kinds of thing
 * that meet on one key - the row that declares a mapping and the rows that ask about it - land on the
 * same member and the same partition without any vertex ever reaching across for state. The key is read
 * differently per edge: off a named field for rows arriving from a source, and off the routing key for
 * changes an upstream vertex already resolved.
 *
 * <p>Inbound ordinals are the compiled ones rather than the next free one, because the ordinal is how a
 * processor tells its own embed's rows from a child's. Where one alias resolves to several producers the
 * edge would have to fan in and the ordinal would stop being unique, so those are merged first and the
 * nest vertex still sees exactly one edge per stream.
 */
public final class NestDag {

    private NestDag() {
    }

    /**
     * Builds the node into {@code dag} and returns the vertex the rest of the pipeline reads from. A
     * passthrough nest builds one identity vertex fed by the root stream: it assembles nothing, so it
     * takes no state, no map and no thread of its own.
     */
    public static Vertex attach(DAG dag, NestTopology topology, String nodeId, String rootAlias,
            String outputStream, Function<String, List<Vertex>> upstream, NestBinding binding,
            ToIntFunction<Vertex> nextOutbound) {
        if (topology.isPassthrough()) {
            Vertex passthrough = dag.newVertex(nodeId, ProcessorMetaSupplier.forceTotalParallelismOne(
                    ProcessorSupplier.of(Processors.mapP(FunctionEx.identity()))));
            int ordinal = 0;
            for (Vertex source : upstream.apply(rootAlias)) {
                dag.edge(Edge.from(source, nextOutbound.applyAsInt(source)).to(passthrough, ordinal++));
            }
            return passthrough;
        }

        Map<List<String>, Vertex> built = new LinkedHashMap<>();
        Vertex assembler = null;
        for (NestVertex spec : topology.vertices()) {
            Vertex vertex = dag.newVertex(spec.name(), processorFor(spec, topology, binding, outputStream));
            built.put(spec.pathId(), vertex);
            for (NestInbound edge : spec.inbound()) {
                connect(dag, vertex, edge, built, upstream, nextOutbound);
            }
            assembler = vertex;
        }
        return assembler;
    }

    private static void connect(DAG dag, Vertex destination, NestInbound edge, Map<List<String>, Vertex> built,
            Function<String, List<Vertex>> upstream, ToIntFunction<Vertex> nextOutbound) {
        if (edge.isCascade()) {
            Vertex source = built.get(edge.pathId());
            if (source == null) {
                throw new IllegalStateException("cascade into " + destination.getName()
                        + " has no vertex for " + edge.pathId());
            }
            draw(dag, source, destination, edge.ordinal(), routedKey(), nextOutbound);
            return;
        }
        List<Vertex> sources = upstream.apply(edge.alias());
        if (sources == null || sources.isEmpty()) {
            throw new IllegalStateException("nest alias '" + edge.alias() + "' resolved to no vertex");
        }
        Vertex source = sources.size() == 1
                ? sources.get(0)
                : merged(dag, destination, edge, sources, nextOutbound);
        draw(dag, source, destination, edge.ordinal(), fieldKey(edge.keyFields()), nextOutbound);
    }

    /**
     * One passthrough that gathers several producers of one stream, so the nest vertex still sees a
     * single edge on the ordinal the compiler gave that stream.
     */
    private static Vertex merged(DAG dag, Vertex destination, NestInbound edge, List<Vertex> sources,
            ToIntFunction<Vertex> nextOutbound) {
        Vertex merge = dag.newVertex(destination.getName() + ":" + edge.alias(),
                ProcessorMetaSupplier.forceTotalParallelismOne(
                        ProcessorSupplier.of(Processors.mapP(FunctionEx.identity()))));
        int ordinal = 0;
        for (Vertex source : sources) {
            dag.edge(Edge.from(source, nextOutbound.applyAsInt(source)).to(merge, ordinal++));
        }
        return merge;
    }

    private static void draw(DAG dag, Vertex source, Vertex destination, int ordinal,
            FunctionEx<Object, Object> key, ToIntFunction<Vertex> nextOutbound) {
        dag.edge(Edge.from(source, nextOutbound.applyAsInt(source)).to(destination, ordinal)
                .partitioned(key).distributed());
    }

    private static ProcessorMetaSupplier processorFor(NestVertex spec, NestTopology topology,
            NestBinding binding, String outputStream) {
        NestBinding.NestStores stores = binding.stores();
        NestDeadLetter deadLetter = binding.deadLetter();
        List<EmbedSlot> slots = topology.slots();
        com.hazelcast.function.SupplierEx<Processor> supplier = spec.isAssembler()
                ? () -> new AssemblerProcessor(spec, slots, stores.forAssembler(spec), outputStream)
                : () -> new ResolverProcessor(spec, stores.forResolver(spec), deadLetter);
        return ProcessorMetaSupplier.of(ProcessorSupplier.of(supplier));
    }

    /** Reads the key off the fields a row carries it in. */
    private static FunctionEx<Object, Object> fieldKey(List<String> fields) {
        return item -> NestKeys.valuesOf(NestKeys.rowOf((Envelope) item), fields);
    }

    /** Reads the key an upstream vertex already resolved and routed by. */
    private static FunctionEx<Object, Object> routedKey() {
        return item -> ((KeyedElement) item).key();
    }
}
