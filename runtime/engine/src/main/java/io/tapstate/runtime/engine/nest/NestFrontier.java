package io.tapstate.runtime.engine.nest;

import io.tapstate.runtime.engine.ChainAxes;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * What passing a frontier through a nest node needs from outside it: the job-wide numbering of chains
 * onto the axes bounds travel on, and which chains reach each alias the node reads. Everything else
 * follows from the compiled tree - a cascade carries whatever its subtree carries, and a level waits on
 * every edge compiled to carry a chain before promising anything about it.
 *
 * <p>Both parts come from the same place, which is the point of carrying them together: a numbering taken
 * from one reading of the graph and an expected set taken from another could disagree about what a chain
 * is called, and then bounds about different chains would be combined as though they were one.
 *
 * <p>A node built without one propagates no bound at all - a frontier that stands still rather than one
 * that runs ahead.
 */
public record NestFrontier(ChainAxes axes, Function<String, List<String>> chainsOfAlias) {

    public NestFrontier {
        Objects.requireNonNull(axes, "axes");
        Objects.requireNonNull(chainsOfAlias, "chainsOfAlias");
    }
}
