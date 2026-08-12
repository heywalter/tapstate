package io.tapstate.cli;

import java.util.Objects;

/**
 * One authored resource document to submit for a remote apply: its raw YAML text plus an origin label (a
 * filename) the server uses only to attribute a parse error back to where it came from. The CLI sends raw
 * text, never a parsed model — the server re-parses and re-validates — so this is the request-side value
 * the {@code apply} verb marshals into the apply body's {@code drafts} array.
 *
 * <p>{@code expectedContentHash} is the optional per-resource precondition: the version the author read
 * before editing. A draft without one is applied the way every draft always was, so a caller that never
 * asks for the check is never refused by it.
 */
record LocalDraft(String source, String content, String expectedContentHash) {

    LocalDraft {
        Objects.requireNonNull(content, "draft content");
    }

    /** A draft submitted with no precondition, which is what collecting a workspace produces. */
    LocalDraft(String source, String content) {
        this(source, content, null);
    }
}
