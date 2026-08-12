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

import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The primitive the last two structural families are built out of: an element with a subtree beneath it
 * has to be able to leave one document and turn up whole in another.
 *
 * <p>It cannot travel as a change. A change carries one row, and what hangs beneath an element is rows
 * that arrived long ago and that nothing will ever send again - the source has no reason to, the row that
 * moved is the only one that was edited. So the subtree has to be handed over as data, which means it has
 * to be readable out of the document that has it and applicable to the one that gains it.
 *
 * <p>Handed over deepest-last, so a parent is always placed before its children. Placed the other way
 * round, a child arrives before the element it hangs under exists and is parked as waiting - correct in
 * the end, but it turns a hand-over into a pile of pending changes and makes what should be one document's
 * worth of work depend on the order a map happened to iterate in.
 *
 * <p>The element that moved is deliberately <em>not</em> part of what is handed over: it is the one row
 * the source did edit, so it arrives on its own as a change like any other. Including it here would mean
 * two paths could place it, and the two would have to agree about which order wins.
 */
class ASubtreeHandedToAnotherDocumentArrivesWholeTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"),
                            embed("document", "claim_id", "claim_id", EmbedAs.ARRAY, "documents",
                                    List.of("document_no")))));

    private static final List<EmbedSlot> SLOTS =
            NestTopology.compile("p", "doc", TREE, tables()).slots();

    private static final ElementRef POLICY = element(List.of("policies"), null, "PN-1", "P1");

    @Test
    void whatIsHandedOverIsEverythingBeneathTheElementAndNotTheElement() {
        RootAssembly had = documentWithAPolicyHoldingTwoClaims();

        List<NestElement> handedOver = had.detachSubtree(POLICY);

        assertThat(handedOver)
                .describedAs("two claims and the document under the first, and not the policy itself")
                .hasSize(3);
        assertThat(handedOver).noneMatch(change -> change.ref().equals(POLICY));
    }

    @Test
    void theDocumentItLeftKeepsNothingOfIt() {
        RootAssembly had = documentWithAPolicyHoldingTwoClaims();

        had.detachSubtree(POLICY);

        assertThat(listAt(had.render(SLOTS).orElseThrow(), "policies"))
                .describedAs("the element and everything beneath it are gone together")
                .isEmpty();
    }

    @Test
    void theDocumentThatGainsItShowsTheWholeSubtree() {
        RootAssembly had = documentWithAPolicyHoldingTwoClaims();
        List<NestElement> handedOver = had.detachSubtree(POLICY);

        RootAssembly gains = new RootAssembly();
        gains.applyRoot(row("customer_id", "C2"), at(1));
        gains.take(new NestElement(POLICY, row("policy_no", "PN-1"), at(9), noPositions()));
        handedOver.forEach(gains::take);

        Map<String, Object> document = gains.render(SLOTS).orElseThrow();
        assertThat(listAt(document, "policies")).hasSize(1);
        assertThat(listAt(document, "policies", "claims"))
                .describedAs("both claims travelled")
                .hasSize(2);
        assertThat(listAt(document, "policies", "claims", "documents"))
                .describedAs("and what hung under a claim travelled with the claim")
                .hasSize(1);
    }

    /**
     * A parent before its children, so nothing is ever parked as waiting on the way in. Asserted on the
     * depth of each change rather than on the exact sequence: what matters is that no child precedes its
     * ancestor, not which of two siblings goes first.
     */
    @Test
    void aParentIsAlwaysHandedOverBeforeWhatHangsUnderIt() {
        RootAssembly had = documentWithAPolicyHoldingTwoClaims();

        List<NestElement> handedOver = had.detachSubtree(POLICY);

        // The element itself does not travel, so the shallowest thing handed over is one level under it.
        int deepest = POLICY.pathId().size();
        for (NestElement change : handedOver) {
            int depth = change.ref().pathId().size();
            assertThat(depth)
                    .describedAs("%s arrived before something it hangs under", change.ref())
                    .isLessThanOrEqualTo(deepest + 1);
            deepest = Math.max(deepest, depth);
        }
    }

    /** Nothing is there to hand over, which is not a failure - it is the answer for a leaf. */
    @Test
    void anElementWithNothingBeneathItHandsOverNothing() {
        RootAssembly had = documentWithAPolicyHoldingTwoClaims();
        ElementRef claimWithNoDocuments = element(List.of("policies", "claims"), "P1", "K2", "K2");

        assertThat(had.detachSubtree(claimWithNoDocuments)).isEmpty();
    }

    /**
     * A record of a deletion beneath the element travels too. Left behind, the document that gains the
     * element has no record that the row was deleted, and a replay from beneath the frontier puts it back -
     * in the new document, where nothing will ever delete it again.
     */
    @Test
    void aRecordOfADeletionBeneathItTravelsAsWell() {
        RootAssembly had = documentWithAPolicyHoldingTwoClaims();
        had.deleteElement(element(List.of("policies", "claims"), "P1", "K2", "K2"), at(8), noPositions());

        List<NestElement> handedOver = had.detachSubtree(POLICY);

        assertThat(handedOver)
                .describedAs("the deleted claim is handed over as the record it now is")
                .anyMatch(change -> change.deletion() && change.ref().elementKey().equals(List.of("K2")));
    }

    /** One customer, one policy, two claims under it, and one document under the first claim. */
    private static RootAssembly documentWithAPolicyHoldingTwoClaims() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.take(new NestElement(POLICY, row("policy_no", "PN-1"), at(2), noPositions()));
        assembly.take(new NestElement(element(List.of("policies", "claims"), "P1", "K1", "K1"),
                row("claim_id", "K1"), at(3), noPositions()));
        assembly.take(new NestElement(element(List.of("policies", "claims"), "P1", "K2", "K2"),
                row("claim_id", "K2"), at(4), noPositions()));
        assembly.take(new NestElement(
                element(List.of("policies", "claims", "documents"), "K1", "D1", null),
                row("document_no", "D1"), at(5), noPositions()));
        return assembly;
    }
}
