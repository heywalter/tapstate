package io.tapstate.control.core;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.lifecycle.CasOutcome;
import io.tapstate.core.lifecycle.CheckpointDoc;
import io.tapstate.core.lifecycle.DesiredState;
import io.tapstate.core.lifecycle.PipelineState;
import io.tapstate.core.lifecycle.StateJson;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.ServeResource;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.TransformBody;
import io.tapstate.core.model.TransformResource;
import io.tapstate.core.model.ViewResource;
import io.tapstate.core.model.SourceMode;
import io.tapstate.core.model.canonical.CanonicalHash;
import io.tapstate.core.model.canonical.CanonicalWriter;
import io.tapstate.spi.store.ArtifactMutation;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.DesiredStore;
import io.tapstate.spi.store.StateStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactMutationServiceTest {

    private static final String PRINCIPAL = "alice";

    private final InMemoryArtifactStore store = new InMemoryArtifactStore();
    private final InMemoryDesiredStore desired = new InMemoryDesiredStore();
    private final InMemoryStateStore state = new InMemoryStateStore();
    private final ArtifactMutationService service =
            new ArtifactMutationService(store, desired, state);

    @Test
    void deletesAnyKindThroughTheOneVerbAndRemovesOnlyThatArtifact() {
        List<Resource> kinds = List.of(
                source("orders"),
                pipeline("flow"),
                transform("mask"),
                view("orders_view"),
                serve("orders_api"));
        kinds.forEach(store::save);

        for (Resource resource : kinds) {
            service.delete(PRINCIPAL, resource.id(), hash(resource));
            assertThat(store.get(resource.id())).isEmpty();
        }
        assertThat(store.list()).isEmpty();
    }

    @Test
    void deleteWithoutAPreconditionIsRefusedWithTheStoreUntouched() {
        SourceResource orders = source("orders");
        store.save(orders);

        assertArtifactError(
                () -> service.delete(PRINCIPAL, "orders", null),
                ArtifactError.PRECONDITION_REQUIRED,
                Map.of("id", "orders"));
        assertThat(store.get("orders")).isPresent();
    }

    @Test
    void deleteOfAnUnknownIdIsRefusedAsNotFound() {
        assertArtifactError(
                () -> service.delete(PRINCIPAL, "ghost", "0".repeat(64)),
                ArtifactError.NOT_FOUND,
                Map.of("id", "ghost"));
    }

    @Test
    void deleteWithAStaleHashIsRefusedWithTheStoredBytesUnchanged() {
        SourceResource orders = source("orders");
        store.save(orders);
        String before = hash(store.get("orders").orElseThrow());

        assertArtifactError(
                () -> service.delete(PRINCIPAL, "orders", "0".repeat(64)),
                ArtifactError.VERSION_CONFLICT,
                Map.of("id", "orders"));
        assertThat(hash(store.get("orders").orElseThrow())).isEqualTo(before);
    }

    @Test
    void referencedDeleteIsRefusedWithSortedReferrersAndNoCascade() {
        SourceResource orders = source("orders");
        store.save(orders);
        store.save(pipelineReading("zeta", "orders"));
        store.save(pipelineReading("alpha", "orders"));

        assertArtifactError(
                () -> service.delete(PRINCIPAL, "orders", hash(orders)),
                ArtifactError.IN_USE,
                Map.of("id", "orders", "referrers", List.of("alpha", "zeta")));
        assertThat(store.get("orders")).isPresent();
        assertThat(store.get("alpha")).isPresent();
        assertThat(store.get("zeta")).isPresent();
    }

    @Test
    void aPipelineThatHasNeverRunIsDeletable() {
        PipelineResource flow = pipeline("flow");
        store.save(flow);

        service.delete(PRINCIPAL, "flow", hash(flow));

        assertThat(store.get("flow")).isEmpty();
    }

    @Test
    void aRunningPipelineIsRefusedEvenAfterStopWasRequested() {
        PipelineResource flow = pipeline("flow");
        store.save(flow);
        state.put("flow", PipelineState.RUNNING);
        desired.put("flow", PipelineState.STOPPED);

        assertArtifactError(
                () -> service.delete(PRINCIPAL, "flow", hash(flow)),
                ArtifactError.PIPELINE_NOT_STOPPED,
                Map.of("id", "flow", "actual", "RUNNING", "desired", "STOPPED"));
        assertThat(store.get("flow")).isPresent();
    }

    @Test
    void aStoppedPipelineThatWasAskedToStartIsRefused() {
        PipelineResource flow = pipeline("flow");
        store.save(flow);
        state.put("flow", PipelineState.STOPPED);
        desired.put("flow", PipelineState.RUNNING);

        assertArtifactError(
                () -> service.delete(PRINCIPAL, "flow", hash(flow)),
                ArtifactError.PIPELINE_NOT_STOPPED,
                Map.of("id", "flow", "actual", "STOPPED", "desired", "RUNNING"));
        assertThat(store.get("flow")).isPresent();
    }

    @Test
    void aPausedPipelineIsRefusedOnBothHalvesOfTheVerdict() {
        PipelineResource flow = pipeline("flow");
        store.save(flow);
        state.put("flow", PipelineState.PAUSED);
        desired.put("flow", PipelineState.PAUSED);

        assertArtifactError(
                () -> service.delete(PRINCIPAL, "flow", hash(flow)),
                ArtifactError.PIPELINE_NOT_STOPPED,
                Map.of("id", "flow", "actual", "PAUSED", "desired", "PAUSED"));
        assertThat(store.get("flow")).isPresent();
    }

    @Test
    void aSettledPipelineIsDeletableFromEveryRestingActualState() {
        for (PipelineState resting :
                List.of(PipelineState.NEW, PipelineState.STOPPED,
                        PipelineState.COMPLETED, PipelineState.FAILED)) {
            PipelineResource flow = pipeline("flow");
            store.save(flow);
            state.put("flow", resting);
            desired.put("flow", PipelineState.STOPPED);

            service.delete(PRINCIPAL, "flow", hash(flow));

            assertThat(store.get("flow")).isEmpty();
        }
    }

    @Test
    void theLifecycleGateJudgesPipelinesOnlyAndNotOtherKindsSharingTheirId() {
        SourceResource orders = source("orders");
        store.save(orders);
        // A non-pipeline resource is not run, so lifecycle documents left under its id say nothing
        // about whether it may be deleted.
        state.put("orders", PipelineState.RUNNING);
        desired.put("orders", PipelineState.RUNNING);

        service.delete(PRINCIPAL, "orders", hash(orders));

        assertThat(store.get("orders")).isEmpty();
    }

    private static SourceResource source(String id) {
        return new SourceResource(
                id, null, "mysql",
                Map.of("host", "localhost", "port", "3306", "database", "orders", "username", "app"),
                SourceMode.SNAPSHOT, null, null, null, null);
    }

    private static PipelineResource pipeline(String id) {
        return pipelineReading(id, "orders_upstream");
    }

    private static PipelineResource pipelineReading(String id, String sourceId) {
        return new PipelineResource(id, null, List.of(sourceId), null, null, null, null, null);
    }

    private static TransformResource transform(String id) {
        return new TransformResource(id, null, new TransformBody.Js("function process(r) { return r; }"), null, null);
    }

    private static ViewResource view(String id) {
        return new ViewResource(id, null, null, null, null, null);
    }

    private static ServeResource serve(String id) {
        return new ServeResource(id, null, null, null, null, null);
    }

    private static String hash(Resource resource) {
        return CanonicalHash.of(new CanonicalWriter().write(resource));
    }

    private static void assertArtifactError(
            ThrowingAction action, ArtifactError code, Map<String, Object> args) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(TapstateException.class, error -> {
                    assertThat(error.code()).isEqualTo(code);
                    assertThat(error.args()).containsExactlyInAnyOrderEntriesOf(args);
                });
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }

    private static final class InMemoryArtifactStore implements ArtifactStore {

        private final Map<String, Resource> artifacts = new LinkedHashMap<>();

        @Override
        public synchronized ArtifactMutation delete(String id, String expectedContentHash) {
            Resource existing = artifacts.get(id);
            if (existing == null) {
                return ArtifactMutation.NOT_FOUND;
            }
            if (!hash(existing).equals(expectedContentHash)) {
                return ArtifactMutation.VERSION_CONFLICT;
            }
            artifacts.remove(id);
            return ArtifactMutation.DELETED;
        }

        @Override
        public synchronized void saveAll(List<Resource> resources) {
            resources.forEach(resource -> artifacts.put(resource.id(), resource));
        }

        @Override
        public synchronized Optional<Resource> get(String id) {
            return Optional.ofNullable(artifacts.get(id));
        }

        @Override
        public synchronized List<Resource> list() {
            return new ArrayList<>(artifacts.values());
        }
    }

    private static final class InMemoryDesiredStore implements DesiredStore {

        private final Map<String, DesiredState> docs = new LinkedHashMap<>();

        void put(String pipelineId, PipelineState target) {
            docs.put(pipelineId, new DesiredState(pipelineId, target, "0".repeat(64)));
        }

        @Override
        public void save(DesiredState state) {
            docs.put(state.pipelineId(), state);
        }

        @Override
        public Optional<DesiredState> read(String pipelineId) {
            return Optional.ofNullable(docs.get(pipelineId));
        }

        @Override
        public List<String> pipelineIds() {
            return new ArrayList<>(docs.keySet());
        }
    }

    private static final class InMemoryStateStore implements StateStore {

        private final Map<String, CheckpointDoc> docs = new LinkedHashMap<>();

        void put(String pipelineId, PipelineState actual) {
            docs.put(pipelineId, CheckpointDoc.initial(pipelineId, StateJson.of(actual), Instant.EPOCH));
        }

        @Override
        public Optional<CheckpointDoc> read(String pipelineId) {
            return Optional.ofNullable(docs.get(pipelineId));
        }

        @Override
        public void create(String pipelineId, String stateJson, Instant touchTime) {
            docs.put(pipelineId, CheckpointDoc.initial(pipelineId, stateJson, touchTime));
        }

        @Override
        public CasOutcome compareAndSwap(
                String pipelineId, long expectedEpoch, String nextStateJson, Instant touchTime) {
            throw new UnsupportedOperationException("the delete gate never writes state");
        }
    }
}
