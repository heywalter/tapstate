package io.tapstate.control.core;

import java.util.List;
import java.util.Objects;

/** Audited administration of revocable machine tokens. */
public final class TokenAdminService {

    private final TokenService tokens;
    private final AuditGate auditGate;

    public TokenAdminService(TokenService tokens, AuditGate auditGate) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.auditGate = Objects.requireNonNull(auditGate, "auditGate");
    }

    /** Creates a token after its public id has been recorded by the audit-first gate. */
    public CreatedToken create(String principal, Scope scope) {
        TokenService.PendingToken pending = tokens.prepare(scope);
        return auditGate.dispatch(
                ControlOperations.TOKEN_CREATE,
                new AuditContext(principal, pending.tokenId()),
                () -> new CreatedToken(
                        pending.tokenId(), pending.scope(), tokens.persist(pending), pending.createdAt()));
    }

    /** Lists safe token descriptors; no bearer secret or stored secret hash is exposed. */
    public List<TokenInfo> list() {
        return tokens.list();
    }

    /** Revokes a token under the audit-first gate. Revocation remains idempotent. */
    public void revoke(String principal, String tokenId) {
        auditGate.dispatch(
                ControlOperations.TOKEN_REVOKE,
                new AuditContext(principal, tokenId),
                () -> {
                    tokens.revoke(tokenId);
                    return null;
                });
    }
}
