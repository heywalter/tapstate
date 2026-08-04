package io.tapstate.runtime.engine;

import java.util.Objects;
import java.util.function.Function;

/**
 * What propagating a frontier through a job needs that the engine will not decide: which chain each of
 * the pipeline's sources reads. The chain is the stream name the source stamps on every event it emits,
 * and resolving a source id to it means resolving the source's connector and config - which stays with
 * whoever wired the pipeline, so the engine still never sees the table universe.
 *
 * <p>Numbering the chains onto the axes bounds travel on follows from this and nothing else, so there is
 * one answer to what a chain is called rather than a name here and a numbering somewhere else that could
 * disagree.
 *
 * <p>A job built without one propagates no bounds at all: every level keeps what it has promised to
 * itself, exactly as it did before there was anywhere to send it. That is a job whose frontier does not
 * advance, never one that advances too far.
 */
public record FrontierBinding(Function<String, String> sourceChains) {

    public FrontierBinding {
        Objects.requireNonNull(sourceChains, "sourceChains");
    }
}
