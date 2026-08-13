package io.tapstate.spi.store;

import java.time.Instant;

/**
 * One entry in the control-plane audit log: the record written before an audited operation runs.
 *
 * <p>Fields — {@code timestamp} (when the operation was attempted), {@code principal} (the subject
 * that invoked it: a user id or a token id), {@code operationId} (the registry id of the operation,
 * e.g. {@code artifact.apply}), {@code resourceId} (the target the operation acts on), and
 * {@code expectedContentHash} (the version the caller declared it was acting on, where the operation
 * takes one; null otherwise). These are the fields knowable before the operation runs, which is what
 * the audit-before-execute guarantee needs. Richer fields the mature record carries — the originating
 * face, the outcome — attach where their determining step lands and are not modelled here.
 *
 * <p>{@code expectedContentHash} is what the caller <em>declared</em>, never what was found or what
 * was destroyed. The record is written before the operation runs, and confirming the version that was
 * actually removed happens inside the store's own atomic compare, which is after this point; an
 * attempt refused by that compare leaves a record whose declared version is, correctly, the stale one
 * the caller offered. It earns its place on a record that is otherwise all "who and what" because a
 * destroyed resource leaves nothing behind to compare an entry against later.
 *
 * <p>A pure value over {@code java..} only (rule R2): the port stays free of any face or store type.
 */
public record AuditRecord(
        Instant timestamp,
        String principal,
        String operationId,
        String resourceId,
        String expectedContentHash) {

    public AuditRecord {
        if (timestamp == null) {
            throw new IllegalArgumentException("audit record timestamp must be set");
        }
        requireText(principal, "principal");
        requireText(operationId, "operationId");
        requireText(resourceId, "resourceId");
    }

    /** A record for an operation that declares no version precondition, which is most of them. */
    public AuditRecord(Instant timestamp, String principal, String operationId, String resourceId) {
        this(timestamp, principal, operationId, resourceId, null);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("audit record " + field + " must be non-blank");
        }
    }
}
