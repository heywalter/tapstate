package io.tapstate.e2e;

import io.tapstate.core.lifecycle.PipelineState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A condition over observable product state. One vocabulary serves both timings: {@code assert}
 * checks a matcher once, {@code await} polls the same matcher until it holds or the bound expires.
 *
 * <p>Every word has a real source today: {@code count} reads the target database, {@code state} reads
 * the published observation, {@code error_count} reads the observation's metrics map. A word whose
 * source the runtime does not yet populate would poll an empty map until timeout, so it is not admitted
 * until something fills it.
 */
public sealed interface Matcher {

    /**
     * Row counts per table, read from the target endpoint itself. Declaration order is preserved,
     * so the endpoints are read in the order written and a failure reads the same way twice.
     */
    record Count(Map<TableAlias, Long> expected) implements Matcher {
        public Count {
            expected = Collections.unmodifiableMap(new LinkedHashMap<>(expected));
        }
    }

    /**
     * The lifecycle state of the pipeline this specification names. A specification references
     * exactly one pipeline, and the executor already resolves its id through the product's parser,
     * so naming it again here would only be an id to copy by hand and get wrong.
     */
    record State(PipelineState expected) implements Matcher {}

    /**
     * The count of observable errors the pipeline this specification names has published, read from its
     * metrics face. A specification references exactly one pipeline - the same one {@link State} names -
     * so the count is written on its own rather than keyed by an id an author would copy by hand.
     */
    record ErrorCount(long expected) implements Matcher {}

    /**
     * The canonical code of the failure the pipeline this specification names has published, read from its
     * status face. The state says a run died and the error count says it was counted; only the code says
     * what killed it, so a regression that swaps one reason for another is visible here and nowhere else.
     */
    record FailureCode(String expected) implements Matcher {}

    /**
     * How many changes the pipeline this specification names could never place in a document, added up over
     * every namespace of it, read from its metrics face. Written as one number rather than keyed by
     * namespace: a namespace name is derived from the pipeline and an embed path inside it, so naming one
     * here would be an internal name for an author to copy by hand and to rewrite whenever a step is renamed.
     */
    record DeadLettered(long expected) implements Matcher {}

    static Matcher count(TableAlias table, long rows) {
        return new Count(Map.of(table, rows));
    }

    static Matcher state(PipelineState expected) {
        return new State(expected);
    }

    static Matcher errorCount(long expected) {
        return new ErrorCount(expected);
    }

    static Matcher failureCode(String expected) {
        return new FailureCode(expected);
    }

    static Matcher deadLettered(long expected) {
        return new DeadLettered(expected);
    }
}
