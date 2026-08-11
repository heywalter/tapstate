package io.tapstate.control.core;

import io.tapstate.core.catalog.TapstateCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rejectsMutableNumberImplementationsAtTheImmutableInputBoundary() {
        AtomicInteger mutable = new AtomicInteger(3306);

        assertThatThrownBy(() -> new SourceDraft(
                "orders", null, "mysql", Map.of("port", mutable), null, null,
                null, null, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported JSON value type");
    }
}
