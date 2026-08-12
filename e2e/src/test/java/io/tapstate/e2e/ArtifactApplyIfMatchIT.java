package io.tapstate.e2e;

import io.tapstate.testsupport.DockerGate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The witness that an edit written against a version someone else has since replaced is refused, and
 * that an edit which never claimed a version is not.
 *
 * <p>Both halves are here because either alone permits an outcome this test exists to exclude. The
 * refusal alone is satisfied by a product that refuses every apply carrying the field, which would make
 * the precondition unusable; the unconditional half alone is satisfied by a product that ignores the
 * field entirely, which is the silent overwrite the precondition was added to stop. The unconditional
 * half is also the backward-compatibility guarantee: callers written before this field existed send no
 * precondition, and they have to keep applying exactly as they did.
 *
 * <p>The refusal is asserted on the stored bytes as well as the code, and that is the discriminating
 * assertion rather than a second opinion on the same fact. An implementation that writes first and
 * compares afterwards answers the very same code on the very same call - it just answers it over a store
 * it has already overwritten, which is the one outcome a precondition exists to make impossible. Reading
 * the canonical form either side of the refused call is the only thing that separates the two, so the
 * bytes are captured before it rather than reconstructed from the document the test sent.
 *
 * <p>The third apply then succeeds against the hash the store actually holds. Without it, "refuses a
 * stale hash" is indistinguishable from "refuses every hash", and the case would pass against a product
 * where the feature never works at all.
 *
 * <p>Runs on the harness's own connector, so it needs Docker for the store and nothing else: the subject
 * is the control plane's write path, which no real database is required to demonstrate.
 */
class ArtifactApplyIfMatchIT {

    private static final String SOURCE_ID = "src_file";
    private static final String VERSION_CONFLICT = "artifact.version-conflict";

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void aStaleVersionIsRefusedWithoutWritingAndTheCurrentOneIsAccepted(Tiers tier, @TempDir Path directory)
            throws Exception {
        try (ServerHandle server = tier.launch(storeUri("apply_if_match", tier))) {
            ControlPlane control = connected(server, directory);

            control.apply(Map.of("src_file.tap.yml", sourceYaml("first")));
            String hashOfTheFirstVersion = control.contentHash(SOURCE_ID);

            // A second author replaces it. The hash the first author is holding now describes a version the
            // store no longer has, which is the whole situation the precondition exists to notice.
            control.applyExpecting("src_file.tap.yml", sourceYaml("second"), hashOfTheFirstVersion);
            String hashOfTheSecondVersion = control.contentHash(SOURCE_ID);
            assertThat(hashOfTheSecondVersion)
                    .as("replacing the document changes the version it is addressed by")
                    .isNotEqualTo(hashOfTheFirstVersion);

            String storedBeforeTheRefusal = control.artifact(SOURCE_ID).orElseThrow().canonicalForm();

            ControlPlane.Refusal refusal =
                    control.applyExpectingRefusal("src_file.tap.yml", sourceYaml("third"), hashOfTheFirstVersion);

            assertThat(refusal.code())
                    .as("the code refusing an edit written against a version the store no longer holds")
                    .isEqualTo(VERSION_CONFLICT);
            assertThat(refusal.params())
                    .as("the refusal names which resource it is about, so a batch tells the author where to look")
                    .containsEntry("id", SOURCE_ID);
            assertThat(control.artifact(SOURCE_ID).orElseThrow().canonicalForm())
                    .as("the stored bytes across a refused apply - a product that writes first and compares "
                            + "afterwards answers the same code over a store it has already overwritten")
                    .isEqualTo(storedBeforeTheRefusal);

            // Against the version the store actually holds, the same edit goes through. Without this the case
            // would pass against a product that refuses every precondition it is handed.
            control.applyExpecting("src_file.tap.yml", sourceYaml("third"), hashOfTheSecondVersion);
            assertThat(control.artifact(SOURCE_ID).orElseThrow().canonicalForm())
                    .as("the edit lands once it names the version it was written against")
                    .isNotEqualTo(storedBeforeTheRefusal)
                    .contains("third");
        }
    }

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void anApplyThatClaimsNoVersionOverwritesWhateverIsStored(Tiers tier, @TempDir Path directory)
            throws Exception {
        try (ServerHandle server = tier.launch(storeUri("apply_no_if_match", tier))) {
            ControlPlane control = connected(server, directory);

            control.apply(Map.of("src_file.tap.yml", sourceYaml("first")));
            // Replaced behind the caller's back. An apply carrying a precondition would be refused from here
            // on; one carrying none is entitled to overwrite, and that entitlement is what callers written
            // before the field existed are standing on.
            control.apply(Map.of("src_file.tap.yml", sourceYaml("second")));

            control.apply(Map.of("src_file.tap.yml", sourceYaml("third")));

            assertThat(control.artifact(SOURCE_ID).orElseThrow().canonicalForm())
                    .as("an apply that never claimed a version is never refused by one")
                    .contains("third");
        }
    }

    private static ControlPlane connected(ServerHandle server, Path directory) throws Exception {
        ControlPlane control = new ControlPlane(server.baseUrl());
        control.bootstrapAndLogin("e2e", "e2e-password");
        control.registerConnector(
                E2eConnectorJar.CONNECTOR_ID, Files.readAllBytes(E2eConnectorJar.buildInto(directory)));
        return control;
    }

    private static String storeUri(String name, Tiers tier) {
        return SharedMongo.replicaSetUrl(name + "_" + tier.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The source document, distinguished only by the directory it names. One field varies so that a
     * version can be told from another by reading the stored bytes, without changing anything the apply
     * path treats differently.
     */
    private static String sourceYaml(String directoryName) {
        return """
                version: tapstate/v1
                kind: source
                id: %s
                connector: %s
                config: { uri: "/tmp/%s" }
                mode: cdc
                tables: [ orders ]
                """
                .formatted(SOURCE_ID, E2eConnectorJar.CONNECTOR_ID, directoryName);
    }
}
