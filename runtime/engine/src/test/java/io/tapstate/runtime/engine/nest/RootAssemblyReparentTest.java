package io.tapstate.runtime.engine.nest;

import io.tapstate.core.model.EmbedAs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.listAt;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SuppressWarnings("unchecked")
class RootAssemblyReparentTest {

    private static final List<EmbedSlot> TREE = List.of(
            new EmbedSlot("policies", EmbedAs.ARRAY, List.of(
                    new EmbedSlot("claims", EmbedAs.ARRAY, List.of(
                            new EmbedSlot("documents", EmbedAs.ARRAY, List.of()))))));

    private static ElementRef policy(String id) {
        return element(List.of("policies"), null, id, id);
    }

    /** The claim CL1, hung under whichever policy {@code parent} names. */
    private static ElementRef claimUnder(String parent) {
        return element(List.of("policies", "claims"), parent, "CL1", "CL1");
    }

    /** One customer, two policies, and a claim with a document of its own hanging under the first policy. */
    private static RootAssembly customerWithAClaimUnderP1() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(policy("P1"), row("policy_no", "P1"), at(2), noPositions());
        assembly.applyElement(policy("P2"), row("policy_no", "P2"), at(3), noPositions());
        assembly.applyElement(claimUnder("P1"), row("claim_no", "CL1"), at(4), noPositions());
        assembly.applyElement(
                element(List.of("policies", "claims", "documents"), "CL1", "D1", null),
                row("document_no", "D1"), at(5), noPositions());
        return assembly;
    }

    @Test
    void aMoveCarriesTheWholeNodeIncludingItsSubtree() {
        RootAssembly assembly = customerWithAClaimUnderP1();

        assertThat(assembly.reparentElement(
                claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1"), at(6), noPositions())).isTrue();

        Map<String, Object> document = assembly.render(TREE).orElseThrow();
        List<Map<String, Object>> policies = listAt(document, "policies");
        assertThat(policies).hasSize(2);
        // The old parent keeps nothing - an element left behind is the ghost this exists to prevent.
        assertThat((List<?>) policies.get(0).get("claims")).isEmpty();
        // The new parent gets the claim AND the document beneath it: moving only the row loses the subtree,
        // and nothing will ever resend those descendants.
        List<Map<String, Object>> movedClaims = (List<Map<String, Object>>) policies.get(1).get("claims");
        assertThat(movedClaims).hasSize(1);
        assertThat((List<Map<String, Object>>) movedClaims.get(0).get("documents"))
                .containsExactly(row("document_no", "D1"));
    }

    /**
     * An identity a node no longer has may not still answer to it. The name a child reaches its parent by
     * is the parent's identity, so a move that changes one leaves the old name pointing at a node that is
     * not what it says: a child naming it is placed under a parent it does not belong to, and the document
     * is wrong with no count out of place. Waiting is the right answer instead - nothing here has that
     * identity, and something arriving with it later is what the child is waiting for.
     */
    @Test
    void anIdentityAMoveGaveUpNoLongerAnswersForTheNodeThatHadIt() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(policy("P1"), row("policy_no", "P1"), at(2), noPositions());

        assembly.reparentElement(policy("P1"), renamedPolicy("P1", "P9"),
                row("policy_no", "P1"), at(3), noPositions());
        assembly.applyElement(claimUnder("P1"), row("claim_no", "CL1"), at(4), noPositions());

        Map<String, Object> document = assembly.render(TREE).orElseThrow();
        assertThat((List<?>) listAt(document, "policies").get(0).get("claims"))
                .describedAs("the policy answers to P9 now; a claim that named P1 belongs to whatever "
                        + "turns up under that name, not to whichever node used to have it")
                .isEmpty();
    }

    /**
     * A node released from the waiting bucket has to take up its identity like any other, and let go of
     * whatever was waiting on it. Attaching it alone leaves an identity nothing answers to while the node
     * that has it is right there in the tree, so its descendants wait for the rest of the run - present in
     * this document's state, in no rendered document, and reported by nothing.
     */
    @Test
    void aMovedNodeReleasedFromTheWaitingBucketTakesUpItsNewIdentity() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(policy("P1"), row("policy_no", "P1"), at(2), noPositions());
        assembly.applyElement(claimUnder("P1"), row("claim_no", "CL1"), at(3), noPositions());
        // A document that belongs to the identity the claim is about to take, arriving before it does.
        assembly.applyElement(element(List.of("policies", "claims", "documents"), "CL9", "D1", null),
                row("document_no", "D1"), at(4), noPositions());

        // The claim moves under a policy that has not arrived, and takes a new child-facing identity with it.
        assembly.reparentElement(claimUnder("P1"),
                element(List.of("policies", "claims"), "P2", "CL1", "CL9"),
                row("claim_no", "CL1"), at(5), noPositions());
        assembly.applyElement(policy("P2"), row("policy_no", "P2"), at(6), noPositions());

        Map<String, Object> document = assembly.render(TREE).orElseThrow();
        List<Map<String, Object>> movedClaims =
                (List<Map<String, Object>>) listAt(document, "policies").get(1).get("claims");
        assertThat(movedClaims).describedAs("the claim landed under the policy that arrived").hasSize(1);
        assertThat((List<Map<String, Object>>) movedClaims.get(0).get("documents"))
                .describedAs("and what was waiting on the identity it took up landed with it")
                .containsExactly(row("document_no", "D1"));
    }

    /** The same policy element under a new child-facing identity. */
    private static ElementRef renamedPolicy(String id, String identity) {
        return element(List.of("policies"), null, id, identity);
    }

    @Test
    void aMoveAppliesTheRowItCarries() {
        RootAssembly assembly = customerWithAClaimUnderP1();

        assembly.reparentElement(
                claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1", "status", "reopened"), at(6), noPositions());

        assertThat(listAt(assembly.render(TREE).orElseThrow(), "policies").get(1))
                .extracting("claims")
                .satisfies(claims -> assertThat((List<Map<String, Object>>) claims)
                        .singleElement()
                        .satisfies(claim -> assertThat(claim).containsEntry("status", "reopened")));
    }

    @Test
    void anOlderMoveIsRefusedAndChangesNothing() {
        RootAssembly assembly = customerWithAClaimUnderP1();

        assertThat(assembly.reparentElement(
                claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1"), at(3), noPositions())).isFalse();

        List<Map<String, Object>> policies = listAt(assembly.render(TREE).orElseThrow(), "policies");
        assertThat((List<?>) policies.get(0).get("claims")).hasSize(1);
        assertThat((List<?>) policies.get(1).get("claims")).isEmpty();
    }

    @Test
    void aMoveToAParentThatHasNotArrivedHoldsTheNodeRatherThanLeavingItWhereItWas() {
        RootAssembly assembly = customerWithAClaimUnderP1();

        // P3's row has not arrived. Leaving the claim under P1 would show a relationship the source has
        // already contradicted - and if P3 never arrives, it would stay wrong for good with no signal.
        assembly.reparentElement(
                claimUnder("P1"), claimUnder("P3"), row("claim_no", "CL1"), at(6), noPositions());

        List<Map<String, Object>> policies = listAt(assembly.render(TREE).orElseThrow(), "policies");
        assertThat((List<?>) policies.get(0).get("claims")).isEmpty();

        assembly.applyElement(policy("P3"), row("policy_no", "P3"), at(7), noPositions());

        List<Map<String, Object>> afterP3 = listAt(assembly.render(TREE).orElseThrow(), "policies");
        assertThat(afterP3).hasSize(3);
        List<Map<String, Object>> heldClaims = (List<Map<String, Object>>) afterP3.get(2).get("claims");
        assertThat(heldClaims).hasSize(1);
        // The subtree travelled with it through the wait.
        assertThat((List<Map<String, Object>>) heldClaims.get(0).get("documents"))
                .containsExactly(row("document_no", "D1"));
    }

    @Test
    void movingAnElementThatWasNeverHereJustPlacesIt() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(policy("P2"), row("policy_no", "P2"), at(2), noPositions());

        assembly.reparentElement(claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1"), at(3), noPositions());

        assertThat(listAt(assembly.render(TREE).orElseThrow(), "policies", "claims"))
                .singleElement()
                .satisfies(claim -> assertThat(claim).containsEntry("claim_no", "CL1"));
    }

    /**
     * A move stays inside one embed. Both halves of an address may change at once - the parent a row hangs
     * under and the key the document shows it by - but the embed it is an element of is what makes it the
     * same element at all, so a move naming two of them names two elements.
     */
    @Test
    void aMoveStaysWithinOneEmbed() {
        RootAssembly assembly = customerWithAClaimUnderP1();
        ElementRef elsewhere = element(List.of("policies"), null, "CL1", "CL1");

        assertThatIllegalArgumentException().isThrownBy(() ->
                assembly.reparentElement(claimUnder("P1"), elsewhere, row("claim_no", "CL1"), at(6),
                        noPositions()));
    }

    /**
     * The other half of the same relaxation, and the case a tree hits whenever an embed is keyed by the
     * column its children point at: one row edited once moves the element to another parent and renames it
     * in the document, and both have to land or the element is addressed by something nothing carries.
     */
    @Test
    void bothHalvesOfAnAddressMayChangeAtOnce() {
        RootAssembly assembly = customerWithAClaimUnderP1();
        ElementRef renamedUnderP2 = element(List.of("policies", "claims"), "P2", "CL9", "CL1");

        assertThat(assembly.reparentElement(claimUnder("P1"), renamedUnderP2,
                row("claim_no", "CL9"), at(6), noPositions())).isTrue();

        Map<String, Object> document = assembly.render(TREE).orElseThrow();
        assertThat(listAt(document, "policies", "claims"))
                .describedAs("nothing left behind under the policy it came from")
                .isEmpty();
        Map<String, Object> underP2 = listAt(document, "policies").get(1);
        assertThat(listAt(underP2, "claims")).singleElement()
                .satisfies(claim -> assertThat(claim).containsEntry("claim_no", "CL9"));
    }
}
