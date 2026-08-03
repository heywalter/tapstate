package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an assembly owes the durable frontier. An element whose ancestor has not arrived has been taken
 * off the stream and put nowhere a sink can see it, so the frontier must stay below where it came from:
 * were it allowed past, a restart would neither replay that change nor find it, and the document would
 * be short an element with nothing anywhere reporting a problem.
 *
 * <p>The bound is only ever a position an element really arrived with, and both halves of it travel
 * together — the order to compare on, the token to persist and hand back to the connector.
 */
class RootAssemblyPendingBoundTest {

    private static final List<String> CLAIMS = List.of("policies", "claims");
    private static final List<String> DOCUMENTS = List.of("policies", "claims", "documents");

    private static Map<String, ChainPosition> on(String chain, long seq, String token) {
        return Map.of(chain, new ChainPosition(at(seq), token));
    }

    private static RootAssembly customer() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        return assembly;
    }

    @Test
    void anElementWaitingForAnAncestorReportsThePositionItArrivedWith() {
        RootAssembly assembly = customer();

        assembly.applyElement(element(CLAIMS, "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(5),
                on("claim", 5, "t5"));

        assertThat(assembly.lowestPendingByChain())
                .containsExactly(Map.entry("claim", new ChainPosition(at(5), "t5")));
    }

    @Test
    void anElementThatFoundItsAncestorStopsHoldingTheFrontierBack() {
        RootAssembly assembly = customer();
        assembly.applyElement(element(CLAIMS, "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(5),
                on("claim", 5, "t5"));

        assembly.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(6),
                on("policy", 6, "t6"));

        assertThat(assembly.lowestPendingByChain()).isEmpty();
    }

    @Test
    void theLowestPositionOnAChainIsReported() {
        RootAssembly assembly = customer();

        // Out of order on purpose: the lowest is neither the first to arrive nor the last.
        assembly.applyElement(element(CLAIMS, "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(7),
                on("claim", 7, "t7"));
        assembly.applyElement(element(CLAIMS, "P1", "CL2", "CL2"), row("claim_no", "CL2"), at(5),
                on("claim", 5, "t5"));
        assembly.applyElement(element(CLAIMS, "P1", "CL3", "CL3"), row("claim_no", "CL3"), at(9),
                on("claim", 9, "t9"));

        assertThat(assembly.lowestPendingByChain())
                .containsExactly(Map.entry("claim", new ChainPosition(at(5), "t5")));
    }

    @Test
    void everyChainReportsItsOwnBoundAndTheyAreNeverCompared() {
        RootAssembly assembly = customer();

        assembly.applyElement(element(CLAIMS, "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(9),
                on("claim", 9, "t9"));
        assembly.applyElement(element(DOCUMENTS, "CL9", "D1", null), row("document_no", "D1"), at(4),
                on("document", 4, "t4"));

        assertThat(assembly.lowestPendingByChain()).containsOnly(
                Map.entry("claim", new ChainPosition(at(9), "t9")),
                Map.entry("document", new ChainPosition(at(4), "t4")));
    }

    @Test
    void aDeletionWaitingForAnAncestorHoldsTheFrontierBackJustAsARowDoes() {
        RootAssembly assembly = customer();

        assembly.deleteElement(element(CLAIMS, "P1", "CL1", "CL1"), at(5), on("claim", 5, "t5"));

        assertThat(assembly.lowestPendingByChain())
                .containsExactly(Map.entry("claim", new ChainPosition(at(5), "t5")));
    }

    @Test
    void anElementMovedToAParentThatHasNotArrivedHoldsTheFrontierBack() {
        RootAssembly assembly = customer();
        assembly.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(2),
                noPositions());
        assembly.applyElement(element(CLAIMS, "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(3),
                noPositions());

        assembly.reparentElement(element(CLAIMS, "P1", "CL1", "CL1"), element(CLAIMS, "P9", "CL1", "CL1"),
                row("claim_no", "CL1"), at(8), on("claim", 8, "t8"));

        assertThat(assembly.lowestPendingByChain())
                .containsExactly(Map.entry("claim", new ChainPosition(at(8), "t8")));
    }

    @Test
    void aWaitingElementKeepsItsPositionAcrossBeingStoredAndRestored() throws Exception {
        RootAssembly assembly = customer();
        assembly.applyElement(element(CLAIMS, "P1", "CL1", "CL1"), row("claim_no", "CL1"), at(5),
                on("claim", 5, "t5"));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(assembly);
        }
        RootAssembly restored;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (RootAssembly) in.readObject();
        }

        assertThat(restored.lowestPendingByChain())
                .containsExactly(Map.entry("claim", new ChainPosition(at(5), "t5")));
    }

    @Test
    void anAssemblyWithNothingWaitingReportsNoBoundAtAll() {
        RootAssembly assembly = customer();
        assembly.applyElement(element(List.of("policies"), null, "P1", "P1"), row("policy_no", "P1"), at(2),
                on("policy", 2, "t2"));

        assertThat(assembly.lowestPendingByChain()).isEmpty();
    }
}
