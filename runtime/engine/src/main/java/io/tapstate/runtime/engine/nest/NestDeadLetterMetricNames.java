package io.tapstate.runtime.engine.nest;

/**
 * What a namespace's count of unassemblable changes is called among a run's statistics, and how to read one
 * back. Naming and parsing sit together because they are one contract with two ends, and the two drifting
 * apart would show up as a pipeline that simply reports nothing discarded — not as anything failing.
 *
 * <p>A name of its own rather than a fifth kind beside the state readings. Those four describe how a
 * namespace's memory is holding up and are read together as one picture; this one says rows left the
 * pipeline, which is a different question with a different answer when it is zero. Folding it in would also
 * mean a namespace absent from the state readings could not report a discard, and the two sets are not the
 * same: a namespace reports state while it runs and reports this only if something went wrong with the data.
 */
public final class NestDeadLetterMetricNames {

    /** What the count's name begins with, before the namespace it concerns. */
    public static final String PREFIX = "nestDeadLettered.";

    private NestDeadLetterMetricNames() {
    }

    /** The name the count for {@code namespace} is left under. */
    public static String nameOf(String namespace) {
        return PREFIX + namespace;
    }

    /** The namespace a count named {@code metric} concerns, or {@code null} when it is not one of these. */
    public static String namespaceOf(String metric) {
        return metric.startsWith(PREFIX) ? metric.substring(PREFIX.length()) : null;
    }
}
