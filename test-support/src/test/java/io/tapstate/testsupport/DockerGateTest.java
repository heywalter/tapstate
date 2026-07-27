package io.tapstate.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.TestAbortedException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

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

    @Test
    void aRunningTestIsLetThrough_aborted_orFailed() {
        assertThatCode(() -> DockerGate.applyDecision(DockerGate.Decision.RUN))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> DockerGate.applyDecision(DockerGate.Decision.SKIP))
                .isInstanceOf(TestAbortedException.class)
                .hasMessageContaining("-Dapi.version=1.44");
        assertThatThrownBy(() -> DockerGate.applyDecision(DockerGate.Decision.FAIL))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("verified nothing");
    }

    @Test
    void theSameDecisionAsACondition() {
        assertThat(DockerGate.resultFor(DockerGate.Decision.RUN).isDisabled()).isFalse();

        ConditionEvaluationResult skipped = DockerGate.resultFor(DockerGate.Decision.SKIP);
        assertThat(skipped.isDisabled()).isTrue();
        assertThat(skipped.getReason()).get(STRING).contains("-Dapi.version=1.44");

        // Disabling in CI would be the bug this whole class exists to prevent, so the condition
        // refuses rather than returning a result at all.
        assertThatThrownBy(() -> DockerGate.resultFor(DockerGate.Decision.FAIL))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void onlyTheExactEnvironmentValueCounts() {
        assertThat(DockerGate.isCi("true")).isTrue();
        assertThat(DockerGate.isCi("false")).isFalse();
        assertThat(DockerGate.isCi("TRUE")).isFalse();
        assertThat(DockerGate.isCi(null)).isFalse();
    }

    /**
     * Whatever this machine has, asking is allowed to answer but never to throw: an exception escaping
     * the probe would become a third outcome the gate never decided on, and it would surface as a
     * broken test rather than as an absent daemon.
     */
    @Test
    void probingForADaemonAnswersRatherThanThrowing() {
        assertThatCode(DockerGate::dockerAvailable).doesNotThrowAnyException();
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
