package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.core.model.EmbedAs;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a document keeps to say an element was deleted, counted apart from everything else it holds.
 *
 * <p>It needs a count of its own because neither of the two already here reaches it. The width of a document
 * counts what would be rendered, and a deletion renders as nothing; what one key holds pending counts changes
 * waiting for something that has not arrived, and a deletion is waiting for nothing - it arrived, it was
 * applied, and what is left is the record that it happened. So a document can sit at nothing pending and one
 * element wide while keeping a million deletions, and both counts say it is small.
 *
 * <p>That is not a gap in either count but the growth surface between them: deletions are kept until a replay
 * can no longer bring back what they deleted, so how many there are follows the durable frontier rather than
 * the shape of the data. A frontier that stops moving is a document that grows without bound, with every
 * quantity anyone is watching reading normal.
 */
class WhatADocumentKeepsForADeletedElementIsCountedOnItsOwnTest {

    private static final List<String> POLICIES = List.of("policies");
    private static final List<String> CLAIMS = List.of("policies", "claims");


    @Test
    void aDeletedElementIsKeptAndCounted() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5));

        assembly.take(policyDeleted("P1", 6));

        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    @Test
    void aLiveElementIsNotOne() {
        RootAssembly assembly = new RootAssembly();

        assembly.take(policy("P1", 5));

        assertThat(assembly.tombstones()).isZero();
    }

    /**
     * The boundary against the width of a document, which deliberately counts the other way. Tying how wide a
     * document may be to how far behind the replay window is would bound it by something neither the data nor
     * whoever set the limit can see.
     */
    @Test
    void aDeletionIsNotCountedAmongTheElementsOfTheDocument() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5));

        assembly.take(policyDeleted("P1", 6));

        assertThat(assembly.elements())
                .describedAs("what renders is nothing, and the width of a document is what renders")
                .isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    /**
     * The boundary against what one key holds pending, which is the other count that could be mistaken for
     * this one. A deletion is not waiting for anything, so nothing that ends a wait will ever reach it.
     */
    @Test
    void aDeletionIsNotCountedAmongWhatIsPending() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "c1"), at(4));
        assembly.take(policy("P1", 5));
        assembly.take(policyDeleted("P1", 6));

        assembly.render(shape());
        assembly.documentSent();

        assertThat(assembly.pending())
                .describedAs("the deletion went out with the document; the record it happened stays behind")
                .isZero();
        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    @Test
    void deletionsAreCountedAtEveryDepth() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5));
        assembly.take(claimUnder("P1", "K1", 6));

        assembly.take(claimDeleted("P1", "K1", 7));
        assembly.take(policyDeleted("P1", 8));

        assertThat(assembly.tombstones())
                .describedAs("a deletion four levels down occupies memory as much as one under the root")
                .isEqualTo(2);
    }

    @Test
    void anElementBroughtBackIsNoLongerCountedAsDeleted() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5));
        assembly.take(policyDeleted("P1", 6));

        assembly.take(policy("P1", 7));

        assertThat(assembly.tombstones()).isZero();
        assertThat(assembly.elements()).isEqualTo(1);
    }

    /**
     * A deletion of an element that was never seen is kept and counted like any other: it is exactly the
     * record that stops a replayed insert from building a row the source has already removed.
     */
    @Test
    void aDeletionOfSomethingNeverSeenIsCountedToo() {
        RootAssembly assembly = new RootAssembly();

        assembly.take(policyDeleted("P1", 6));

        assertThat(assembly.tombstones()).isEqualTo(1);
    }

    private static List<EmbedSlot> shape() {
        return List.of(new EmbedSlot("policies", EmbedAs.ARRAY,
                List.of(new EmbedSlot("claims", EmbedAs.ARRAY, List.of()))));
    }

    private static NestElement policy(String id, long seq) {
        return new NestElement(element(POLICIES, null, id, id), row("policy_no", id), at(seq),
                noPositions());
    }

    private static NestElement policyDeleted(String id, long seq) {
        return new NestElement(element(POLICIES, null, id, id), null, at(seq), noPositions());
    }

    private static NestElement claimUnder(String policy, String id, long seq) {
        return new NestElement(element(CLAIMS, policy, id, id), row("claim_no", id), at(seq),
                noPositions());
    }

    private static NestElement claimDeleted(String policy, String id, long seq) {
        return new NestElement(element(CLAIMS, policy, id, id), null, at(seq), noPositions());
    }
}
