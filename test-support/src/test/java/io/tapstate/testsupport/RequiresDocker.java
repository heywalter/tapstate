package io.tapstate.testsupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a suite that drives real containers: the Docker gate decides first, then Testcontainers
 * manages the {@code @Container} fields.
 *
 * <p>Deciding first is not a matter of how these two lines are ordered: {@link DockerGate} is an
 * execution condition, and JUnit evaluates every condition before any {@code BeforeAllCallback}, which
 * is what starts the {@code @Container} fields. Were the gate a callback instead, order would decide
 * it - the container would start first and a developer without Docker would get "Could not find a
 * valid Docker environment" rather than an explanation - and the invariant would then live in the
 * order somebody happened to type two annotations, in every file that used them.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(DockerGate.class)
@Testcontainers
public @interface RequiresDocker {
}
