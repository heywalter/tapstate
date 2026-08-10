package io.tapstate.runtime.engine;

import io.tapstate.core.common.TapstateErrorCode;
import io.tapstate.core.common.Severity;

import java.util.Set;

/**
 * The {@code engine} domain's error codes: user-facing, diagnosable failures of running a pipeline —
 * both of a job operation on it (submit / suspend / resume / cancel) and of the data plane the job
 * carries, such as an ordering value the engine's own frontier transport cannot encode. Distinct from
 * the {@code lifecycle} domain, which polices whether a transition is legal before the engine is ever
 * touched. These are converge-side execution failures carried through the error-code system and
 * rendered through the shared message catalog.
 *
 * <p>{@code placeholders()} is the named-argument contract: every throw site supplies a value for
 * each name, and the build-time placeholder gate checks the catalog templates against it.
 */
public enum EngineError implements TapstateErrorCode {

    /**
     * A suspend / resume named a pipeline that has no running job to act on: {@code pipeline} is the
     * id the caller gave.
     */
    NO_SUCH_JOB("engine.no-such-job", Set.of("pipeline")),

    /**
     * A change's ordering value is outside the range the frontier transport encodes it in: {@code chain}
     * is the chain it belongs to, {@code epoch} and {@code seq} the generation and position that did not
     * fit. A capacity line of the transport rather than a defect — the widths are a build-time choice.
     */
    FRONTIER_ORDER_NOT_ENCODABLE("engine.frontier-order-not-encodable",
            Set.of("chain", "epoch", "seq")),

    /**
     * A pipeline's data-plane job died on its own, for a reason the product had not already coded at its
     * throw site: {@code pipeline} is the pipeline whose run died and {@code cause} is what it died of.
     * A fault that does carry its own code keeps that code instead — this is the last resort, so that a
     * dead job always names a reason rather than reporting a bare state change.
     */
    JOB_FAILED("engine.job-failed", Set.of("pipeline", "cause")),

    /**
     * Running: a chain's durable position has stopped moving for long enough to be worth saying so, while
     * nothing else about the pipeline has changed. {@code chain} is the chain, {@code minutes} how long it
     * has been pinned, {@code gap} how far the bound combined for it has run on meanwhile and
     * {@code cause} which of the two pins it is — held back by changes still pending upstream, or starved
     * of positions to advance to. The two are worked on from opposite ends, which is why the reading that
     * tells them apart is carried rather than only the duration.
     *
     * <p>A warning because nothing failed: the pipeline runs, its queues are short and its error count is
     * zero. That is exactly why it is said out loud — what a pinned position consumes is the source's log
     * retention, and when that rotates past it the pipeline has still not failed, it has merely lost the
     * ability to resume, taking every pipeline mining the same chain with it.
     */
    FRONTIER_PINNED("engine.frontier-pinned",
            Set.of("chain", "minutes", "gap", "cause"), Severity.WARNING);

    private final String code;
    private final Set<String> placeholders;
    private final Severity severity;

    EngineError(String code, Set<String> placeholders) {
        this(code, placeholders, Severity.ERROR);
    }

    EngineError(String code, Set<String> placeholders, Severity severity) {
        this.code = code;
        this.placeholders = placeholders;
        this.severity = severity;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return severity;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
