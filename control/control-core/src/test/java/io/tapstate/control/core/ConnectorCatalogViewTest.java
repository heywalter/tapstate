package io.tapstate.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.tapstate.spi.store.ConnectorRegistration;
import io.tapstate.spi.store.ConnectorRegistry;
import io.tapstate.spi.store.RegistrationOutcome;
import io.tapstate.spi.store.RegistrationSource;
import io.tapstate.core.catalog.CatalogEntryReader;
import io.tapstate.core.catalog.TapstateCatalog;

/**
 * The online catalog view unions the bundled snapshot with the rows derived for registered connectors:
 * a registered connector becomes visible without a restart (the view re-reads the store per call), and
 * every listed connector is tagged bundled or registered so a face can tell an authored-in connector
 * from a user-uploaded one.
 */
class ConnectorCatalogViewTest {

    private static final TapstateCatalog BUNDLED = TapstateCatalog.load();

    private static final String ACME_ROW = """
            {
              "id": "acme", "name": "Acme", "displayName": "Acme", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": false, "writeSemantics": []}, "pushOut": false, "config": [],
              "provenance": {"connectorRepoSha": null, "specPath": "spec.json", "specContentHash": "h",
                "pdkApiVersion": "1.0.0", "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    @Test
    void detailCarriesTheStoredSpecSourceUnderTheRowsProvenanceHash() {
        // The normalized row is a lossy projection; the source is kept beside it under the very hash the
        // row's provenance already records. The detail read is where that pointer stops being a pointer.
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        InMemoryConnectorSpecStore specs = new InMemoryConnectorSpecStore();
        String source = "{\"properties\":{\"id\":\"acme\"},\"zz\":1,\"a\":2}";
        specs.put("h", source.getBytes(StandardCharsets.UTF_8));
        ConnectorCatalogView view = new ConnectorCatalogView(BUNDLED, store, specs, emptyRegistry());

        ConnectorDetail detail = view.detail("acme");

        assertThat(detail.spec().contentHash()).isEqualTo("h");
        assertThat(detail.spec().text()).isEqualTo(source);
        assertThat(detail.spec().unavailable()).isNull();
        // The projection stands unchanged beside it: the source is an addition, not a replacement, and
        // the fields a connection form already consumes must not shift shape under it.
        assertThat(detail.id()).isEqualTo("acme");
        assertThat(detail.origin()).isEqualTo("registered");
        assertThat(detail.config()).isEmpty();
        assertThat(detail.modes()).containsExactly("snapshot");
    }

    @Test
    void detailSaysWhyTheSpecSourceIsAbsentRatherThanReturningNothing() {
        // A bundled row has no stored source. Answering with a bare null invites a consumer to
        // reconstruct a spec from the normalized config — the one thing the source exists to prevent —
        // so the absence is stated, with the hash still named so a later store can be checked again.
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, new InMemoryConnectorCatalogStore(), new InMemoryConnectorSpecStore(), emptyRegistry());

        ConnectorDetail detail = view.detail(BUNDLED.ids().iterator().next());

        assertThat(detail.spec().text()).isNull();
        assertThat(detail.spec().unavailable()).isEqualTo("not-stored");
        assertThat(detail.origin()).isEqualTo("bundled");
    }

    @Test
    void detailStatesTheAbsenceForARowWhoseProvenanceNamesNoSpecHashAtAll() {
        // A row can carry no hash to dereference, not merely a hash nothing is stored under. Both are
        // absences and both must be stated, but they arrive by different routes - and the one with no
        // pointer at all is the route where returning a bare null would be easiest to write.
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW.replace("\"specContentHash\": \"h\"", "\"specContentHash\": null")));
        ConnectorCatalogView view =
                new ConnectorCatalogView(BUNDLED, store, new InMemoryConnectorSpecStore(), emptyRegistry());

        ConnectorDetail detail = view.detail("acme");

        assertThat(detail.spec().contentHash()).isNull();
        assertThat(detail.spec().text()).isNull();
        assertThat(detail.spec().unavailable()).isEqualTo("not-stored");
    }

    @Test
    void runtimeAvailableIsTrueOnlyWhenTheArtifactBytesAreActuallyThere() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), registryHolding("acme", "jar-hash", true));

        assertThat(view.detail("acme").runtimeAvailable()).isTrue();
    }

    @Test
    void runtimeAvailableIsFalseForARegisteredRowWhoseArtifactBytesAreGone() {
        // The case that separates runtimeAvailable from origin. Both say "registered" here, yet the jar
        // cannot be loaded — an implementation that reads origin and stops would answer true, and every
        // consumer would be told a connector can run when it cannot.
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), registryHolding("acme", "jar-hash", false));

        ConnectorDetail detail = view.detail("acme");

        assertThat(detail.origin()).isEqualTo("registered");
        assertThat(detail.runtimeAvailable()).isFalse();
    }

    @Test
    void runtimeAvailableIsFalseForABundledRowThatWasNeverRegistered() {
        // A bundled row is catalog metadata shipped with the release; nothing says its jar is present.
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, new InMemoryConnectorCatalogStore(), new InMemoryConnectorSpecStore(), emptyRegistry());

        ConnectorDetail detail = view.detail(BUNDLED.ids().iterator().next());

        assertThat(detail.origin()).isEqualTo("bundled");
        assertThat(detail.runtimeAvailable()).isFalse();
    }

    @Test
    void mergedUnionsRegisteredRowsOverTheBundledSnapshot() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), emptyRegistry());

        TapstateCatalog merged = view.merged();

        assertThat(merged.ids()).containsAll(BUNDLED.ids()).contains("acme");
        assertThat(merged.byId("acme").displayName()).isEqualTo("Acme");
    }

    @Test
    void mergedReflectsRegistrationsMadeAfterTheViewWasConstructed() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), emptyRegistry());
        assertThat(view.merged().ids()).doesNotContain("acme");

        store.upsert(CatalogEntryReader.read(ACME_ROW));

        // The view re-reads the store per call, so a runtime registration shows up without a restart.
        assertThat(view.merged().ids()).contains("acme");
    }

    @Test
    void summariesTagRegisteredRowsRegisteredAndBundledRowsBundled() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        store.upsert(CatalogEntryReader.read(ACME_ROW));
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), emptyRegistry());

        List<ConnectorSummary> summaries = view.summaries();

        ConnectorSummary acme = summaries.stream().filter(s -> s.id().equals("acme")).findFirst().orElseThrow();
        assertThat(acme.origin()).isEqualTo("registered");
        assertThat(acme.modes()).contains("snapshot");
        String bundledId = BUNDLED.ids().get(0);
        ConnectorSummary bundled = summaries.stream().filter(s -> s.id().equals(bundledId)).findFirst().orElseThrow();
        assertThat(bundled.origin()).isEqualTo("bundled");
    }

    @Test
    void detailProjectsTheNormalizedConfigWithoutRawFormilyExpressions() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), emptyRegistry());

        ConnectorDetail detail = view.detail("mysql");

        assertThat(detail.id()).isEqualTo("mysql");
        assertThat(detail.origin()).isEqualTo("bundled");
        assertThat(detail.config()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("password");
            assertThat(field.type()).isEqualTo("string");
            assertThat(field.secret()).isTrue();
        });
        assertThat(detail.config()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("host");
            assertThat(field.visibleWhen()).isNotNull();
            assertThat(field.visibleWhen().controllingField()).isEqualTo("deploymentMode");
            assertThat(field.visibleWhen().equalsAnyOf()).containsExactly("standalone");
        });
    }

    @Test
    void detailReadsTheLiveRegisteredOverlayAndTagsItsOrigin() {
        InMemoryConnectorCatalogStore store = new InMemoryConnectorCatalogStore();
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, store, new InMemoryConnectorSpecStore(), emptyRegistry());

        store.upsert(CatalogEntryReader.read(ACME_ROW));

        assertThat(view.detail("acme").origin()).isEqualTo("registered");
    }

    @Test
    void detailRejectsAnUnknownConnectorWithACodedError() {
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, new InMemoryConnectorCatalogStore(), new InMemoryConnectorSpecStore(), emptyRegistry());

        assertThatThrownBy(() -> view.detail("missing"))
                .isInstanceOfSatisfying(io.tapstate.core.common.TapstateException.class,
                        error -> assertThat(error.code().code()).isEqualTo("connector.not-found"));
    }

    @Test
    void readsAConnectorWhileAnotherStoredRegistrationCannotBeReconstructed() {
        // Registry corruption is scoped to the connector it belongs to. A registry that cannot produce a
        // full listing — one entry written by a newer build, one file left behind by a partial restore —
        // must still answer about the connector being read, or a single bad entry takes down every
        // connection form in the product, including bundled connectors that were never registered.
        ConnectorRegistry unlistable = new ConnectorRegistry() {
            @Override
            public RegistrationOutcome register(String id, String pdkApiVersion, RegistrationSource source, byte[] artifact) {
                throw new UnsupportedOperationException("the detail read never registers");
            }

            @Override
            public List<ConnectorRegistration> list() {
                throw new IllegalStateException("one stored registration cannot be reconstructed");
            }

            @Override
            public Optional<ConnectorRegistration> find(String connectorId) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> artifact(String hash) {
                throw new UnsupportedOperationException("a detail read must not pull artifact bytes");
            }

            @Override
            public boolean hasArtifact(String hash) {
                return false;
            }
        };
        ConnectorCatalogView view = new ConnectorCatalogView(
                BUNDLED, new InMemoryConnectorCatalogStore(), new InMemoryConnectorSpecStore(), unlistable);

        ConnectorDetail detail = view.detail(BUNDLED.ids().get(0));

        assertThat(detail.origin()).isEqualTo("bundled");
        assertThat(detail.runtimeAvailable()).isFalse();
    }

    private static ConnectorRegistry emptyRegistry() {
        return registryHolding(null, null, false);
    }

    /**
     * A registry carrying at most one registration, and answering independently whether its bytes are
     * still there — the two facts the detail read combines, kept separable so a test can pull them apart.
     */
    private static ConnectorRegistry registryHolding(String connectorId, String contentHash, boolean bytesPresent) {
        List<ConnectorRegistration> registrations = connectorId == null
                ? List.of()
                : List.of(new ConnectorRegistration(connectorId, contentHash, "1.0.0", RegistrationSource.REGISTER));
        return new ConnectorRegistry() {
            @Override
            public RegistrationOutcome register(String id, String pdkApiVersion, RegistrationSource source, byte[] artifact) {
                throw new UnsupportedOperationException("the detail read never registers");
            }

            @Override
            public List<ConnectorRegistration> list() {
                return registrations;
            }

            @Override
            public Optional<byte[]> artifact(String hash) {
                throw new UnsupportedOperationException("a detail read must not pull artifact bytes");
            }

            @Override
            public boolean hasArtifact(String hash) {
                return bytesPresent && hash.equals(contentHash);
            }
        };
    }
}
