package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SuppressWarnings("unchecked")
class RootAssemblyReparentTest {

    private static final List<EmbedSlot> TREE = List.of(
            new EmbedSlot("policies", EmbedAs.ARRAY, List.of(
                    new EmbedSlot("claims", EmbedAs.ARRAY, List.of(
                            new EmbedSlot("documents", EmbedAs.ARRAY, List.of()))))));

    private static SourceOrder at(long seq) {
        return new SourceOrder(1L, seq);
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static ElementRef element(List<String> pathId, Object parent, Object key, Object identity) {
        return new ElementRef(pathId, parent, List.of(key), identity);
    }

    private static ElementRef policy(String id) {
        return element(List.of("policies"), null, id, id);
    }

    /** The claim CL1, hung under whichever policy {@code parent} names. */
    private static ElementRef claimUnder(String parent) {
        return element(List.of("policies", "claims"), parent, "CL1", "CL1");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listAt(Map<String, Object> document, String... path) {
        Object node = document;
        for (int i = 0; i < path.length; i++) {
            node = ((Map<String, Object>) node).get(path[i]);
            if (i < path.length - 1) {
                node = ((List<Map<String, Object>>) node).get(0);
            }
        }
        return (List<Map<String, Object>>) node;
    }

    /** One customer, two policies, and a claim with a document of its own hanging under the first policy. */
    private static RootAssembly customerWithAClaimUnderP1() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(policy("P1"), row("policy_no", "P1"), at(2));
        assembly.applyElement(policy("P2"), row("policy_no", "P2"), at(3));
        assembly.applyElement(claimUnder("P1"), row("claim_no", "CL1"), at(4));
        assembly.applyElement(
                element(List.of("policies", "claims", "documents"), "CL1", "D1", null),
                row("document_no", "D1"), at(5));
        return assembly;
    }

    @Test
    void aMoveCarriesTheWholeNodeIncludingItsSubtree() {
        RootAssembly assembly = customerWithAClaimUnderP1();

        assertThat(assembly.reparentElement(
                claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1"), at(6))).isTrue();

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

    @Test
    void aMoveAppliesTheRowItCarries() {
        RootAssembly assembly = customerWithAClaimUnderP1();

        assembly.reparentElement(
                claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1", "status", "reopened"), at(6));

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
                claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1"), at(3))).isFalse();

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
                claimUnder("P1"), claimUnder("P3"), row("claim_no", "CL1"), at(6));

        List<Map<String, Object>> policies = listAt(assembly.render(TREE).orElseThrow(), "policies");
        assertThat((List<?>) policies.get(0).get("claims")).isEmpty();

        assembly.applyElement(policy("P3"), row("policy_no", "P3"), at(7));

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
        assembly.applyElement(policy("P2"), row("policy_no", "P2"), at(2));

        assembly.reparentElement(claimUnder("P1"), claimUnder("P2"), row("claim_no", "CL1"), at(3));

        assertThat(listAt(assembly.render(TREE).orElseThrow(), "policies", "claims"))
                .singleElement()
                .satisfies(claim -> assertThat(claim).containsEntry("claim_no", "CL1"));
    }

    @Test
    void aMoveNamesOneElement() {
        RootAssembly assembly = customerWithAClaimUnderP1();
        ElementRef other = element(List.of("policies", "claims"), "P2", "CL9", "CL9");

        assertThatIllegalArgumentException().isThrownBy(() ->
                assembly.reparentElement(claimUnder("P1"), other, row("claim_no", "CL1"), at(6)));
    }
}
