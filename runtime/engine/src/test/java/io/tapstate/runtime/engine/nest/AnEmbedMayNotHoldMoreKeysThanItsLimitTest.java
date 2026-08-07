package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.TransformBody;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A level of a nest holds one entry per key of its own, and nothing about the shape of the tree bounds how
 * many keys that is: it is however many rows the source has. Left alone it grows until the memory holding
 * it runs out, and running out of memory cannot say which level did it or what it was holding - so there
 * is a limit, and passing it fails the job with both.
 *
 * <p>What the limit is measured against is the level's own state rather than a tally this run kept, and
 * that difference is the point rather than an implementation detail. State outlives the run that wrote it:
 * a level restarted onto a keyspace that was already too wide has to fail on the first thing it is asked
 * to do, and a counter that began at zero with the process would instead report almost nothing held and
 * let it run - reporting least of all in exactly the situation the limit is for.
 */
class AnEmbedMayNotHoldMoreKeysThanItsLimitTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestVertex POLICIES =
            NestTopology.compile("p", "doc", TREE, tables()).vertexAt(List.of("policies"));

    /** Small enough to reach in a test, and reached by counting keys rather than by anything else. */
    private static final long LIMIT = 3L;

    private static final NestSettings SETTINGS =
            NestSettings.defaults().withKeyLimit(POLICIES.mapName(), LIMIT);

    private final HeapNestStore<ResolverState> store = new HeapNestStore<>();

    @Test
    void anEmbedPastItsLimitFailsTheJobSayingWhatItHoldsAndWhatItMay() throws Exception {
        ResolverProcessor processor = resolver();

        assertThatThrownBy(() -> processor.process(0, policies(LIMIT + 1)))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.resolver-key-limit-exceeded");
    }

    @Test
    void theFailureNamesTheEmbedItsKeysAndItsLimit() throws Exception {
        ResolverProcessor processor = resolver();

        assertThatThrownBy(() -> processor.process(0, policies(LIMIT + 1)))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).args())
                .isEqualTo(Map.of("embedPath", "policies", "keys", LIMIT + 1, "limit", LIMIT));
    }

    @Test
    void anEmbedFilledExactlyToItsLimitKeepsRunning() throws Exception {
        ResolverProcessor processor = resolver();

        assertThatCode(() -> processor.process(0, policies(LIMIT)))
                .describedAs("the limit is what may be held, not the first count that is refused")
                .doesNotThrowAnyException();
        assertThat(store.count()).isEqualTo(LIMIT);
    }

    /**
     * The one a remembered tally cannot pass. Every key here was put there by a previous run, and this run
     * adds none: it touches a key that is already present, so a count kept since this process started would
     * stand at one and see nothing wrong with a level that is already over.
     */
    @Test
    void aLevelRestartedOntoAKeyspaceAlreadyTooWideFailsWithoutAddingAKey() throws Exception {
        for (int i = 0; i <= LIMIT; i++) {
            store.save(List.of("pid" + i), new ResolverState());
        }
        ResolverProcessor processor = resolver();

        assertThatThrownBy(() -> processor.process(0, policyRow(0)))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.resolver-key-limit-exceeded");
        assertThat(store.count())
                .describedAs("this run wrote back the key it touched and created no new one")
                .isEqualTo(LIMIT + 1);
    }

    private ResolverProcessor resolver() throws Exception {
        ResolverProcessor processor = new ResolverProcessor(POLICIES, store, element -> { }, SETTINGS);
        processor.init(new TestOutbox(64), new TestProcessorContext());
        return processor;
    }

    /** {@code keys} policy rows, each declaring a different key of this level. */
    private static TestInbox policies(long keys) {
        TestInbox inbox = new TestInbox();
        for (int i = 0; i < keys; i++) {
            inbox.add(policyRowAt(i));
        }
        return inbox;
    }

    /** One policy row on a key that may or may not already be held. */
    private static TestInbox policyRow(int i) {
        TestInbox inbox = new TestInbox();
        inbox.add(policyRowAt(i));
        return inbox;
    }

    private static Envelope policyRowAt(int i) {
        // policy_id is what this vertex is partitioned by - it is the column the claims beneath name.
        return Envelope.insert(i, "policy",
                        row("policy_id", "pid" + i, "customer_id", "c" + i, "policy_no", "p" + i), null)
                .withOrder(new SourceOrder(1L, i));
    }
}
