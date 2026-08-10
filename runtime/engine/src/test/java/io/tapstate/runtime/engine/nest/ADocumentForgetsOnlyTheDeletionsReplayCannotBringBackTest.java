package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.listAt;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * When a document may stop keeping the record that an element was deleted. The record is what makes a
 * deletion survive a replay - an insert delivered again lands below it and is refused - so it may go only
 * once that replay can no longer happen, which is what the position a restart would resume from says.
 *
 * <p>Late costs one entry until the next sweep; early is a row the source deleted coming back and staying
 * back, with nothing anywhere reporting it. So every way of not knowing keeps it: a chain whose floor cannot
 * be read, a deletion that arrived carrying no position of its own, a deletion still holding a subtree
 * beneath it.
 *
 * <p>The subtree is not a detail of the gate but most of it. What hangs beneath a deleted element is kept on
 * purpose, because nothing will ever resend those rows, and dropping the element they hang from takes them
 * with it. A deletion is therefore forgotten from the bottom up: children first, and the element that held
 * them only once there is nothing left under it.
 */
class ADocumentForgetsOnlyTheDeletionsReplayCannotBringBackTest {

    private static final List<String> POLICIES = List.of("policies");
    private static final List<String> CLAIMS = List.of("policies", "claims");
    private static final List<EmbedSlot> SHAPE = List.of(new EmbedSlot("policies", EmbedAs.ARRAY,
            List.of(new EmbedSlot("claims", EmbedAs.ARRAY, List.of()))));

    private static final String CHAIN = "policies-chain";
    private static final long TAKEN_IN = 1_700_000_000_000L;

    @Test
    void aDeletionBelowTheFloorIsForgotten() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(policyDeleted("P1", 6), TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(floorAt(9));

        assertThat(forgotten).isEqualTo(1);
        assertThat(assembly.tombstones()).isZero();
    }

    /**
     * The floor is where a restart resumes, so a deletion sitting exactly on it is delivered again. Reading
     * this boundary the other way forgets the one record the replay about to happen needs.
     */
    @Test
    void aDeletionAtTheFloorIsKept() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(policyDeleted("P1", 6), TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(floorAt(6));

        assertThat(forgotten).isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    @Test
    void aDeletionOnAChainWithNoKnownFloorIsKept() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(policyDeleted("P1", 6), TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(ReplayFloor.NONE);

        assertThat(forgotten)
                .describedAs("not knowing is not the same as knowing it is safe")
                .isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    @Test
    void aDeletionThatCameWithNoPositionOfItsOwnIsKept() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(new NestElement(element(POLICIES, null, "P1", "P1"), null, at(6), noPositions(), 6),
                TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(floorAt(9));

        assertThat(forgotten)
                .describedAs("nothing to weigh against the floor, so nothing that says the replay is past")
                .isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    @Test
    void aLiveElementIsNeverForgotten() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "c1"), at(4));
        assembly.take(policy("P1", 5), TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(floorAt(9));

        assertThat(forgotten).isZero();
        assertThat(assembly.elements()).isEqualTo(1);
    }

    /**
     * The one the whole gate is for. Nothing will ever resend the rows hanging beneath a deleted element, so
     * letting the element go while they are still there loses them for good, with the document simply
     * quieter than it was.
     */
    @Test
    void aDeletionStillHoldingASubtreeIsKept() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(claimUnder("P1", "K1", 6), TAKEN_IN);
        assembly.take(policyDeleted("P1", 7), TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(floorAt(9));

        assertThat(forgotten).isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
        assertThat(assembly.elements())
                .describedAs("the claim beneath it is still there and comes back with the policy")
                .isEqualTo(1);
    }

    @Test
    void aSubtreeOfDeletionsCollapsesFromTheBottom() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(claimUnder("P1", "K1", 6), TAKEN_IN);
        assembly.take(claimDeleted("P1", "K1", 7), TAKEN_IN);
        assembly.take(policyDeleted("P1", 8), TAKEN_IN);

        long forgotten = assembly.forgetDeletionsBelow(floorAt(9));

        assertThat(forgotten)
                .describedAs("the claim goes first, which is what leaves nothing under the policy")
                .isEqualTo(2);
        assertThat(assembly.tombstones()).isZero();
    }

    /**
     * A child arriving under a deleted parent hangs beneath it rather than parking anywhere, which is what
     * makes the subtree the whole of the "nothing waiting on it" condition rather than half of it.
     */
    @Test
    void aChildArrivingUnderADeletedParentKeepsThatDeletionFromBeingForgotten() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(policyDeleted("P1", 6), TAKEN_IN);

        assembly.take(claimUnder("P1", "K1", 7), TAKEN_IN);
        long forgotten = assembly.forgetDeletionsBelow(floorAt(9));

        assertThat(forgotten).isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    /**
     * Forgetting has to take the element out of everything that names it, not just out of the array it was
     * rendered from. Leave the name behind and the next child to arrive is filed under a row that is no
     * longer part of this document: it is neither rendered nor waiting for anything, so it never reaches the
     * document and nothing anywhere reports a row missing.
     */
    @Test
    void aForgottenDeletionTakesTheNameItAnsweredToWithIt() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "c1"), at(4));
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(policyDeleted("P1", 6), TAKEN_IN);
        assembly.forgetDeletionsBelow(floorAt(9));

        assembly.take(claimUnder("P1", "K1", 20), TAKEN_IN);
        assembly.take(policy("P1", 21), TAKEN_IN);

        Map<String, Object> document = assembly.render(SHAPE).orElseThrow();
        assertThat(listAt(document, "policies"))
                .describedAs("the policy is back")
                .hasSize(1);
        assertThat(listAt(document, "policies", "claims"))
                .describedAs("and the claim that arrived while it was gone is under it, not lost")
                .hasSize(1);
    }

    private static ReplayFloor floorAt(long seq) {
        SourceOrder resumesAt = at(seq);
        return chain -> CHAIN.equals(chain) ? Optional.of(resumesAt) : Optional.empty();
    }

    private static Map<String, ChainPosition> from(long seq) {
        return Map.of(CHAIN, new ChainPosition(at(seq), "token-" + seq));
    }

    private static NestElement policy(String id, long seq) {
        return new NestElement(element(POLICIES, null, id, id), row("policy_no", id), at(seq),
                from(seq), seq);
    }

    private static NestElement policyDeleted(String id, long seq) {
        return new NestElement(element(POLICIES, null, id, id), null, at(seq), from(seq), seq);
    }

    private static NestElement claimUnder(String policy, String id, long seq) {
        return new NestElement(element(CLAIMS, policy, id, id), row("claim_no", id), at(seq),
                from(seq), seq);
    }

    private static NestElement claimDeleted(String policy, String id, long seq) {
        return new NestElement(element(CLAIMS, policy, id, id), null, at(seq), from(seq), seq);
    }
}
