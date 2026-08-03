package io.tapstate.e2e;

import io.tapstate.testsupport.DockerGate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The witness that runtime registration refuses a connector this release does not support, cleanly and
 * without keeping any of it.
 *
 * <p>The artifact is the harness's own connector packaged under an id nothing supports. It is well
 * formed in every other way - the same classes, manifest and specification shape the harness registers
 * successfully elsewhere - so the only reason to refuse it is the id, and a refusal here cannot be a
 * packaging accident wearing the right error code.
 *
 * <p>Two assertions, because either alone is satisfied by an outcome this test exists to exclude.
 * Asserting only the code leaves "refused, having already filed the bytes and half-loaded the
 * connector" indistinguishable from a clean refusal; asserting only the absence leaves a bare crash
 * that stored nothing looking like a pass. The status is part of the first: a coded refusal answers a
 * request error, while an uncaught failure would answer a server error with no code at all.
 *
 * <p>Both fidelity tiers. The accepted set is widenable per deployment and the harness widens it - to
 * its own synthetic id and nothing else - so this also pins that the widening stays narrow in the
 * shipped boot jar, where it arrives as a command-line argument rather than a property set in this JVM.
 *
 * <p>Needs Docker for the store, and nothing else: no connector jars are staged for it, so unlike the
 * real-connector witnesses this one runs in an ordinary build.
 */
class UnofficialConnectorIsRefusedIT {

    /** An id no release supports, and one the harness does not widen the accepted set to. */
    private static final String UNOFFICIAL_ID = "e2e_unofficial";

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void registeringAConnectorOutsideTheOfficialSetIsRefusedAndNothingIsFiledUnderIt(
            Tiers tier, @TempDir Path directory) throws Exception {
        byte[] artifact = Files.readAllBytes(E2eConnectorJar.buildInto(directory, UNOFFICIAL_ID));
        String storeUri =
                SharedMongo.replicaSetUrl("unofficial_refused_" + tier.name().toLowerCase(Locale.ROOT));

        try (ServerHandle server = tier.launch(storeUri)) {
            ControlPlane control = new ControlPlane(server.baseUrl());
            control.bootstrapAndLogin("e2e", "e2e-password");

            ControlPlane.Refusal refusal = control.registerConnectorExpectingRefusal(artifact);

            assertThat(refusal.code()).isEqualTo("connector.not-official");
            assertThat(refusal.status()).isEqualTo(400);
            assertThat(control.connectorIds()).doesNotContain(UNOFFICIAL_ID);
        }
    }
}
