package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

class AChangeIsNotHeldForAParentThatWillNotComeTest {

    private static final Duration BACKSTOP = Duration.ofHours(1);
    private static final long HELD_AT = 1_700_000_000_000L;

    private static NestElement child(long eventTime, long seq) {
        return new NestElement(element(List.of("items"), "P1", "a", null),
                row("sku", "a"), at(seq), Map.of("items", new ChainPosition(at(seq), "t" + seq)),
                eventTime);
    }

    /** The rule as a vertex applies it, with the parent's progress already worked out for this chain. */
    private static PendingRelease against(ParentProgress parent) {
        PendingProtection protection = new PendingProtection(BACKSTOP);
        return (child, held) -> protection.verdictOn(parent, child.eventTime(), held);
    }

    @Test
    void aHeldChangeIsLetGoOnceItsParentIsEstablishedAbsent() {
        // Until this exists a change waits for its parent for as long as the pipeline runs, and the
        // frontier stays below it the whole time -- so the position its source reads from never advances
        // and the log eventually rotates past it, while nothing has failed and nothing is reported.
        ResolverState state = new ResolverState();
        NestElement orphan = child(400L, 2);
        state.resolve(orphan, HELD_AT);

        List<ReleasedChild> released =
                state.letGo(against(new ParentProgress(true, OptionalLong.of(500L))), HELD_AT);

        assertThat(released)
                .containsExactly(new ReleasedChild(orphan, PendingVerdict.PARENT_ABSENT, Duration.ZERO));
        assertThat(state.waiting()).isEmpty();
    }

    @Test
    void aChangeLetGoOfStopsHoldingTheFrontierDown() {
        // This is the whole point of letting go, and it is a separate claim from the bucket being empty:
        // a change removed from the bucket but still counted in what the level reports would leave the
        // frontier pinned exactly as before, with the change now dead-lettered as well -- the worst of
        // both, and invisible, since the reported bound is what everything downstream reads.
        ResolverState state = new ResolverState();
        state.resolve(child(400L, 2), HELD_AT);
        assertThat(state.lowestHeldByChain()).containsOnlyKeys("items");

        state.letGo(against(new ParentProgress(true, OptionalLong.of(500L))), HELD_AT);

        assertThat(state.lowestHeldByChain()).isEmpty();
    }

    @Test
    void aChangeStillUnderProtectionIsNeitherLetGoNorForgotten() {
        // The level is still loading and the backstop is nowhere near, so nothing has been established and
        // the change stays exactly where it was -- still held, and still keeping the frontier below it.
        ResolverState state = new ResolverState();
        NestElement protectedChild = child(400L, 2);
        state.resolve(protectedChild, HELD_AT);

        List<ReleasedChild> released = state.letGo(
                against(new ParentProgress(false, OptionalLong.empty())), HELD_AT + 1);

        assertThat(released).isEmpty();
        assertThat(state.waiting()).containsExactly(protectedChild);
        assertThat(state.lowestHeldByChain()).containsOnlyKeys("items");
    }

    @Test
    void oneBucketCanHoldChangesThatGoOnDifferentGroundsAndEachCarriesItsOwn() {
        // The two verdicts are not a property of the bucket: they are worked out per change, because each
        // is compared against its own event time and its own age. A bucket that reported one verdict for
        // everything it let go would label established absences as backstop guesses, or worse the reverse.
        ResolverState state = new ResolverState();
        NestElement established = child(400L, 2);
        NestElement guessed = child(500L, 3);
        state.resolve(established, HELD_AT);
        state.resolve(guessed, HELD_AT - BACKSTOP.toMillis());

        List<ReleasedChild> released =
                state.letGo(against(new ParentProgress(true, OptionalLong.of(450L))), HELD_AT);

        assertThat(released).containsExactly(
                new ReleasedChild(established, PendingVerdict.PARENT_ABSENT, Duration.ZERO),
                new ReleasedChild(guessed, PendingVerdict.WALL_CLOCK_BACKSTOP, BACKSTOP));
        assertThat(state.waiting()).isEmpty();
    }

    @Test
    void whatStaysKeepsTheOrderItArrivedIn() {
        // The bucket is drained in arrival order when a parent finally declares, and a sweep that took
        // one out of the middle must not disturb that: the order elements are applied in is what decides
        // which version of a row a document ends up showing.
        ResolverState state = new ResolverState();
        NestElement first = child(500L, 2);
        NestElement second = child(400L, 3);
        NestElement third = child(600L, 4);
        state.resolve(first, HELD_AT);
        state.resolve(second, HELD_AT);
        state.resolve(third, HELD_AT);

        state.letGo(against(new ParentProgress(true, OptionalLong.of(450L))), HELD_AT);

        assertThat(state.waiting()).containsExactly(first, third);
    }
}
