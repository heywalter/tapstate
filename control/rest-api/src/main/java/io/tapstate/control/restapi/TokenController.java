package io.tapstate.control.restapi;

import io.tapstate.control.core.CreatedToken;
import io.tapstate.control.core.Scope;
import io.tapstate.control.core.TokenAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;

/** Thin HTTP projection of machine-token administration. */
@RestController
class TokenController {

    private final TokenAdminService tokens;

    TokenController(TokenAdminService tokens) {
        this.tokens = tokens;
    }

    @Verb("token.create")
    @PostMapping("/tokens")
    ResponseEntity<CreatedToken> create(
            @RequestBody(required = false) TokenCreateRequest request,
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) String principal) {
        TokenCreateRequest body = MalformedRequest.require(
                request, "the request must carry a token scope");
        MalformedRequest.requireText(body.scope(), "a `scope` is required");
        Scope scope;
        try {
            scope = Scope.valueOf(body.scope().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw MalformedRequest.rejecting("`scope` must be read, write, or admin", error);
        }
        CreatedToken created = tokens.create(principal, scope);
        return ResponseEntity.created(URI.create("/api/tokens/" + created.tokenId())).body(created);
    }

    @Verb("token.list")
    @GetMapping("/tokens")
    TokenList list() {
        return new TokenList(tokens.list());
    }

    @Verb("token.revoke")
    @PostMapping("/tokens/{id}:revoke")
    ResponseEntity<Void> revoke(
            @PathVariable("id") String id,
            @RequestAttribute(AuthInterceptor.PRINCIPAL_ATTRIBUTE) String principal) {
        tokens.revoke(principal, id);
        return ResponseEntity.noContent().build();
    }
}
