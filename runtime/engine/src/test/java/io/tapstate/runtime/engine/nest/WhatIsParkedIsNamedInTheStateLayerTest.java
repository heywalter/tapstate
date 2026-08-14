package io.tapstate.runtime.engine.nest;

import static io.tapstate.runtime.engine.nest.NestFixtures.at;
import static io.tapstate.runtime.engine.nest.NestFixtures.element;
import static io.tapstate.runtime.engine.nest.NestFixtures.noPositions;
import static io.tapstate.runtime.engine.nest.NestFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.tapstate.spi.store.KeyedStateStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a nest parks between documents is state like any other, so it is written through to the layer behind
 * the map - and the layer files under a name, which has to exist for the kind of key a parked entry is
 * filed by.
 *
 * <p>These keys are not the kind every other nest key is. A vertex's own state is filed by the tuple of
 * values that vertex is partitioned by, every one of them a scalar read off a row. A parked subtree is
 * filed by an address instead - which embed, and which element inside it - because that address is the one
 * thing both halves of a move arrive at without either knowing anything about the other. A naming that only
 * knows scalars has no answer for one of these, and the answer it gives instead takes the job down.
 *
 * <p><b>It is only reachable with a store behind the map, which is why nothing here had ever run it.</b>
 * The heap stores every job-level case in this tree runs on name no key at all: a name is asked for as an
 * entry is written through or read back, and nothing stands behind those maps to write through to. So the
 * vehicle is the running shape - a member configured the way a pipeline configures one - rather than the
 * naming on its own.
 */
class WhatIsParkedIsNamedInTheStateLayerTest {

    private static final String NAMESPACE = "nest.p1.n1.policies";

    private static final List<String> POLICIES = List.of("policies");

    private HazelcastInstance member;

    private final HeapKeyedStateStore cold = new HeapKeyedStateStore();

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    /**
     * The whole of it in one pass: an entry parked on a map with a store behind it reaches that store, and
     * comes back out of it as what was parked. Both halves matter - a name that cannot be produced fails the
     * write, and a name that is produced differently on the way back finds nothing where the entry is.
     */
    @Test
    void aParkedSubtreeIsWrittenThroughAndComesBackAsWhatWasParked() {
        NestStore<ParkedSubtree> parking = parking();
        ParkedSubtree.At address = new ParkedSubtree.At(POLICIES, List.of("P2"));

        parking.save(address, new ParkedSubtree(List.of(claim("K1", "P1"))));

        assertThat(cold.count(NAMESPACE + ".parking"))
                .describedAs("a parked subtree is state, so the layer behind the map has it")
                .isEqualTo(1L);
        ParkedSubtree reread = parking.load(address);
        assertThat(reread).isNotNull();
        assertThat(reread.changes()).hasSize(1);
        assertThat(reread.changes().get(0).fields()).isEqualTo(row("claim_id", "K1", "policy_id", "P1"));
    }

    /**
     * The requirement the naming exists for. Two addresses that are not equal must never be filed under one
     * name, or one move's rows are read and overwritten as another's - silently, and with the right shape.
     *
     * <p>Two whole numbers of different width, because that is the pair only the kinds separate: written out,
     * both are the same digit, and a naming built from the values alone gives them one name. A string beside
     * a number would prove nothing here - its quotes already keep it apart.
     */
    @Test
    void twoParkedKeysAlikeButForTheTypeOfTheirIdentityStayTwoEntries() {
        NestStore<ParkedSubtree> parking = parking();
        ParkedSubtree.At narrow = new ParkedSubtree.At(POLICIES, List.of(1));
        ParkedSubtree.At wide = new ParkedSubtree.At(POLICIES, List.of(1L));

        parking.save(narrow, new ParkedSubtree(List.of(claim("K1", "P1"))));
        parking.save(wide, new ParkedSubtree(List.of(claim("K2", "P1"), claim("K3", "P1"))));

        assertThat(cold.count(NAMESPACE + ".parking"))
                .describedAs("two addresses that are not equal are two entries in the layer")
                .isEqualTo(2L);
        assertThat(parking.load(narrow).changes()).hasSize(1);
        assertThat(parking.load(wide).changes()).hasSize(2);
    }

    /**
     * The other way one address could be read as another: the embed and the identity beside it are two
     * lists, and a naming that ran them together would make a deeper path holding less indistinguishable
     * from a shallower one holding more.
     */
    @Test
    void aParkedKeyIsNotConfusedWithOneWhoseEmbedIsADeeperPath() {
        NestStore<ParkedSubtree> parking = parking();
        ParkedSubtree.At shallow = new ParkedSubtree.At(List.of("policies"), List.of("claims", "P2"));
        ParkedSubtree.At deep = new ParkedSubtree.At(List.of("policies", "claims"), List.of("P2"));

        parking.save(shallow, new ParkedSubtree(List.of(claim("K1", "P1"))));
        parking.save(deep, new ParkedSubtree(List.of(claim("K2", "P1"), claim("K3", "P1"))));

        assertThat(cold.count(NAMESPACE + ".parking")).isEqualTo(2L);
        assertThat(parking.load(shallow).changes()).hasSize(1);
        assertThat(parking.load(deep).changes()).hasSize(2);
    }

    /** Letting go of a collected entry has to reach the layer too, or the next look finds it again. */
    @Test
    void lettingGoOfAParkedKeyLetsGoOfItInTheLayerBehindTheMap() {
        NestStore<ParkedSubtree> parking = parking();
        ParkedSubtree.At address = new ParkedSubtree.At(POLICIES, List.of("P2"));
        parking.save(address, new ParkedSubtree(List.of(claim("K1", "P1"))));

        parking.remove(address);

        assertThat(cold.count(NAMESPACE + ".parking"))
                .describedAs("an entry taken in is gone from the layer, not only from memory")
                .isZero();
        assertThat(parking.load(address)).isNull();
    }

    private NestStore<ParkedSubtree> parking() {
        member = startMember(cold);
        return NestBinding.onMap().bind(member).forParking(vertex());
    }

    private static NestElement claim(String claimId, String policyId) {
        return new NestElement(element(List.of("policies", "claims"), policyId, claimId, null),
                row("claim_id", claimId, "policy_id", policyId), at(1L), noPositions(), null);
    }

    private static HazelcastInstance startMember(KeyedStateStore store) {
        Config config = new Config();
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.setProperty("hazelcast.shutdownhook.enabled", "false");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        config.addMapConfig(NestSettings.defaults().backedStateMaps());
        HazelcastInstance started = Hazelcast.newHazelcastInstance(config);
        // The configuration names the store and the member is what holds it, so it is bound after the
        // member exists and before any map on it is used.
        NestStateMapStoreFactory.bindTo(started, store);
        return started;
    }

    private static NestVertex vertex() {
        return new NestVertex(POLICIES, "nest:n1:policies", NAMESPACE,
                List.of("policy_id"), List.of("customer_id"), List.of());
    }
}
