package io.tapstate.control.core;

import io.tapstate.core.catalog.TapstateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SourceDraftServiceTest {

    @Test
    void rendersAValidatedSourceWithoutPersistingIt() {
        SourceDraftService service = new SourceDraftService(TapstateCatalog.load());

        SourceDraftResult result = service.draft(new SourceDraft(
                "orders",
                null,
                "mysql",
                Map.of("host", "localhost", "port", 3306, "database", "orders", "username", "root"),
                null,
                null,
                null,
                null,
                null,
                List.of()));

        assertThat(result.yaml()).contains("kind: source", "id: orders", "connector: mysql");
    }
}
