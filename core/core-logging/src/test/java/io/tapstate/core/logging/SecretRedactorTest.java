package io.tapstate.core.logging;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    @Test
    void replacesRegisteredValuesAndTracksOwnerLifecycle() {
        SecretRedactor redactor = new SecretRedactor();
        redactor.replace("source-a", List.of("sentinel", "sentinel-long"));
        redactor.replace("source-b", List.of("second-secret"));

        assertThat(redactor.redact("sentinel-long / sentinel / second-secret"))
                .isEqualTo("******** / ******** / ********");

        redactor.replace("source-a", List.of("replacement"));
        redactor.remove("source-b");

        assertThat(redactor.redact("sentinel second-secret replacement"))
                .isEqualTo("sentinel second-secret ********");
    }

    @Test
    void ignoresEmptyValuesAndLeavesNullTextNull() {
        SecretRedactor redactor = new SecretRedactor();
        redactor.replace("source", List.of("", "  "));

        assertThat(redactor.redact("ordinary line")).isEqualTo("ordinary line");
        assertThat(redactor.redact(null)).isNull();
    }
}
