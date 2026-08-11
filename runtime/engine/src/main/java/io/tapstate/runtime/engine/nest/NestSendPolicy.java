package io.tapstate.runtime.engine.nest;

import java.io.Serializable;

/**
 * How often one document may go out, and whether two versions of it may ever go out as one.
 *
 * <p>The unit that goes out here is the whole document, so a root fed by several tables is rewritten in
 * full for every change to any of them. Folding is what makes that affordable: versions produced faster
 * than the window are merged, and what goes out is the state they add up to. It is only safe because what
 * goes out is a state rather than a change - a reader that upserts on the key lands in the same place
 * whether it saw one version or ten.
 *
 * <p><b>Which is exactly why an append root may not fold.</b> There every send is a new record, so merging
 * two versions is not a saved write but a row the reader never sees. Both halves have to go: the window,
 * and the merging of a whole drain into one document. The second is the easier one to forget, because it
 * happens with no window configured at all.
 *
 * @param windowMillis how long after a document goes out before it may go out again, or zero for no
 *        window at all - every change that has one goes out as the drain applying it settles
 * @param foldingAllowed whether versions produced inside one window, or inside one drain, may be merged
 *        into the one that goes out
 */
public record NestSendPolicy(long windowMillis, boolean foldingAllowed) implements Serializable {

    public NestSendPolicy {
        if (windowMillis < 0) {
            throw new IllegalArgumentException("a window cannot be shorter than no window: " + windowMillis);
        }
        if (windowMillis > 0 && !foldingAllowed) {
            throw new IllegalArgumentException(
                    "a window that may not fold would delay every change and merge none: " + windowMillis);
        }
    }

    /**
     * Documents merged while a window of {@code windowMillis} is open. Zero leaves each drain sending the
     * state it settled on, which is the same thing with the window taken out rather than a different rule.
     */
    public static NestSendPolicy within(long windowMillis) {
        return new NestSendPolicy(windowMillis, true);
    }

    /** Every change its own document, merged with nothing: what an append reader has to be given. */
    public static NestSendPolicy everyChange() {
        return new NestSendPolicy(0L, false);
    }
}
