package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.embed;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.nest;
import static io.tapstate.runtime.engine.nest.NestTreeFixtures.tables;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.core.test.TestInbox;
import com.hazelcast.jet.core.test.TestOutbox;
import com.hazelcast.jet.core.test.TestProcessorContext;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.Op;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.NestRoot;
import io.tapstate.core.model.TransformBody;
import io.tapstate.runtime.engine.ReplayFloor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A change that arrives after a restart goes out on the whole document, not on what the restart left in
 * memory.
 *
 * <p>A nest publishes a document rather than a row, and a sink applies it whole. So a level that comes back
 * up empty and assembles the next change on its own has not lost a version - it has written one. The
 * document on the sink held everything the root ever absorbed; what replaces it holds the one change that
 * happened to arrive first. Every reading around it says the run is healthy: the job is RUNNING, no error is
 * counted, the source resumed from the right position, and the sink did apply exactly what it was handed.
 * The damage is in the payload and nowhere else.
 *
 * <p><b>Which change wakes the level decides what the damage looks like, so both are asked here.</b> A child
 * alone cannot show it: its root is absent from an empty level, and a document with no root row is never
 * sent - so a level with nothing behind it goes quiet rather than wrong, and quiet is not the failure being
 * guarded. A root row is what turns the same emptiness into a send: it is all a document needs to be
 * renderable, and rendered against an empty level it carries no children at all. That is the one that
 * reaches the sink.
 *
 * <p>The restart is a real one for the state: the member is shut down and another started over the same
 * layer behind the map, so what the second one answers with can only have come from that layer. Nothing is
 * loaded on the way up - the layer refuses to list its keys - so the answer is fetched by the change that
 * asks for it, which is what "no warm-up" means here.
 *
 * <p>The last test is the positive control, and it is the same run over a map with nothing behind it. It
 * fails the way this guards against: the document goes out holding none of what it had. Without it, a
 * passing first test could mean the assertion never had a way to fail.
 */
class AChangeArrivingAfterARestartGoesOutOnTheWholeDocumentTest {

    private static final TransformBody.Nest TREE = nest(new NestRoot("customer", List.of("customer_id"),
            null, true,
            List.of(embed("policy", "customer_id", "customer_id", EmbedAs.ARRAY, "policies",
                    List.of("policy_no")))));

    private static final NestTopology TOPOLOGY = NestTopology.compile("p", "doc", TREE, tables());

    private static final int OWN_ROWS = 0;
    private static final int POLICIES = 1;

    /** How many policies the document holds before anything is restarted. */
    private static final int HELD_BEFORE = 2;

    /** Survives the member, which is the whole point of it: this is what the second member reads back. */
    private final HeapKeyedStateStore cold = new HeapKeyedStateStore();

    private final TestOutbox out = new TestOutbox(512);
    private final Ticking clock = new Ticking();

    private HazelcastInstance member;

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    @Test
    void aRootRowArrivingAfterARestartGoesOutWithEverythingTheDocumentHeld() throws Exception {
        AssemblerProcessor before = assemblerOn(memberWithColdLayer());
        customer(before, "C1", "Ada");
        policies(before, "C1", HELD_BEFORE);

        AssemblerProcessor after = assemblerOn(restarted());
        List<Object> emitted = feed(after, OWN_ROWS, renamed("C1", "Ada", "Grace"));

        assertThat(documentsIn(emitted))
                .describedAs("a root row is all a document needs to be renderable, so this one goes to the "
                        + "sink whatever the level holds - and the sink applies it whole, over the version "
                        + "that had the policies in it")
                .containsExactly("C1:" + HELD_BEFORE);
    }

    @Test
    void aChildArrivingAfterARestartIsAddedToWhatTheDocumentAlreadyHeld() throws Exception {
        AssemblerProcessor before = assemblerOn(memberWithColdLayer());
        customer(before, "C1", "Ada");
        policies(before, "C1", HELD_BEFORE);

        AssemblerProcessor after = assemblerOn(restarted());
        List<Object> emitted = feed(after, POLICIES, policy("C1", HELD_BEFORE));

        assertThat(documentsIn(emitted))
                .describedAs("the root row it hangs from was read back with the rest, so the change is "
                        + "merged into what was there rather than becoming the document")
                .containsExactly("C1:" + (HELD_BEFORE + 1));
    }

    /**
     * The positive control. Same run, same assertions available, over a map that has nothing behind it -
     * which is what a nest was before its state was given a layer to survive in. It publishes the fragment.
     */
    @Test
    void withNothingBehindTheMapTheSameRootRowGoesOutOnAFragment() throws Exception {
        AssemblerProcessor before = assemblerOn(memberWith(NestSettings.defaults().stateMaps()));
        customer(before, "C1", "Ada");
        policies(before, "C1", HELD_BEFORE);

        AssemblerProcessor after = assemblerOn(restartedWith(NestSettings.defaults().stateMaps()));
        List<Object> emitted = feed(after, OWN_ROWS, renamed("C1", "Ada", "Grace"));

        assertThat(documentsIn(emitted))
                .describedAs("nothing is wrong anywhere a count can see: one document went out, for the "
                        + "right key, carrying the change that was made. It is the policies that are gone")
                .containsExactly("C1:0");
    }

    // ---- harness ------------------------------------------------------------------------

    private HazelcastInstance memberWithColdLayer() {
        return memberWith(NestSettings.defaults().backedStateMaps());
    }

    private HazelcastInstance memberWith(MapConfig maps) {
        Config config = new Config();
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.addMapConfig(maps);
        member = Hazelcast.newHazelcastInstance(config);
        NestStateMapStoreFactory.bindTo(member, cold);
        return member;
    }

    private HazelcastInstance restarted() {
        return restartedWith(NestSettings.defaults().backedStateMaps());
    }

    /**
     * Takes the member away and brings another up over the same layer behind the map. Nothing of the first
     * one is carried across but that layer, so an answer from the second can have come from nowhere else.
     */
    private HazelcastInstance restartedWith(MapConfig maps) {
        member.shutdown();
        member = null;
        return memberWith(maps);
    }

    private AssemblerProcessor assemblerOn(HazelcastInstance on) throws Exception {
        NestBinding.NestStores stores = NestBinding.onMap().bind(on);
        AssemblerProcessor processor = new AssemblerProcessor(TOPOLOGY.assembler(), TOPOLOGY.slots(),
                stores.forAssembler(TOPOLOGY.assembler()), "doc", null, null, ReplayFloor.NONE,
                NestSettings.defaults(), clock, NestSendPolicy.within(0),
                stores.forParking(TOPOLOGY.assembler()), (from, released) -> { });
        processor.init(out, new TestProcessorContext());
        return processor;
    }

    private List<Object> feed(AssemblerProcessor processor, int ordinal, Envelope event) {
        TestInbox inbox = new TestInbox();
        inbox.queue().add(event);
        processor.process(ordinal, inbox);
        List<Object> emitted = new ArrayList<>();
        out.drainQueueAndReset(0, emitted, false);
        return emitted;
    }

    private void customer(AssemblerProcessor processor, String customerId, String name) {
        feed(processor, OWN_ROWS, Envelope.insert(1, "customer",
                row("customer_id", customerId, "name", name), null).withOrder(at(1)));
    }

    private void policies(AssemblerProcessor processor, String customerId, int count) {
        for (int i = 0; i < count; i++) {
            feed(processor, POLICIES, policy(customerId, i));
        }
    }

    private static Envelope policy(String customerId, int index) {
        return Envelope.insert(2, "policy",
                row("policy_id", "P" + index, "customer_id", customerId, "policy_no", "PN-" + index), null)
                .withOrder(at(10 + index));
    }

    private static Envelope renamed(String customerId, String was, String is) {
        return Envelope.update(9, "customer",
                row("customer_id", customerId, "name", was),
                row("customer_id", customerId, "name", is), null).withOrder(at(90));
    }

    /** Every assembled document that went downstream, as {@code customer_id:policyCount}. */
    private static List<String> documentsIn(List<Object> emitted) {
        List<String> documents = new ArrayList<>();
        for (Object item : emitted) {
            if (item instanceof Envelope event && event.op() != Op.DELETE && event.after() != null) {
                Object policies = event.after().get("policies");
                documents.add(event.after().get("customer_id") + ":"
                        + (policies instanceof List<?> held ? held.size() : 0));
            }
        }
        return documents;
    }

    /** A clock that only moves when the test moves it, so nothing here is measured against a real one. */
    private static final class Ticking implements NestClock {

        private long now = 1_000_000L;

        @Override
        public long millis() {
            return now;
        }
    }
}
