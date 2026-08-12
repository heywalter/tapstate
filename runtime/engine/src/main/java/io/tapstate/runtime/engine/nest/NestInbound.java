package io.tapstate.runtime.engine.nest;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * One edge arriving at a nest vertex: which stream travels it, and where that stream's rows carry the
 * value the vertex is keyed by. The {@code ordinal} is the inbound ordinal the edge is drawn on, which
 * is how a processor tells the rows apart once they arrive - the ordinal alone says which embed it is
 * looking at, so nothing has to be inferred from the row.
 *
 * <p>{@code keyFields} names the fields the key is read off, positionally matched to the vertex's
 * partition key, and {@code elementKey} the fields that identify one element of this stream inside the
 * document it lands in. A cascading edge carries changes an upstream vertex already routed and whose
 * place in the document it already settled, so it names neither, nor an alias: those absences are the
 * same fact, and {@link #isCascade()} is the one way to ask it.
 *
 * <p>{@code tracksKeyChanges} is the author's switch for this stream, carried here rather than looked up
 * because the ordinal already decides everything else about a row and this is one more thing decided at
 * compile time. {@code table} is the source table behind the alias, resolved once while compiling so a
 * failure can name what has to be reconfigured rather than only what the author wrote. Both are absent on
 * a cascading edge: no row of a source arrives on one, so there is nothing to track and no table to name.
 */
public record NestInbound(int ordinal, String alias, List<String> pathId, List<String> keyFields,
        List<String> elementKey, boolean tracksKeyChanges, String table) implements Serializable {

    public NestInbound {
        Objects.requireNonNull(pathId, "pathId");
        pathId = List.copyOf(pathId);
        keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        elementKey = elementKey == null ? List.of() : List.copyOf(elementKey);
        if ((alias == null) != keyFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "an edge either names the stream it carries and the fields keying it, or neither");
        }
        if (alias == null && tracksKeyChanges) {
            throw new IllegalArgumentException("no row arrives on a cascading edge to track key changes on");
        }
    }

    /** An edge over rows nobody asked to have key changes followed on - the shape most of them are. */
    public NestInbound(int ordinal, String alias, List<String> pathId, List<String> keyFields,
            List<String> elementKey) {
        this(ordinal, alias, pathId, keyFields, elementKey, false, null);
    }

    /** Whether these rows arrive already stamped with the parent key by the vertex one level down. */
    public boolean isCascade() {
        return alias == null;
    }
}
