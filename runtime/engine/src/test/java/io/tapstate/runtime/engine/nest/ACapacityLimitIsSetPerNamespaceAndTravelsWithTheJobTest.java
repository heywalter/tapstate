package io.tapstate.runtime.engine.nest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The one place a nest is told what it may hold. Every knob here shares two properties, and both of them
 * are why this could not stay a static method handing back a map configuration.
 *
 * <p><b>Per namespace, not per job.</b> One tree's levels differ in cardinality by orders of magnitude -
 * a policy level holds as many keys as there are policies, the level beneath it as many as there are
 * claims against all of them - so a single number covering both is either too small for the wide level or
 * too large to catch the narrow one growing without bound. A limit that applies to everything is a limit
 * that catches nothing.
 *
 * <p><b>It travels.</b> The limit is decided where the job is assembled and enforced on whichever member
 * runs the vertex, so it has to survive the trip between them. A limit that stayed behind would leave
 * every vertex running unguarded while the configuration that was meant to guard them reads as set.
 */
class ACapacityLimitIsSetPerNamespaceAndTravelsWithTheJobTest {

    private static final String POLICIES = "nest.p.doc.policies";
    private static final String CLAIMS = "nest.p.doc.policies.claims";

    @Test
    void aNamespaceNobodyConfiguredTakesTheDefault() {
        NestSettings settings = NestSettings.defaults();

        assertThat(settings.keysAllowedIn(POLICIES)).isEqualTo(NestSettings.DEFAULT_KEY_LIMIT);
    }

    @Test
    void aNamespaceGivenALimitOfItsOwnTakesThatOne() {
        NestSettings settings = NestSettings.defaults().withKeyLimit(POLICIES, 12L);

        assertThat(settings.keysAllowedIn(POLICIES)).isEqualTo(12L);
    }

    @Test
    void aLimitSetOnOneLevelDoesNotReachTheLevelBeneathIt() {
        NestSettings settings = NestSettings.defaults().withKeyLimit(POLICIES, 12L);

        assertThat(settings.keysAllowedIn(CLAIMS))
                .describedAs("the level beneath holds a different number of keys and keeps its own limit")
                .isEqualTo(NestSettings.DEFAULT_KEY_LIMIT);
    }

    @Test
    void everyLimitReachesTheMemberThatWillEnforceIt() throws Exception {
        NestSettings settings = NestSettings.defaults().withKeyLimit(POLICIES, 12L).withKeyLimit(CLAIMS, 34L);

        NestSettings arrived = roundTripped(settings);

        assertThat(arrived.keysAllowedIn(POLICIES)).isEqualTo(12L);
        assertThat(arrived.keysAllowedIn(CLAIMS)).isEqualTo(34L);
    }

    /** What the job submission does to anything the vertices are configured with, and nothing more. */
    private static NestSettings roundTripped(NestSettings settings) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(settings);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (NestSettings) in.readObject();
        }
    }
}
