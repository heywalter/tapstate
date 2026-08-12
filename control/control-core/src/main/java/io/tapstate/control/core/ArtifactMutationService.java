package io.tapstate.control.core;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.dsl.ReferenceGraph;
import io.tapstate.core.lifecycle.DesiredState;
import io.tapstate.core.lifecycle.PipelineState;
import io.tapstate.core.lifecycle.StateJson;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.Resource;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.DesiredStore;
import io.tapstate.spi.store.ObservationStore;
import io.tapstate.spi.store.SrsMetaStore;
import io.tapstate.spi.store.StateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Removal of an applied resource, one path for every kind. Deletion is real: the document leaves the
 * store, so no read path has to learn to filter a tombstone, and the id is free to be applied again
 * immediately.
 *
 * <p>Two grounds refuse a deletion, and both are judged <em>before</em> anything is written, so a
 * refusal leaves the store byte-for-byte as it was:
 *
 * <ul>
 *   <li><b>Still referenced</b> — another stored resource points at the id. The referrers travel back
 *       in the failure so the caller can act without a second query. Nothing cascades: removing the
 *       referrers is the caller's decision, not a side effect of this one.</li>
 *   <li><b>Running pipeline</b> — the id is a pipeline that is running or is about to. Both halves of
 *       the lifecycle are read, because either one alone lets a live pipeline through: the desired
 *       state alone passes a pipeline whose stop has been requested but not yet reached, which is
 *       still executing; the actual state alone passes one whose start has been requested but not yet
 *       reached, which the next convergence would then raise from an artifact that no longer
 *       exists.</li>
 * </ul>
 *
 * <p>The removal itself is the store's atomic conditional delete, so the content hash the caller read
 * is checked and the document removed as one indivisible step — a writer holding a stale version can
 * never remove what it did not see. The precondition is mandatory here, unlike on apply: a delete
 * carries an id and nothing else, so without it the caller is discarding a version it never looked at.
 *
 * <p>Removing a pipeline reclaims the bookkeeping that belongs to that pipeline alone — its desired
 * intent, its checkpoint, its observation — and detaches its cursor from every shared mining chain that
 * carries one. Nothing that another pipeline may share is touched: the chains themselves, the nest
 * state and the target data all outlive the artifact. Detaching is not tidiness — a departed consumer's
 * cursor is folded into two independent minimums that would otherwise pin the shared chain's durable
 * frontier and cdc write headroom permanently, stalling every other pipeline on it without an error.
 */
public final class ArtifactMutationService {

    /** Actual states a pipeline is at rest in; any other means it is still executing. */
    private static final Set<PipelineState> RESTING = Set.of(
            PipelineState.NEW, PipelineState.STOPPED, PipelineState.COMPLETED, PipelineState.FAILED);

    /** Desired states that will drive a pipeline back up, whatever it is doing right now. */
    private static final Set<PipelineState> HEADED_UP = Set.of(
            PipelineState.RUNNING, PipelineState.PAUSED);

    private final ArtifactStore store;
    private final DesiredStore desired;
    private final StateStore state;
    private final ObservationStore observations;
    private final SrsMetaStore srsMeta;
    private final AuditGate auditGate;

    public ArtifactMutationService(
            ArtifactStore store,
            DesiredStore desired,
            StateStore state,
            ObservationStore observations,
            SrsMetaStore srsMeta,
            AuditGate auditGate) {
        this.store = Objects.requireNonNull(store, "store");
        this.desired = Objects.requireNonNull(desired, "desired");
        this.state = Objects.requireNonNull(state, "state");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.srsMeta = Objects.requireNonNull(srsMeta, "srsMeta");
        this.auditGate = Objects.requireNonNull(auditGate, "auditGate");
    }

    /**
     * Removes the stored artifact {@code id}, provided {@code expectedContentHash} is the version the
     * caller read and neither refusal ground holds, then reclaims a pipeline's dependent bookkeeping.
     * {@code principal} is the identity the removal is attributed to.
     *
     * <p>A refusal happens before anything is written. A failure to reclaim happens after the artifact is
     * already gone and is reported rather than swallowed, so the residue is visible to whoever has to
     * clear it; it never puts the artifact back, because a removal the caller was told succeeded must not
     * silently undo itself.
     */
    public void delete(String principal, String id, String expectedContentHash) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(id, "id");
        if (expectedContentHash == null) {
            throw error(ArtifactError.PRECONDITION_REQUIRED, Map.of("id", id));
        }

        List<Resource> stored = store.list();
        Resource target = stored.stream()
                .filter(resource -> resource.id().equals(id))
                .findFirst()
                .orElseThrow(() -> error(ArtifactError.NOT_FOUND, Map.of("id", id)));

        refuseWhenReferenced(id, stored);
        if (target instanceof PipelineResource) {
            refuseWhenNotStopped(id);
        }

        // Both grounds are judged above, outside the gate, so a refusal writes no audit record and leaves
        // the store untouched. What the gate wraps is the destruction itself: the record lands before the
        // document does, so an audit backend that is down refuses the removal rather than destroying a
        // resource that leaves no trace behind.
        // The declared version travels with the record, not the one the store turns out to hold: this
        // runs before the compare below, and on the branch where that compare refuses, the version the
        // caller offered is the only one that describes the attempt.
        AuditContext audit = new AuditContext(principal, id, expectedContentHash);
        auditGate.dispatch(ControlOperations.ARTIFACT_DELETE, audit, () -> {
            switch (store.delete(id, expectedContentHash)) {
                case DELETED -> {
                }
                case NOT_FOUND -> throw error(ArtifactError.NOT_FOUND, Map.of("id", id));
                case VERSION_CONFLICT -> throw error(ArtifactError.VERSION_CONFLICT, Map.of("id", id));
                default -> throw new IllegalStateException("unexpected artifact mutation outcome for delete");
            }

            if (target instanceof PipelineResource) {
                reclaim(id);
            }
            return null;
        });
    }

    /**
     * Reclaims everything a removed pipeline owns. Every step is attempted even after one fails, and the
     * failures are reported together at the end: aborting at the first would leave the untouched steps'
     * residue behind on top of the failure, and the artifact is already gone by now so no caller can
     * simply run the removal again to finish the job.
     *
     * <p>The shared chains are detached first because theirs is the only residue that harms a
     * <em>different</em> pipeline; if the process dies mid-reclaim, the damage that has been contained is
     * the one that was not this pipeline's alone to suffer.
     */
    private void reclaim(String id) {
        List<RuntimeException> failures = new ArrayList<>();
        attempt(failures, () -> detachFromEveryChain(id));
        attempt(failures, () -> desired.delete(id));
        attempt(failures, () -> state.delete(id));
        attempt(failures, () -> observations.delete(id));
        if (failures.isEmpty()) {
            return;
        }
        RuntimeException first = failures.get(0);
        failures.stream().skip(1).forEach(first::addSuppressed);
        throw first;
    }

    /**
     * Detaches the pipeline's cursor from every chain that carries one. The chains are asked which of
     * them hold it, rather than derived from what the pipeline reads: chain identity is resolved where
     * captures are built, and a cursor left behind by an earlier shape of the pipeline would be invisible
     * to any derivation from its current one.
     */
    private void detachFromEveryChain(String id) {
        for (String miningChainId : srsMeta.miningChainIdsWithConsumer(id)) {
            srsMeta.detachConsumer(miningChainId, id);
        }
    }

    /** Runs one reclaim step, collecting a coded or runtime failure instead of ending the reclaim. */
    private static void attempt(List<RuntimeException> failures, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    private void refuseWhenReferenced(String id, List<Resource> stored) {
        List<String> referrers = ReferenceGraph.of(stored).referencedBy(id).stream()
                .map(ReferenceGraph.Edge::id)
                .sorted()
                .toList();
        if (!referrers.isEmpty()) {
            throw error(ArtifactError.IN_USE, Map.of("id", id, "referrers", referrers));
        }
    }

    /**
     * Refuses a pipeline that is executing or is headed back up. A pipeline with neither document has
     * never run, which is the clean case rather than an unknown one.
     */
    private void refuseWhenNotStopped(String id) {
        PipelineState actual = state.read(id)
                .map(checkpoint -> StateJson.parse(checkpoint.stateJson()))
                .orElse(PipelineState.NEW);
        PipelineState intent = desired.read(id)
                .map(DesiredState::targetState)
                .orElse(PipelineState.NEW);
        if (!RESTING.contains(actual) || HEADED_UP.contains(intent)) {
            throw error(
                    ArtifactError.PIPELINE_NOT_STOPPED,
                    Map.of("id", id, "actual", actual.name(), "desired", intent.name()));
        }
    }

    private static TapstateException error(ArtifactError code, Map<String, Object> args) {
        return new TapstateException(code, args, null);
    }
}
