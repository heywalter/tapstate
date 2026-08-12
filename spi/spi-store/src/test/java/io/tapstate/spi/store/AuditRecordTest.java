package io.tapstate.spi.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditRecordTest {

    private static final Instant TS = Instant.parse("2026-07-07T10:15:30Z");

    @Test
    void holdsTheAuditFields() {
        AuditRecord record = new AuditRecord(TS, "alice", "artifact.apply", "orders-source");
        assertThat(record.timestamp()).isEqualTo(TS);
        assertThat(record.principal()).isEqualTo("alice");
        assertThat(record.operationId()).isEqualTo("artifact.apply");
        assertThat(record.resourceId()).isEqualTo("orders-source");
    }

    @Test
    void carriesTheVersionPreconditionACallerDeclaredWhenThereIsOne() {
        AuditRecord record =
                new AuditRecord(TS, "alice", "artifact.delete", "orders-source", "a".repeat(64));

        assertThat(record.expectedContentHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void anOperationWithNoPreconditionToDeclareLeavesItAbsent() {
        // Most audited operations declare no version at all — creating a token, probing a connection. The
        // shorter form is what they use, and it must stay valid rather than force every caller to pass a
        // null through: the field belongs to the operations that have one, not to the record's shape.
        AuditRecord record = new AuditRecord(TS, "alice", "token.create", "tok-1");

        assertThat(record.expectedContentHash()).isNull();
    }

    @Test
    void rejectsAMissingTimestamp() {
        assertThatThrownBy(() -> new AuditRecord(null, "alice", "artifact.apply", "orders-source"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankPrincipal() {
        assertThatThrownBy(() -> new AuditRecord(TS, "  ", "artifact.apply", "orders-source"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankOperationId() {
        assertThatThrownBy(() -> new AuditRecord(TS, "alice", " ", "orders-source"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankResourceId() {
        assertThatThrownBy(() -> new AuditRecord(TS, "alice", "artifact.apply", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
