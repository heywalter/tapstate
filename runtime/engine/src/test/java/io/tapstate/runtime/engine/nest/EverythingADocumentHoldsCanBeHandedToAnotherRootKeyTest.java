package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.listAt;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The primitive the last structural family is built out of: a root row whose own key changes takes the
 * whole document with it, not a subtree of it.
 *
 * <p>Every other move takes one element out of a document that goes on existing. This one ends the
 * document: the key it was filed under names a row the source no longer has, and everything that hung
 * beneath it belongs to a key that has never been seen before. So what has to be readable out is not a
 * subtree but all of it, at every depth - and the rows are exactly the ones nothing will ever send again,
 * since the source edited one row and considers the rest untouched.
 *
 * <p>Shallowest first for the reason a subtree is: whoever applies them places a parent before its
 * children, so nothing is parked as waiting on the way in.
 */
class EverythingADocumentHoldsCanBeHandedToAnotherRootKeyTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"),
                            embed("document", "claim_id", "claim_id", EmbedAs.ARRAY, "documents",
                                    List.of("document_no")))));

    private static final List<EmbedSlot> SLOTS =
            NestTopology.compile("p", "doc", TREE, tables()).slots();

    private static final ElementRef POLICY = element(List.of("policies"), null, "PN-1", "P1");
    private static final ElementRef OTHER_POLICY = element(List.of("policies"), null, "PN-2", "P2");
    private static final ElementRef CLAIM = element(List.of("policies", "claims"), "P1", "K1", "K1");

    /**
     * The element that moved is not handed over when a subtree moves, because the source edited that row and
     * it arrives on its own. Here the source edited the <em>root</em>, so every element is a row nothing will
     * resend - including the ones directly under the root, which a subtree hand-over would have left behind.
     */
    @Test
    void whatIsHandedOverIsEveryElementAtEveryDepth() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();

        List<NestElement> handedOver = had.detachEverything();

        assertThat(handedOver)
                .describedAs("two policies, one claim, one document under it")
                .hasSize(4);
        assertThat(handedOver)
                .describedAs("the elements directly under the root travel too, unlike in a subtree move")
                .anyMatch(change -> change.ref().equals(POLICY))
                .anyMatch(change -> change.ref().equals(OTHER_POLICY));
    }

    @Test
    void theKeyItLeftHoldsNothingOfItAfterwards() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();

        had.detachEverything();

        assertThat(had.elements())
                .describedAs("the document under the old key is empty, not merely re-rendered")
                .isZero();
        assertThat(listAt(had.render(SLOTS).orElseThrow(), "policies")).isEmpty();
    }

    @Test
    void theNewKeyShowsTheWholeTreeItGained() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();
        List<NestElement> handedOver = had.detachEverything();

        RootAssembly gains = new RootAssembly();
        gains.applyRoot(row("customer_id", "C2"), at(20));
        handedOver.forEach(gains::take);

        Map<String, Object> document = gains.render(SLOTS).orElseThrow();
        assertThat(listAt(document, "policies")).hasSize(2);
        assertThat(listAt(document, "policies", "claims"))
                .describedAs("what hung two levels down arrived with its parent")
                .hasSize(1);
        assertThat(listAt(document, "policies", "claims", "documents")).hasSize(1);
    }

    /**
     * Asserted on depth rather than on the exact sequence: what matters is that no child precedes the
     * element it hangs under, not which of two siblings goes first. Placed the other way round, every child
     * would be parked as waiting on the way in and a hand-over would turn into a pile of pending changes.
     */
    @Test
    void aParentIsAlwaysHandedOverBeforeWhatHangsUnderIt() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();

        List<NestElement> handedOver = had.detachEverything();

        int deepest = 0;
        for (NestElement change : handedOver) {
            int depth = change.ref().pathId().size();
            assertThat(depth)
                    .describedAs("%s arrived before something it hangs under", change.ref())
                    .isLessThanOrEqualTo(deepest + 1);
            deepest = Math.max(deepest, depth);
        }
    }

    /**
     * Left behind, the document that gains the tree has no record that the row was deleted, and a replay
     * from beneath the frontier puts it back - under the new key, where nothing will ever delete it again.
     */
    @Test
    void aRecordOfADeletionTravelsAsWell() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();
        had.deleteElement(OTHER_POLICY, at(8), noPositions());

        List<NestElement> handedOver = had.detachEverything();

        assertThat(handedOver)
                .describedAs("the deleted policy is handed over as the record it now is")
                .anyMatch(change -> change.deletion() && change.ref().elementKey().equals(List.of("PN-2")));
    }

    /**
     * A child whose parent never arrived is in the document's state and in nothing else. Left behind under a
     * key that is being deleted, it goes with the key: nothing resends it, and nothing anywhere reports that
     * it went.
     */
    @Test
    void whatWasWaitingForAParentThatNeverArrivedTravelsToo() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();
        ElementRef underAnAbsentClaim =
                element(List.of("policies", "claims", "documents"), "K9", "D9", null);
        had.take(new NestElement(underAnAbsentClaim, row("document_no", "D9"), at(9), noPositions()));
        assertThat(had.pending()).describedAs("it really is waiting, not placed").isPositive();

        List<NestElement> handedOver = had.detachEverything();

        assertThat(handedOver)
                .describedAs("it travels, and goes on waiting under the new key rather than vanishing")
                .anyMatch(change -> change.ref().equals(underAnAbsentClaim));
    }

    /**
     * The old key is deleted the moment its tree has been read out, and what is left of it has to become
     * reclaimable. Holding on to the positions of changes that are now somebody else's would keep the
     * deleted key un-droppable for the life of the job, with every count reading healthy.
     */
    @Test
    void theKeyItLeftIsHoldingNothingBackAfterwards() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();
        had.take(new NestElement(CLAIM, row("claim_id", "K1"), at(30),
                Map.of("chain-a", new ChainPosition(new SourceOrder(1L, 30L), "t30"))));

        had.detachEverything();

        assertThat(had.lowestHeldByChain())
                .describedAs("what it held travelled with the tree; the hand-over is what holds the frontier now")
                .isEmpty();
    }

    /**
     * What travels carries no position of its own, which is what stops one change from being counted as
     * covered twice - once by the document that let it go and again by the document that gains it. What keeps
     * the frontier below a move in flight is the move being outstanding, not a second copy of every position
     * in the tree; and a position reported as covered by two documents is a frontier past a change only one
     * of them really carried, which no assertion about either document would ever show.
     */
    @Test
    void whatTravelsCarriesNoPositionOfItsOwn() {
        RootAssembly had = documentWithTwoPoliciesAndADeeperSubtree();
        had.take(new NestElement(CLAIM, row("claim_id", "K1"), at(30),
                Map.of("chain-a", new ChainPosition(new SourceOrder(1L, 30L), "t30"))));

        assertThat(had.detachEverything())
                .describedAs("a hand-over is rows, not the accounting for them")
                .allMatch(change -> change.positions().isEmpty());
    }

    /** Nothing to hand over is the answer for a document with no elements, not a failure. */
    @Test
    void aDocumentWithNoElementsHandsOverNothing() {
        RootAssembly bare = new RootAssembly();
        bare.applyRoot(row("customer_id", "C1"), at(1));

        assertThat(bare.detachEverything()).isEmpty();
    }

    /** One customer, two policies, one claim under the first, one document under that claim. */
    private static RootAssembly documentWithTwoPoliciesAndADeeperSubtree() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.take(new NestElement(POLICY, row("policy_no", "PN-1"), at(2), noPositions()));
        assembly.take(new NestElement(OTHER_POLICY, row("policy_no", "PN-2"), at(3), noPositions()));
        assembly.take(new NestElement(CLAIM, row("claim_id", "K1"), at(4), noPositions()));
        assembly.take(new NestElement(
                element(List.of("policies", "claims", "documents"), "K1", "D1", null),
                row("document_no", "D1"), at(5), noPositions()));
        return assembly;
    }
}
