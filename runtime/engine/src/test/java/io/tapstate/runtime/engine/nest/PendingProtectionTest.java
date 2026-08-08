package io.tapstate.runtime.engine.nest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class PendingProtectionTest {

    private static final Duration BACKSTOP = Duration.ofHours(1);

    @Test
    void aChangeIsLetGoOnceItsParentsLevelHasLoadedAndThatChainHasRunPastIt() {
        // Both halves are needed and neither is enough: the level having loaded says no parent row is
        // still to come from the initial read, and the chain having run past this change says none is
        // still to come from the log either. Together they establish the parent absent rather than late.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(true, OptionalLong.of(500L));

        assertThat(protection.verdictOn(parent, 400L, Duration.ZERO))
                .contains(PendingVerdict.PARENT_ABSENT);
    }

    @Test
    void aChangeHeldAsLongAsAChangeMayBeIsLetGoOnTheBackstop() {
        // Nothing has established anything here and this path gives up anyway, because the other two can
        // be unable to ever answer: a nest that embeds its own table puts parent and child on one chain,
        // and the child waiting here is itself what stops that chain from reaching the parent it waits for.
        // Held exactly as long as the backstop allows is already too long -- the alternative reading leaves
        // "how long may it be held" with no value that ever trips.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(false, OptionalLong.empty());

        assertThat(protection.verdictOn(parent, 400L, BACKSTOP))
                .contains(PendingVerdict.WALL_CLOCK_BACKSTOP);
    }

    @Test
    void aChangeIsHeldWhileItsParentsLevelIsStillLoading() {
        // The chain has run well past this change, and that says nothing at all while rows of the parent
        // are still being read out of the initial load: the one that answers this change may be among
        // them. Reading the chain alone would declare absent every child that arrived before its parent
        // was loaded, which during a load is most of them.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(false, OptionalLong.of(500L));

        assertThat(protection.verdictOn(parent, 400L, Duration.ZERO)).isEmpty();
    }

    @Test
    void aChangeIsHeldWhileItsParentsChainHasNotReachedItsOwnEventTime() {
        // The load has finished, so no parent is still to come from it -- but one may still be written to
        // the log at any moment, and until the chain has been consumed past this change's own event time
        // the parent row could be sitting just ahead of where the read has got to.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(true, OptionalLong.of(300L));

        assertThat(protection.verdictOn(parent, 400L, Duration.ZERO)).isEmpty();
    }

    @Test
    void aChangeIsHeldWhenItsParentTravelsAClockThatCannotBeComparedWithItsOwn() {
        // Two sources are two clocks. A child whose clock runs behind reports an event time lower than the
        // same instant on the parent's chain, so the threshold this test compares against is lowered by
        // the difference, and the parent is declared absent while it is still on its way. The change is
        // held instead and the backstop remains the only path that lets it go.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(true, OptionalLong.empty());

        assertThat(protection.verdictOn(parent, 400L, Duration.ZERO)).isEmpty();
    }

    @Test
    void aParentChainConsumedToExactlyThisChangesEventTimeHasReachedIt() {
        // The boundary is inclusive. A parent row committed in the same millisecond would have been read
        // by the time the chain reports that millisecond consumed, so excluding equality would hold every
        // such change until the backstop rather than answering it.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(true, OptionalLong.of(400L));

        assertThat(protection.verdictOn(parent, 400L, Duration.ZERO))
                .contains(PendingVerdict.PARENT_ABSENT);
    }

    @Test
    void anEstablishedAbsenceIsReportedRatherThanTheBackstopWhenBothWouldFire() {
        // Both answers release the change; they do not mean the same thing. One says the data is shaped
        // this way, the other says a row may just have been dropped. Reporting the backstop for a change
        // whose parent is established absent would send whoever reads the dead letter looking for a fault
        // that is not there -- and doing so for every change once a load is slow enough drowns the ones
        // that are worth looking at.
        PendingProtection protection = new PendingProtection(BACKSTOP);
        ParentProgress parent = new ParentProgress(true, OptionalLong.of(500L));

        assertThat(protection.verdictOn(parent, 400L, BACKSTOP.plusHours(1)))
                .contains(PendingVerdict.PARENT_ABSENT);
    }
}
