package io.tapstate.runtime.engine.nest;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A subtree between documents: what hung under an element that has left one document and is on its way to
 * another, held somewhere both can reach until the one gaining it takes it.
 *
 * <p>It exists because those rows cannot travel as changes. The source edited one row - the one that moved
 * - and will never send the rest again, so unless they are read out of the document that had them and kept
 * here they are simply lost, with nothing anywhere reporting it. Held here they are data in transit, which
 * is a state a restart has to be able to find: the entry outlives the run that wrote it, and the frontier
 * is kept below the change that started the move until the move has landed.
 *
 * <p>The changes are in the order they must be applied - a parent before its children - so whoever takes
 * them places them straight in rather than parking any of them as waiting for an ancestor.
 */
public record ParkedSubtree(List<NestElement> changes) implements Serializable {

    public ParkedSubtree {
        Objects.requireNonNull(changes, "changes");
        changes = Collections.unmodifiableList(new ArrayList<>(changes));
    }

    /**
     * Which element's subtree an entry is, and the only thing both halves of a move can name. The element's
     * own address serves because it is read off the row that moved, so the document letting it go and the
     * one gaining it arrive at the same value without either knowing anything about the other.
     *
     * <p>A type of its own rather than a plain list, so a parked entry can never be mistaken for a document:
     * the keys either side of it are the keys of root rows, and two different things under one map must not
     * be able to collide on one.
     */
    public record At(List<String> pathId, List<Object> elementKey) implements Serializable {

        public At {
            pathId = List.copyOf(Objects.requireNonNull(pathId, "pathId"));
            elementKey = List.copyOf(Objects.requireNonNull(elementKey, "elementKey"));
        }

        /** Where the element this change is about sits, whichever half of the move the change is. */
        public static At of(NestElement change) {
            ElementRef ref = change.movedFrom() == null ? change.ref() : change.movedFrom();
            return new At(ref.pathId(), ref.elementKey());
        }

        /**
         * Where a whole document waits while its root row changes key: under the key it is bound for.
         *
         * <p>The empty path is what says "all of it" rather than "the embed at this path". It can be nothing
         * else - an element's address always names the embed it belongs to and so is never empty - so the two
         * kinds of entry share a map without being able to collide on one.
         *
         * <p>Addressed by the key it is going to and not by the one it came from, because the half that has
         * to find it is the half that has never seen this document: it reads its own key off the row that
         * arrived and asks for it. The half letting go knows both.
         */
        public static At ofRoot(List<Object> rootKey) {
            return new At(List.of(), rootKey);
        }
    }

    /** Whether this is holding nothing, which is not a thing worth keeping an entry for. */
    public boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * This and {@code more}, in the order they arrived. Two moves of the same element before either was
     * collected are two lots of rows, and taking only the later one would drop whatever the earlier one
     * carried.
     */
    public ParkedSubtree and(ParkedSubtree more) {
        List<NestElement> both = new ArrayList<>(changes);
        both.addAll(more.changes());
        return new ParkedSubtree(both);
    }
}
