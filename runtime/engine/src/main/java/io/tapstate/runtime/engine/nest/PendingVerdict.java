package io.tapstate.runtime.engine.nest;

/** Why a change held for a parent that never arrived was let go. */
public enum PendingVerdict {

    PARENT_ABSENT("parent-absent"),

    WALL_CLOCK_BACKSTOP("wall-clock-backstop");

    private final String verdict;

    PendingVerdict(String verdict) {
        this.verdict = verdict;
    }

    /** How this verdict is written wherever it is read by someone rather than switched on. */
    public String verdict() {
        return verdict;
    }
}
