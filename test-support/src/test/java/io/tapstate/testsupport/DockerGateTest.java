package io.tapstate.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate decides what a missing Docker daemon means, and the answer differs by where the build
 * runs. A suite whose whole reason to exist is running against the real thing must never report green
 * in CI without having run - a skip there is an absent gate wearing a passing badge. On a developer
 * machine the same absence is ordinary, and aborting is the honest outcome.
 */
class DockerGateTest {

    @Test
    void runsWhenDockerIsThere() {
        assertThat(DockerGate.decide(true, false)).isEqualTo(DockerGate.Decision.RUN);
        assertThat(DockerGate.decide(true, true)).isEqualTo(DockerGate.Decision.RUN);
    }

    @Test
    void skipsOnADeveloperMachineWithoutDocker() {
        assertThat(DockerGate.decide(false, false)).isEqualTo(DockerGate.Decision.SKIP);
    }

    @Test
    void failsRatherThanSkipsInCiWithoutDocker() {
        assertThat(DockerGate.decide(false, true)).isEqualTo(DockerGate.Decision.FAIL);
    }

    /**
     * The gate has to decide before Testcontainers starts the static containers, or a machine without
     * Docker meets the daemon's absence through Testcontainers' own error instead of through this
     * explanation. Being an execution condition is what guarantees that: JUnit evaluates conditions
     * before any callback, so it holds no matter which order the two annotations were written in.
     * Turning the gate back into a callback would quietly hand that ordering back to whoever types the
     * annotations, which is what this pins.
     */
    @Test
    void theGateDecidesBeforeAnyContainerStarts() {
        assertThat(ExecutionCondition.class)
                .as("a condition is evaluated before the callback that starts the containers")
                .isAssignableFrom(DockerGate.class);

        List<Class<? extends Annotation>> declared = Arrays.stream(RequiresDocker.class.getAnnotations())
                .map(Annotation::annotationType)
                .toList();
        assertThat(declared).contains(ExtendWith.class, Testcontainers.class);
        assertThat(RequiresDocker.class.getAnnotation(ExtendWith.class).value())
                .containsExactly(DockerGate.class);
    }
}
