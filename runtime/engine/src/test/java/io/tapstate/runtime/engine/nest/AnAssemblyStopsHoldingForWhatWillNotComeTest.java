package io.tapstate.runtime.engine.nest;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.model.EmbedAs;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.listAt;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an assembly may stop holding the durable frontier for, and what becomes of the change when it does.
 *
 * <p>Two things are held here and they end differently. A change absorbed into a document whose root has
 * not arrived is already in the tree, in its right place: letting go of it means letting the frontier past
 * it and nothing more — the assembly is stored, so the element is still here if the root turns up after
 * all, and throwing it away would lose a row that is in hand. A change waiting for an ancestor that has
 * not arrived is in no document at all and nowhere a sink can see it, so letting go of it is the end of it
 * here, and it is handed back to be routed rather than dropped.
 *
 * <p>The two are also asked different questions, which is why each has its own rule. What a change under
 * an absent root waits for is a root row, and it arrives on a stream this level can name and say how far
 * has been read. What waits for an ancestor waits for an element that arrives already routed by the level
 * below, on an edge that names no stream at all — so nothing about that stream can be asked, and only the
 * backstop can ever end that wait. Answering both from one rule would give one of them an answer built for
 * the other.
 */
class AnAssemblyStopsHoldingForWhatWillNotComeTest {

    private static final List<String> POLICIES = List.of("policies");
    private static final List<String> CLAIMS = List.of("policies", "claims");
    private static final List<EmbedSlot> SHAPE = List.of(new EmbedSlot("policies", EmbedAs.ARRAY,
            List.of(new EmbedSlot("claims", EmbedAs.ARRAY, List.of()))));

    private static final long TAKEN_IN = 1_700_000_000_000L;

    /** A rule that ends every wait it is asked about, so what a test varies is which bucket it is given to. */
    private static final PendingRelease RELEASE = (change, held) -> Optional.of(PendingVerdict.PARENT_ABSENT);

    /** A rule that ends none of them. */
    private static final PendingRelease HOLD = (change, held) -> Optional.empty();

    private static Map<String, ChainPosition> on(String chain, long seq) {
        return Map.of(chain, new ChainPosition(at(seq), "t" + seq));
    }

    /** A row of the embed directly under the root, which is where a change waits for the root itself. */
    private static NestElement policy(String id, long seq) {
        return new NestElement(element(POLICIES, null, id, id), row("policy_no", id), at(seq),
                on("policy", seq), seq);
    }

    /** A row one level deeper, which waits for the policy row it names rather than for the root. */
    private static NestElement claimUnder(String policy, String id, long seq) {
        return new NestElement(element(CLAIMS, policy, id, id), row("claim_no", id), at(seq),
                on("claim", seq), seq);
    }

    @Test
    void aChangeTakenIntoADocumentWhoseRootNeverArrivesStopsHoldingTheFrontier() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);

        List<ReleasedChild> released = assembly.letGo(RELEASE, HOLD, TAKEN_IN + 60_000);

        assertThat(assembly.lowestHeldByChain()).isEmpty();
        assertThat(released).singleElement().satisfies(child -> {
            assertThat(child.child().ref().elementKey()).containsExactly("P1");
            assertThat(child.verdict()).isEqualTo(PendingVerdict.PARENT_ABSENT);
            assertThat(child.heldFor()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void theElementItselfIsKeptWhereItWasPlaced() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.letGo(RELEASE, HOLD, TAKEN_IN + 60_000);

        assembly.applyRoot(row("customer_id", "C1"), at(9));

        assertThat(listAt(assembly.render(SHAPE).orElseThrow(), "policies"))
                .describedAs("what was let go of is the promise to replay it, not the row itself")
                .containsExactly(row("policy_no", "P1", "claims", List.of()));
    }

    @Test
    void aChangeWaitingForAnAncestorIsGoneOnceItIsLetGoOf() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.take(claimUnder("P1", "CL1", 5), TAKEN_IN);

        List<ReleasedChild> released = assembly.letGo(HOLD, RELEASE, TAKEN_IN + 60_000);
        assertThat(released).extracting(child -> child.child().ref().elementKey())
                .containsExactly(List.of("CL1"));

        assembly.take(policy("P1", 6), TAKEN_IN);
        assertThat(listAt(assembly.render(SHAPE).orElseThrow(), "policies", "claims"))
                .describedAs("it was handed back to be routed; attaching it here as well would put one row "
                        + "in two places")
                .isEmpty();
    }

    @Test
    void eachBucketIsAnsweredByItsOwnRule() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(claimUnder("P9", "CL1", 6), TAKEN_IN);

        assertThat(assembly.letGo(RELEASE, HOLD, TAKEN_IN))
                .extracting(child -> child.child().ref().elementKey())
                .containsExactly(List.of("P1"));
        assertThat(assembly.letGo(HOLD, RELEASE, TAKEN_IN))
                .extracting(child -> child.child().ref().elementKey())
                .containsExactly(List.of("CL1"));
    }

    @Test
    void whatTheRuleSaysToGoOnHoldingGoesOnHoldingTheFrontierBack() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(claimUnder("P9", "CL1", 6), TAKEN_IN);

        assertThat(assembly.letGo(HOLD, HOLD, TAKEN_IN + 60_000)).isEmpty();
        assertThat(assembly.lowestHeldByChain())
                .containsOnlyKeys("policy", "claim");
    }

    @Test
    void howLongEachChangeWaitedIsMeasuredFromWhenThatChangeWasTakenIn() {
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.take(policy("P2", 6), TAKEN_IN + 60_000);

        assertThat(assembly.letGo(RELEASE, HOLD, TAKEN_IN + 90_000))
                .extracting(ReleasedChild::heldFor)
                .describedAs("one wait per change, not one per document: they did not start together")
                .containsExactly(Duration.ofMillis(90_000), Duration.ofMillis(30_000));
    }

    @Test
    void theRuleIsHandedTheChangeItselfSoItCanBeAskedWhenItHappened() {
        List<Long> asked = new ArrayList<>();
        RootAssembly assembly = new RootAssembly();
        assembly.take(policy("P1", 5), TAKEN_IN);

        assembly.letGo((change, held) -> {
            asked.add(change.eventTime());
            return Optional.empty();
        }, HOLD, TAKEN_IN);

        assertThat(asked)
                .describedAs("whether the root's stream has been read past this change is the whole of the "
                        + "second test, and it can only be asked of the change")
                .containsExactly(5L);
    }

    @Test
    void nothingIsHeldOnceADocumentCarryingItHasGoneOut() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyRoot(row("customer_id", "C1"), at(1));
        assembly.take(policy("P1", 5), TAKEN_IN);
        assembly.documentSent();

        assertThat(assembly.holdsAnything()).isFalse();
        assertThat(assembly.letGo(RELEASE, RELEASE, TAKEN_IN + 60_000)).isEmpty();
    }

    @Test
    void aChangeTakenInWithoutSayingWhenIsNeverLetGoOf() {
        RootAssembly assembly = new RootAssembly();
        assembly.applyElement(element(POLICIES, null, "P1", "P1"), row("policy_no", "P1"), at(5),
                on("policy", 5));

        assertThat(assembly.letGo(RELEASE, RELEASE, TAKEN_IN))
                .describedAs("nothing here can tell whether the root is late or absent, and ending a wait "
                        + "on no evidence is the outcome that loses a row")
                .isEmpty();
        assertThat(assembly.lowestHeldByChain()).isNotEmpty();
    }
}
