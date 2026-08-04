package io.tapstate.control.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariant that makes "never a bare null" true by construction rather than by convention.
 *
 * <p>A consumer reading a specification source has to be able to tell "here it is" from "it is not
 * here, and this is why". Both fields set leaves that question unanswerable; neither set is the silent
 * absence the type exists to rule out - the shape that invites rebuilding a specification out of the
 * normalized config. Enforcing it in the constructor is what lets every reader skip the check, so the
 * enforcement itself is worth a test: without one, the guarantee rests on nobody having edited it.
 */
class SpecSourceTest {

    @Test
    void carriesEitherTheSourceOrAStatedReasonForItsAbsence() {
        assertThat(SpecSource.of("h", "{}").text()).isEqualTo("{}");
        assertThat(SpecSource.of("h", "{}").unavailable()).isNull();
        assertThat(SpecSource.unavailable("h", SpecSource.NOT_STORED).unavailable()).isEqualTo("not-stored");
        assertThat(SpecSource.unavailable("h", SpecSource.NOT_STORED).text()).isNull();
    }

    @Test
    void refusesToHoldBothTheSourceAndAReasonItIsAbsent() {
        assertThatThrownBy(() -> new SpecSource("h", "{}", SpecSource.NOT_STORED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void refusesToHoldNeither() {
        // The one this type exists for: a source that is quietly nothing at all.
        assertThatThrownBy(() -> new SpecSource("h", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }
}
