package io.tapstate.e2e;

import io.tapstate.core.lifecycle.PipelineState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a specification against one tier binding.
 *
 * <p>Waiting is condition-driven and bounded: {@code await} polls a matcher until it holds or the
 * bound expires, and an expired bound reports how long it actually waited alongside what it expected
 * and what it last read. There is no fixed-duration sleep anywhere in a run - a sleep long enough to
 * be reliable is a sleep that wastes that long on every green run, and it is never quite reliable
 * anyway.
 */
public final class E2eExecutor {

    private final TierBinding binding;
    private final PipelineLoader pipelineLoader;
    private final Duration timeout;
    private final Duration pollInterval;

    public E2eExecutor(
            TierBinding binding, PipelineLoader pipelineLoader, Duration timeout, Duration pollInterval) {
        this.binding = binding;
        this.pipelineLoader = pipelineLoader;
        this.timeout = timeout;
        this.pollInterval = pollInterval;
    }

    public void execute(Envelope envelope) {
        String pipelineId = pipelineLoader.resolvePipelineId(envelope.pipeline());
        provision(envelope.setup());
        for (Seed seed : envelope.seed()) {
            binding.seed(seed.table(), seed.rows());
        }
        // Discovery trails the seed: a source model is read out of what the source holds, and the seed is
        // what puts it there.
        envelope.setup().discover().forEach(binding::discoverSchema);
        applyResources(envelope.setup(), envelope.pipeline());
        for (Step step : envelope.steps()) {
            execute(step, pipelineId);
        }
    }

    /**
     * Strict order: register, read, seed, discover, apply. Each step is where it is because the one
     * before it is what makes it answerable.
     *
     * <p>A resource may not be applied before the connector it names is registered. The resources
     * themselves go in one batch, because that is the closure the product resolves references within.
     *
     * <p>Discovery sits between the seed and the apply, pinned from both sides. It cannot precede the
     * seed: a model is discovered from what the source holds, and the harness's seed is what
     * materializes the table - it drops and rewrites it, so before a seed there is nothing to discover.
     * Discovering an absent table returns an empty model and leaves the sink with no target and no key
     * to upsert on, quietly, because an empty model is what an empty source honestly looks like. And it
     * cannot follow the apply: a pipeline whose expression reads a row field is refused unless the
     * sources feeding it were discovered first, so an apply that ran before the discovery would be
     * refused rather than applied.
     *
     * <p>Which is why reading the resources is its own step. The seed dials the source's own address and
     * the discovery names its connector and settings - both stated only in the resource files, and both
     * needed before the product has been told anything at all.
     */
    private void provision(Setup setup) {
        setup.connectors().forEach(binding::registerConnector);
        if (!setup.apply().isEmpty()) {
            // Read, do not apply. What the resources declare is needed before the product is told
            // anything: the seed dials the source's own address, and a discovery is asked for with the
            // connector and settings the source states.
            binding.readResources(setup.apply());
        }
    }

    /**
     * The batch is the setup's resources plus the pipeline the envelope names. The pipeline is not
     * optional to apply: every specification declares one, and every specification drives it - so a run
     * that applied only what {@code setup.apply} listed would leave the steps addressing a pipeline the
     * product was never told about, and the specification would fail at its first verb over a resource
     * that reads perfectly correct.
     *
     * <p>Listing it under {@code setup.apply} anyway is allowed and is what the checked-in examples do,
     * so it is added only when absent: the batch is a closure the product resolves ids within, and one
     * id submitted twice is not a closure. Which spelling an author picks cannot change what is sent.
     *
     * <p>One batch, not two. The pipeline names its source and target by id, and the product resolves
     * references within the set submitted together - so applying it after its endpoints, in a round trip
     * of its own, is a pipeline referencing ids that are not in its own batch.
     *
     * <p>The pipeline is applied but never read in {@link #provision}: the read is there to learn an
     * endpoint's own address and settings before the product has been told anything, and a pipeline
     * states neither.
     */
    private void applyResources(Setup setup, String pipeline) {
        List<String> resources = new ArrayList<>(setup.apply());
        if (!resources.contains(pipeline)) {
            resources.add(pipeline);
        }
        binding.applyResources(resources);
    }

    private void execute(Step step, String pipelineId) {
        switch (step) {
            case Step.Lifecycle lifecycle -> binding.drive(pipelineId, lifecycle.verb());
            case Step.Cdc cdc -> binding.cdc(cdc.table(), cdc.op(), cdc.rows());
            case Step.Assertion assertion -> check(assertion.matcher(), pipelineId);
            case Step.Await await -> await(await.matcher(), pipelineId);
        }
    }

    private void check(Matcher matcher, String pipelineId) {
        mismatch(matcher, pipelineId)
                .ifPresent(
                        mismatch -> {
                            throw new AssertionError(mismatch);
                        });
    }

    private void await(Matcher matcher, String pipelineId) {
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        while (true) {
            Optional<String> mismatch = mismatch(matcher, pipelineId);
            if (mismatch.isEmpty()) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError(
                        "timed out after "
                                + Duration.ofNanos(System.nanoTime() - start)
                                + " (bound "
                                + timeout
                                + "); "
                                + mismatch.get());
            }
            sleep(pollInterval);
        }
    }

    /** The reading that falsifies the matcher, or empty when it holds. */
    private Optional<String> mismatch(Matcher matcher, String pipelineId) {
        return switch (matcher) {
            case Matcher.Count count -> countMismatch(count.expected());
            case Matcher.State state -> stateMismatch(state.expected(), pipelineId);
            case Matcher.ErrorCount errorCount -> errorCountMismatch(errorCount.expected(), pipelineId);
        };
    }

    private Optional<String> countMismatch(Map<TableAlias, Long> expected) {
        List<String> mismatches = new ArrayList<>();
        expected.forEach(
                (table, rows) -> {
                    long actual = binding.count(table);
                    if (actual != rows) {
                        mismatches.add(table + " expected " + rows + ", found " + actual);
                    }
                });
        return mismatches.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", mismatches));
    }

    /**
     * A pipeline that has published no observation yet reads as a mismatch, never as a failure: that is
     * the window between recording an intent and the first convergence pass, and an {@code await} exists
     * to sit through exactly it. The unpublished read is named in the mismatch rather than folded into
     * the states, because "nothing was ever published" and "the wrong state was published" fail for
     * different reasons and send an author looking in different places.
     */
    private Optional<String> stateMismatch(PipelineState expected, String pipelineId) {
        Optional<PipelineState> actual = binding.state(pipelineId);
        if (actual.filter(published -> published == expected).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(
                pipelineId
                        + " expected "
                        + expected
                        + ", found "
                        + actual.map(Object::toString).orElse("no published observation"));
    }

    /**
     * Reads the same unobserved window as {@link #stateMismatch} the same way: a pipeline that has published
     * no observation reads as a mismatch, never as a failure, so an {@code await} sits through the window
     * between a start intent and the first convergence pass. "nothing published" is named apart from a wrong
     * count because the two fail for different reasons.
     */
    private Optional<String> errorCountMismatch(long expected, String pipelineId) {
        Optional<Long> actual = binding.errorCount(pipelineId);
        if (actual.filter(published -> published == expected).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(
                pipelineId
                        + " expected error count "
                        + expected
                        + ", found "
                        + actual.map(Object::toString).orElse("no published observation"));
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(interval.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a condition", e);
        }
    }
}
