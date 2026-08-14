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
 * How much one key is holding for something that has not arrived. It is the quantity the pending limit is
 * about, and what it counts is changes rather than rows: a level holds every change it has taken in and
 * not passed on, one entry each, because a change that may stop being held has to be picked out of them
 * one at a time.
 *
 * <p>That is the whole difference between this count and the width of a document. One element updated a
 * thousand times under a root that has not arrived is one element and a thousand held changes - the
 * document is no wider for it, and what has to be in memory has grown a thousandfold. Counting elements
 * here would read that as one.
 *
 * <p>Both places a change can wait are counted together, because both are the same thing to whoever set the
 * limit: a change consumed, held, and shown to nobody. They differ in what ends the wait, not in what it
 * costs to wait - and the one that is easy to overlook is the commoner of the two, since a row of an embed
 * directly under the root names no ancestor and is attached to the document on arrival rather than parked.
 *
 * <p>What has gone out is not held: a document that was rendered released everything it carried, and
 * counting those would tie the limit to how much has flowed through rather than to how much is stuck.
 */
class WhatOneKeyHoldsPendingIsCountedByChangeTest {

    private static final List<String> POLICIES = List.of("policies");
    private static final List<String> CLAIMS = List.of("policies", "claims");
    private static final List<EmbedSlot> SHAPE = List.of(new EmbedSlot("policies", EmbedAs.ARRAY,
            List.of(new EmbedSlot("claims", EmbedAs.ARRAY, List.of()))));

    private static final long TAKEN_IN = 1_700_000_000_000L;

    @Test
    void aChildHeldForAParentNotYetKnownIsOnePending() {
        ResolverState state = new ResolverState();

        state.resolve(policy("P1", 5), TAKEN_IN);

        assertThat(state.pending()).isEqualTo(1);
    }

    @Test
    void aDeclarationThatReleasesWhatWaitedLeavesNothingPending() {
        ResolverState state = new ResolverState();
        state.resolve(policy("P1", 5), TAKEN_IN);
        state.resolve(policy("P2", 6), TAKEN_IN);

        state.declare("c1", at(7));

        assertThat(state.pending())
                .describedAs("what was released is on its way, not held - a count of arrivals would say two")
                .isZero();
    }

    @Test
    void aChangeAbsorbedWhileTheRootIsAbsentCountsAsPending() {
        RootAssembly assembly = new RootAssembly();

        assembly.take(policy("P1", 5));

        assertThat(assembly.pending())
                .describedAs("a row under the root is attached on arrival and held all the same")
                .isEqualTo(1);
    }

    @Test
    void aChangeWaitingForAnAncestorCountsAsPending() {
        RootAssembly assembly = new RootAssembly();

        assembly.take(claimUnder("P1", "K1", 5));

        assertThat(assembly.pending()).isEqualTo(1);
    }

    /**
     * The one that says which of two readings this is: the same element written three times is one element
     * of the document and three changes held against it.
     */
    @Test
    void everyChangeToOneElementIsHeldAndCountedOnItsOwn() {
        RootAssembly assembly = new RootAssembly();

        assembly.take(policy("P1", 5));
        assembly.take(policy("P1", 6));
        assembly.take(policy("P1", 7));

        assertThat(assembly.elements())
                .describedAs("the document is one policy wide however often that policy was written")
                .isEqualTo(1);
        assertThat(assembly.pending()).isEqualTo(3);
    }

    @Test
    void aDocumentThatWentOutLeavesNothingPending() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "c1"), at(4));
        assembly.take(policy("P1", 5));

        assembly.render(SHAPE);
        assembly.documentSent();

        assertThat(assembly.pending()).isZero();
    }

    private static NestElement policy(String id, long seq) {
        return new NestElement(element(POLICIES, null, id, id), row("policy_no", id), at(seq),
                noPositions());
    }

    private static NestElement claimUnder(String policy, String id, long seq) {
        return new NestElement(element(CLAIMS, policy, id, id), row("claim_no", id), at(seq),
                noPositions());
    }
}
