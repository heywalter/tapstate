package io.tapstate.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TapstateMcpApplicationTest {

    @AfterEach
    void clearProcessProperties() {
        System.clearProperty(TapstateMcpApplication.AOT_PROCESSING_PROPERTY);
        System.clearProperty(TapstateMcpApplication.LOGBACK_STATUS_LISTENER_PROPERTY);
    }

    @Test
    void stdioLoggingSuppressesLogbackStatusFrames() {
        TapstateMcpApplication.prepareStdioLogging();

        assertThat(System.getProperty(TapstateMcpApplication.LOGBACK_STATUS_LISTENER_PROPERTY))
                .isEqualTo("ch.qos.logback.core.status.NopStatusListener");
    }

    @Test
    void aotProcessingDoesNotRequireADeployTimeToken() {
        System.setProperty(TapstateMcpApplication.AOT_PROCESSING_PROPERTY, "true");

        assertThat(TapstateMcpApplication.options(new String[0], Map.of()).token())
                .isEqualTo("tapstate-aot-context-only");
    }
}
