package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RootAssemblyNestingTest {

    private static final List<EmbedSlot> POLICIES_CLAIMS_DOCUMENTS = List.of(
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

    /** An element of the embed at {@code pathId}, hung under the parent row that answers to {@code parent}. */
    private static ElementRef element(List<String> pathId, Object parent, Object key, Object identity) {
        return new ElementRef(pathId, parent, List.of(key), identity);
    }

    /** The array an embed occupies in the rendered document, reached through the first element of each level. */
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

    /** A claims element as the four-level shape renders it: its own row, plus the empty documents array. */
    private static Map<String, Object> claim(String number) {
        return row("claim_no", number, "documents", List.of());
    }

    private static RootAssembly customerWithOnePolicy() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(2));
        return assembly;
    }

    @Test
    void aGrandchildLandsUnderItsOwnParentElement() {
        RootAssembly assembly = customerWithOnePolicy();
        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(3));

        Map<String, Object> document = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies")).hasSize(1);
        assertThat(listAt(document, "policies", "claims")).containsExactly(claim("CL1"));
    }

    @Test
    void aFourLevelTreeAssemblesAllTheWayDown() {
        RootAssembly assembly = customerWithOnePolicy();
        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(3));
        // The deepest row names only its own parent - it never carries the root's key.
        assembly.applyElement(
                element(List.of("policies", "claims", "documents"), "CL1", "D1", null),
                row("document_no", "D1"), at(4));

        Map<String, Object> document = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies", "claims", "documents"))
                .containsExactly(row("document_no", "D1"));
    }

    @Test
    void siblingEmbedsWhoseIdentityValuesCollideStayApart() {
        List<EmbedSlot> twoBranches = List.of(
                new EmbedSlot("policies", EmbedAs.ARRAY, List.of(
                        new EmbedSlot("claims", EmbedAs.ARRAY, List.of()))),
                new EmbedSlot("orders", EmbedAs.ARRAY, List.of(
                        new EmbedSlot("items", EmbedAs.ARRAY, List.of()))));
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        // Two auto-increment keys from different tables, both 77.
        assembly.applyElement(element(List.of("policies"), null, "P77", 77), row("policy_no", "P77"), at(2));
        assembly.applyElement(element(List.of("orders"), null, "O77", 77), row("order_no", "O77"), at(3));

        assembly.applyElement(
                element(List.of("policies", "claims"), 77, "CL1", null), row("claim_no", "CL1"), at(4));
        assembly.applyElement(
                element(List.of("orders", "items"), 77, "IT1", null), row("sku", "IT1"), at(5));

        Map<String, Object> document = assembly.render(twoBranches).orElseThrow();
        assertThat(listAt(document, "policies", "claims")).containsExactly(row("claim_no", "CL1"));
        assertThat(listAt(document, "orders", "items")).containsExactly(row("sku", "IT1"));
    }

    @Test
    void aChildWaitsWhileItsParentElementIsMissingAndAppearsWhenItArrives() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", null), row("claim_no", "CL1"), at(2));

        Map<String, Object> beforeTheParent = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(beforeTheParent, "policies")).isEmpty();

        assembly.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(3));

        Map<String, Object> afterTheParent = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(afterTheParent, "policies", "claims")).containsExactly(claim("CL1"));
    }

    @Test
    void aChildDeleteThatOutrunsItsParentStillDeletes() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        ElementRef claim = element(List.of("policies", "claims"), "P1", "CL1", null);
        assembly.deleteElement(claim, at(10));

        assembly.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(11));
        assembly.applyElement(claim, row("claim_no", "CL1"), at(9));

        Map<String, Object> document = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies", "claims")).isEmpty();
    }

    @Test
    void updatingAParentRowKeepsTheChildrenItAlreadyHas() {
        RootAssembly assembly = customerWithOnePolicy();
        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", null), row("claim_no", "CL1"), at(3));

        assembly.applyElement(
                element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1", "status", "closed"), at(4));

        Map<String, Object> document = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies").get(0)).containsEntry("status", "closed");
        assertThat(listAt(document, "policies", "claims")).containsExactly(claim("CL1"));
    }

    @Test
    void deletingAnElementKeepsItsSubtreeForTheRebuild() {
        RootAssembly assembly = customerWithOnePolicy();
        ElementRef policy = element(List.of("policies"), null, "P1", "P1");
        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", null), row("claim_no", "CL1"), at(3));

        assembly.deleteElement(policy, at(4));
        assertThat(listAt(assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow(), "policies")).isEmpty();

        assembly.applyElement(policy, row("policy_no", "P1"), at(5));
        Map<String, Object> document = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies", "claims")).containsExactly(claim("CL1"));
    }

    @Test
    void aChildOfADeletedParentIsHeldRatherThanDropped() {
        RootAssembly assembly = customerWithOnePolicy();
        ElementRef policy = element(List.of("policies"), null, "P1", "P1");
        assembly.deleteElement(policy, at(3));

        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", null), row("claim_no", "CL1"), at(4));
        assertThat(listAt(assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow(), "policies")).isEmpty();

        assembly.applyElement(policy, row("policy_no", "P1"), at(5));
        Map<String, Object> document = assembly.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies", "claims")).containsExactly(claim("CL1"));
    }

    @Test
    void anElementAlwaysNamesTheEmbedItBelongsTo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ElementRef(List.of(), null, List.of("CL1"), null));
    }

    @Test
    void aWaitingChildSurvivesSerialization() throws Exception {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.applyElement(
                element(List.of("policies", "claims"), "P1", "CL1", null), row("claim_no", "CL1"), at(2));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(assembly);
        }
        RootAssembly restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (RootAssembly) in.readObject();
        }

        restored.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(3));
        Map<String, Object> document = restored.render(POLICIES_CLAIMS_DOCUMENTS).orElseThrow();
        assertThat(listAt(document, "policies", "claims")).containsExactly(claim("CL1"));
    }
}
