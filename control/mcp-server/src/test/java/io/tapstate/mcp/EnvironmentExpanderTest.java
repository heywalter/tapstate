package io.tapstate.mcp;

import io.tapstate.core.common.TapstateException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentExpanderTest {

    @Test
    void recursivelyExpandsMapsListsDefaultsAndLeavesOtherValuesUntouched() {
        Object expanded = EnvironmentExpander.expand(
                Map.of("password", "${PASSWORD}", "values", List.of(
                        "${var:MISSING:fallback}", "literal", 42)),
                Map.of("PASSWORD", "secret"));

        assertThat(expanded).isEqualTo(Map.of(
                "password", "secret", "values", List.of("fallback", "literal", 42)));
    }

    @Test
    void missingEnvironmentReferenceIsAStableCodedFailure() {
        assertThatThrownBy(() -> EnvironmentExpander.expand("${MISSING}", Map.of()))
                .isInstanceOf(TapstateException.class)
                .hasMessageContaining("mcp.environment-missing");
    }
}
