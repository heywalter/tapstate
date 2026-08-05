package io.tapstate.control.core;

import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.dsl.DslError;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorConfigValidatorTest {

    private final ConnectorConfigValidator validator =
            new ConnectorConfigValidator(TapstateCatalog::load);

    @Test
    void validatesMongoStandardConnectionSettingsAgainstTheLiveCatalog() {
        assertThatCode(() -> validator.validate(
                "mongodb", Map.of("isUri", false, "host", "localhost", "database", "orders")))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsTheMissingActiveConnectionField() {
        assertThatThrownBy(() -> validator.validate(
                "mongodb", Map.of("isUri", true)))
                .isInstanceOfSatisfying(TapstateException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.code())
                                .isEqualTo(DslError.CONFIG_REQUIRED));
    }
}
