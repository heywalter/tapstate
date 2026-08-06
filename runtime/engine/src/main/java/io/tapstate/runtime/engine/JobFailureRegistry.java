package io.tapstate.runtime.engine;

import com.hazelcast.core.HazelcastInstance;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the exception that killed a pipeline's job, synchronously, at the moment a processor catches
 * it — rather than reconstructing one later from Jet's own terminal job state. Once a job's terminal
 * result is recorded, {@code JobResult.getFailureAsThrowable()} rebuilds a "mock throwable" (Hazelcast's
 * own javadoc) from the failure's text representation, with no cause chain; a coded exception a sink or
 * connector threw is unrecoverable through Jet's own API a short window after the job fails, since the
 * live in-memory result that still carries it is torn down once the job's terminal record is durable.
 * This registry is the fix: a processor records the real, unwrapped exception at its throw site before
 * Jet's tasklet machinery ever wraps or degrades it, and {@link Engine#failureOf} consults it first.
 *
 * <p>One instance lives per Hazelcast member, resolved through {@link HazelcastInstance#getUserContext()}
 * so both sides of the DAG boundary reach the same shared instance: {@link Engine} outside a job, and a
 * processor's {@code Processor.Context} inside one — the DAG itself must stay serializable, so nothing
 * captures a live reference to this registry across that boundary; each side resolves it fresh from the
 * one {@link HazelcastInstance} they both already hold.
 */
public final class JobFailureRegistry {

    private static final String USER_CONTEXT_KEY = JobFailureRegistry.class.getName();

    private final Map<String, Throwable> failures = new ConcurrentHashMap<>();

    private JobFailureRegistry() {
    }

    /** The registry shared by this member's engine and every processor its jobs run, created once. */
    public static JobFailureRegistry of(HazelcastInstance member) {
        return (JobFailureRegistry) member.getUserContext()
                .computeIfAbsent(USER_CONTEXT_KEY, ignored -> new JobFailureRegistry());
    }

    /** Records the cause a pipeline's job died of, overwriting whatever this pipeline last recorded. */
    public void record(String pipelineId, Throwable cause) {
        failures.put(pipelineId, cause);
    }

    /** The recorded cause for a pipeline, or empty when none was ever recorded, or it was cleared. */
    public Optional<Throwable> get(String pipelineId) {
        return Optional.ofNullable(failures.get(pipelineId));
    }

    /**
     * Forgets a pipeline's recorded failure. Called when a fresh run starts, so a cause recorded by a
     * previous run under the same pipeline id is never mistaken for the run now starting.
     */
    public void clear(String pipelineId) {
        failures.remove(pipelineId);
    }
}
