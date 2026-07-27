package io.tapstate.runtime.engine;

import io.tapstate.core.common.TapstateErrorCode;
import io.tapstate.core.common.Severity;

import java.util.Set;

/**
 * The {@code engine} domain's error codes: user-facing, diagnosable failures of a Jet job
 * operation on a pipeline — distinct from the {@code lifecycle} domain, which polices whether a
 * transition is legal before the engine is ever touched. These are converge-side execution
 * failures carried through the error-code system and rendered through the shared message catalog.
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
     * A pipeline's data-plane job died on its own, for a reason the product had not already coded at its
     * throw site: {@code pipeline} is the pipeline whose run died and {@code cause} is what it died of.
     * A fault that does carry its own code keeps that code instead — this is the last resort, so that a
     * dead job always names a reason rather than reporting a bare state change.
     */
    JOB_FAILED("engine.job-failed", Set.of("pipeline", "cause"));

    private final String code;
    private final Set<String> placeholders;

    EngineError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
