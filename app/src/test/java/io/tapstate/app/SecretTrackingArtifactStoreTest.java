package io.tapstate.app;

import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.logging.SecretRedactor;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.ViewResource;
import io.tapstate.spi.store.ArtifactMutation;
import io.tapstate.spi.store.ArtifactStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.tapstate.spi.store.ArtifactMutation.ALREADY_EXISTS;
import static io.tapstate.spi.store.ArtifactMutation.CREATED;
import static io.tapstate.spi.store.ArtifactMutation.DELETED;
import static io.tapstate.spi.store.ArtifactMutation.REPLACED;
import static io.tapstate.spi.store.ArtifactMutation.VERSION_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

class SecretTrackingArtifactStoreTest {

    @Test
    void registersExistingAndSuccessfullyWrittenSourceSecrets() {
        RecordingStore delegate = new RecordingStore();
        delegate.save(source("existing", "before-start"));
        SecretRedactor redactor = new SecretRedactor();

        SecretTrackingArtifactStore store = tracking(delegate, redactor);
        store.saveAll(List.of(source("applied", "from-apply")));
        assertThat(store.create(source("created", "from-create"))).isEqualTo(CREATED);

        assertThat(redactor.redact("before-start from-apply from-create"))
                .isEqualTo("******** ******** ********");
    }

    @Test
    void replacesAndRemovesSecretsOnlyAfterSuccessfulMutations() {
        RecordingStore delegate = new RecordingStore();
        delegate.save(source("orders", "old-secret"));
        SecretRedactor redactor = new SecretRedactor();
        SecretTrackingArtifactStore store = tracking(delegate, redactor);

        delegate.nextReplace = VERSION_CONFLICT;
        assertThat(store.replace("orders", "stale", source("orders", "rejected-secret")))
                .isEqualTo(VERSION_CONFLICT);
        assertThat(redactor.redact("old-secret rejected-secret"))
                .isEqualTo("******** rejected-secret");

        assertThat(store.replace("orders", "current", source("orders", "new-secret")))
                .isEqualTo(REPLACED);
        assertThat(redactor.redact("old-secret new-secret"))
                .isEqualTo("old-secret ********");

        assertThat(store.replace("orders", "current", view("orders"))).isEqualTo(REPLACED);
        assertThat(redactor.redact("new-secret")).isEqualTo("new-secret");

        assertThat(store.create(source("deletable", "delete-me"))).isEqualTo(CREATED);
        delegate.nextDelete = VERSION_CONFLICT;
        assertThat(store.delete("deletable", "stale")).isEqualTo(VERSION_CONFLICT);
        assertThat(redactor.redact("delete-me")).isEqualTo("********");
        assertThat(store.delete("deletable", "current")).isEqualTo(DELETED);
        assertThat(redactor.redact("delete-me")).isEqualTo("delete-me");
    }

    @Test
    void tracksEveryScalarWhenAStoredConnectorIsNoLongerInTheCatalog() {
        RecordingStore delegate = new RecordingStore();
        SecretRedactor redactor = new SecretRedactor();
        SecretTrackingArtifactStore store = tracking(delegate, redactor);
        SourceResource source = new SourceResource(
                "legacy", null, "removed-connector",
                Map.of("credentials", Map.of("password", "nested-secret")),
                null, null, null, null, null);

        store.save(source);

        assertThat(redactor.redact("nested-secret")).isEqualTo("********");
    }

    private static SecretTrackingArtifactStore tracking(ArtifactStore delegate, SecretRedactor redactor) {
        return new SecretTrackingArtifactStore(delegate, TapstateCatalog::load, redactor);
    }

    private static SourceResource source(String id, String password) {
        return new SourceResource(
                id, null, "mysql", Map.of("host", "localhost", "password", password),
                null, null, null, null, null);
    }

    private static ViewResource view(String id) {
        return new ViewResource(id, null, "select 1", null, null, null);
    }

    private static final class RecordingStore implements ArtifactStore {

        private final Map<String, Resource> resources = new LinkedHashMap<>();
        private ArtifactMutation nextReplace = REPLACED;
        private ArtifactMutation nextDelete = DELETED;

        @Override
        public ArtifactMutation create(Resource artifact) {
            if (resources.containsKey(artifact.id())) {
                return ALREADY_EXISTS;
            }
            resources.put(artifact.id(), artifact);
            return CREATED;
        }

        @Override
        public ArtifactMutation replace(String id, String expectedContentHash, Resource replacement) {
            ArtifactMutation result = nextReplace;
            nextReplace = REPLACED;
            if (result == REPLACED) {
                resources.put(id, replacement);
            }
            return result;
        }

        @Override
        public ArtifactMutation delete(String id, String expectedContentHash) {
            ArtifactMutation result = nextDelete;
            nextDelete = DELETED;
            if (result == DELETED) {
                resources.remove(id);
            }
            return result;
        }

        @Override
        public void saveAll(List<Resource> artifacts) {
            artifacts.forEach(artifact -> resources.put(artifact.id(), artifact));
        }

        @Override
        public Optional<Resource> get(String id) {
            return Optional.ofNullable(resources.get(id));
        }

        @Override
        public List<Resource> list() {
            return List.copyOf(resources.values());
        }
    }
}
