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
 * How many documents a nest holds is how many roots its source has, which nothing about the tree bounds
 * either. It is a different quantity from how many keys a level below holds - one document can draw on
 * hundreds of thousands of keys of one level, and a level can serve a single document - so it is limited
 * by its own number and reported by its own failure. Sharing one of either with the levels beneath would
 * make whichever is the wider of the two the only one that ever fires.
 *
 * <p>The two are also limited for different reasons, which is why the reasons are worth keeping apart in
 * what is reported. A level with too many keys has outgrown what is holding them; a nest with too many
 * documents has outgrown what the deployment was built to store, whether or not any of it is in memory.
 */
class ANestMayNotHoldMoreDocumentsThanItsLimitTest {

    private static final TransformBody.Nest TREE = nest("customer", List.of("customer_id"),
            embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies", List.of("policy_no"),
                    embed("claim", "policy_id", "policy_id", EmbedAs.ARRAY, "claims", List.of("claim_id"))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final NestVertex ASSEMBLER = TOPOLOGY.assembler();

    /** Two documents fit; the third is what must stop the job. */
    private static final long LIMIT = 2L;

    private static final NestSettings SETTINGS =
            NestSettings.defaults().withRootLimit(ASSEMBLER.mapName(), LIMIT);

    private final HeapNestStore<RootAssembly> store = new HeapNestStore<>();

    @Test
    void aNestPastItsLimitFailsTheJobSayingWhatItHoldsAndWhatItMay() throws Exception {
        AssemblerProcessor processor = assembler(SETTINGS);

        assertThatThrownBy(() -> processor.process(0, customers(LIMIT + 1)))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.root-count-limit-exceeded");
    }

    @Test
    void theFailureNamesTheNestItsDocumentsAndItsLimit() throws Exception {
        AssemblerProcessor processor = assembler(SETTINGS);

        assertThatThrownBy(() -> processor.process(0, customers(LIMIT + 1)))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).args())
                .isEqualTo(Map.of("stepId", "doc", "roots", LIMIT + 1, "limit", LIMIT));
    }

    @Test
    void aNestFilledExactlyToItsLimitKeepsRunning() throws Exception {
        AssemblerProcessor processor = assembler(SETTINGS);

        assertThatCode(() -> processor.process(0, customers(LIMIT)))
                .describedAs("the limit is what may be held, not the first count that is refused")
                .doesNotThrowAnyException();
        assertThat(store.count()).isEqualTo(LIMIT);
    }

    /**
     * The one a remembered tally cannot pass, for the same reason it cannot at any other level: every root
     * here was written by a previous run, and this run adds none.
     */
    @Test
    void aNestRestartedOntoMoreDocumentsThanItMayHoldFailsWithoutAddingOne() throws Exception {
        for (int i = 0; i <= LIMIT; i++) {
            store.save(List.of(i), new RootAssembly());
        }
        AssemblerProcessor processor = assembler(SETTINGS);

        assertThatThrownBy(() -> processor.process(0, customerRow(0)))
                .isInstanceOf(TapstateException.class)
                .extracting(thrown -> ((TapstateException) thrown).code().code())
                .isEqualTo("nest.root-count-limit-exceeded");
        assertThat(store.count())
                .describedAs("this run wrote back the root it touched and created no new one")
                .isEqualTo(LIMIT + 1);
    }

    /**
     * How many documents there may be and how many keys there may be are two settings that a namespace
     * carries at once, not one number read twice. The confusion is easy to make and would not show: the
     * assembler's own namespace can be given both, and reading the wrong one bounds the documents at a
     * figure nobody set for them. So the key limit is set as low as it goes and the documents must ignore
     * it entirely.
     */
    @Test
    void aKeyLimitOnTheNestsOwnNamespaceDoesNotBoundHowManyDocumentsThereMayBe() throws Exception {
        NestSettings keysOnly = NestSettings.defaults().withKeyLimit(ASSEMBLER.mapName(), 1L);
        AssemblerProcessor processor = assembler(keysOnly);

        assertThatCode(() -> processor.process(0, customers(5)))
                .describedAs("documents are bounded by the document limit alone")
                .doesNotThrowAnyException();
    }

    private AssemblerProcessor assembler(NestSettings settings) throws Exception {
        AssemblerProcessor processor =
                new AssemblerProcessor(ASSEMBLER, TOPOLOGY.slots(), store, "doc", settings);
        processor.init(new TestOutbox(128), new TestProcessorContext());
        return processor;
    }

    /** {@code roots} customer rows, each its own document. */
    private static TestInbox customers(long roots) {
        TestInbox inbox = new TestInbox();
        for (int i = 0; i < roots; i++) {
            inbox.add(customerRowAt(i));
        }
        return inbox;
    }

    private static TestInbox customerRow(int i) {
        TestInbox inbox = new TestInbox();
        inbox.add(customerRowAt(i));
        return inbox;
    }

    private static Envelope customerRowAt(int i) {
        return Envelope.insert(i, "customer", row("customer_id", i, "name", "n" + i), null)
                .withOrder(new SourceOrder(1L, i));
    }
}
