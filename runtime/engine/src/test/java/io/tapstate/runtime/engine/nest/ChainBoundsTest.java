package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far one vertex instance lets the durable frontier go. The bound it reports is always a change that
 * really arrived, and never one with a change still held beneath it: everything at or below the bound is
 * claimed to have gone downstream, and a change held back has not.
 *
 * <p>The failing shape this is here to stop is silent and only visible after a crash — a bound that runs
 * ahead of a held change means a restart replays from above that change, so it is neither delivered nor
 * replayable, and no count anywhere is wrong.
 */
class ChainBoundsTest {

    private static final String CLAIMS = "claim";
    private static final String POLICIES = "policy";

    private static Map<String, ChainPosition> on(String chain, long seq, String token) {
        return Map.of(chain, new ChainPosition(at(seq), token));
    }

    @Test
    void withNothingHeldTheBoundIsTheHighestChangeThatWentOut() {
        ChainBounds bounds = new ChainBounds();

        bounds.emitted(on(CLAIMS, 90, "t90"));
        bounds.emitted(on(CLAIMS, 400, "t400"));

        assertThat(bounds.lowerBounds())
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(400), "t400")));
    }

    @Test
    void nothingIsReportedForAChainThatHasOnlyEverHeldChangesBack() {
        ChainBounds bounds = new ChainBounds();

        bounds.held(on(CLAIMS, 100, "t100"));

        assertThat(bounds.lowerBounds())
                .describedAs("there is no change below it to report, so the chain does not advance at all")
                .isEmpty();
    }

    @Test
    void aChangeStillHeldKeepsTheBoundBelowIt() {
        ChainBounds bounds = new ChainBounds();

        bounds.emitted(on(CLAIMS, 90, "t90"));
        bounds.held(on(CLAIMS, 100, "t100"));
        bounds.emitted(on(CLAIMS, 400, "t400"));
        bounds.emitted(on(CLAIMS, 500, "t500"));

        assertThat(bounds.lowerBounds())
                .describedAs("400 and 500 went out, but claiming them would claim 100 along with them")
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(90), "t90")));
    }

    @Test
    void releasingWhatWasHeldLetsTheBoundReachWhatHadAlreadyGoneOut() {
        ChainBounds bounds = new ChainBounds();
        bounds.emitted(on(CLAIMS, 90, "t90"));
        bounds.held(on(CLAIMS, 100, "t100"));
        bounds.emitted(on(CLAIMS, 500, "t500"));

        bounds.released(on(CLAIMS, 100, "t100"));
        bounds.emitted(on(CLAIMS, 100, "t100"));

        assertThat(bounds.lowerBounds())
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(500), "t500")));
    }

    @Test
    void eachHeldChangeStopsTheBoundAtTheHighestChangeThatWentOutBeneathIt() {
        ChainBounds bounds = new ChainBounds();
        bounds.emitted(on(CLAIMS, 90, "t90"));
        bounds.emitted(on(CLAIMS, 95, "t95"));
        bounds.held(on(CLAIMS, 100, "t100"));
        bounds.emitted(on(CLAIMS, 200, "t200"));
        bounds.emitted(on(CLAIMS, 250, "t250"));
        bounds.held(on(CLAIMS, 300, "t300"));
        bounds.emitted(on(CLAIMS, 400, "t400"));

        assertThat(bounds.lowerBounds())
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(95), "t95")));

        bounds.released(on(CLAIMS, 100, "t100"));
        bounds.emitted(on(CLAIMS, 100, "t100"));

        assertThat(bounds.lowerBounds())
                .describedAs("the next change still held is 300, so the bound reaches the highest below it")
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(250), "t250")));

        bounds.released(on(CLAIMS, 300, "t300"));
        bounds.emitted(on(CLAIMS, 300, "t300"));

        assertThat(bounds.lowerBounds())
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(400), "t400")));
    }

    @Test
    void theBoundNeverGoesBackwards() {
        ChainBounds bounds = new ChainBounds();
        bounds.emitted(on(CLAIMS, 500, "t500"));
        assertThat(bounds.lowerBounds())
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(500), "t500")));

        bounds.held(on(CLAIMS, 600, "t600"));

        assertThat(bounds.lowerBounds())
                .describedAs("a bound already reported has been acted on downstream and cannot be taken back")
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(500), "t500")));
    }

    @Test
    void chainsAreTrackedApartAndNeverCompared() {
        ChainBounds bounds = new ChainBounds();

        bounds.emitted(on(CLAIMS, 400, "claims-400"));
        bounds.held(on(POLICIES, 10, "policies-10"));
        bounds.emitted(on(POLICIES, 5, "policies-5"));

        assertThat(bounds.lowerBounds())
                .describedAs("one chain being held back says nothing about how far another may go")
                .containsOnly(
                        Map.entry(CLAIMS, new ChainPosition(at(400), "claims-400")),
                        Map.entry(POLICIES, new ChainPosition(at(5), "policies-5")));
    }

    @Test
    void theTokenReportedIsTheOneThatArrivedWithThatOrder() {
        ChainBounds bounds = new ChainBounds();

        bounds.emitted(on(CLAIMS, 90, "mysql-bin.000042:19088743"));
        bounds.held(on(CLAIMS, 100, "mysql-bin.000042:19088999"));
        bounds.emitted(on(CLAIMS, 400, "mysql-bin.000042:19090000"));

        assertThat(bounds.lowerBounds().get(CLAIMS))
                .describedAs("the two halves travel as a pair: pick a bound by order, persist its own token")
                .isEqualTo(new ChainPosition(at(90), "mysql-bin.000042:19088743"));
    }

    @Test
    void aChangeCarryingNoTokenStillMovesTheBound() {
        ChainBounds bounds = new ChainBounds();

        bounds.emitted(on(CLAIMS, 90, null));

        assertThat(bounds.lowerBounds())
                .describedAs("a snapshot row is ordered but is not a spot in a change stream; skipping it "
                        + "would leave a snapshot-only chain never advancing at all")
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(90), null)));
    }

    @Test
    void aChangeThatWillNeverReachADocumentDoesNotHoldTheBoundBack() {
        ChainBounds bounds = new ChainBounds();
        bounds.held(on(CLAIMS, 100, "t100"));
        bounds.emitted(on(CLAIMS, 400, "t400"));

        bounds.released(on(CLAIMS, 100, "t100"));

        assertThat(bounds.lowerBounds())
                .describedAs("a change routed to the dead letter is accounted for there and stops pinning "
                        + "the frontier, which is what keeps one dangling key from stalling a whole chain")
                .containsExactly(Map.entry(CLAIMS, new ChainPosition(at(400), "t400")));
    }
}
