package io.tapstate.runtime.engine.nest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.processor.Processors;
import io.tapstate.core.common.TapstateException;
import io.tapstate.runtime.engine.Engine;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A pipeline's budget applies to that pipeline's levels and to nobody else's, which means naming each of
 * its namespaces exactly - the pattern the member started with covers every nest on the process, and a
 * number written into it would be every pipeline's number.
 *
 * <p><b>What a map holds is settled the first time that map's name is configured, and stays settled for
 * as long as the process runs.</b> Measured: adding the same configuration again is accepted, adding a
 * different one is refused by the substrate, and the number already in place is the one that stays. So an
 * author who edits the budget and restarts the pipeline gets the old number unless something says
 * otherwise - the pipeline running on a figure its own artifact contradicts, with nothing reporting it.
 * That is what the coded refusal here exists for; it is not the substrate's exception passed along.
 *
 * <p>A pipeline asking for what the process already gives is left alone entirely. Pinning an exact
 * configuration that merely repeats the pattern would buy nothing and cost the ability to ever change it.
 */
class APipelineBudgetReachesItsOwnMapsTest {

    private static final String MINE = "nest.p1.n1.orders";
    private static final String SOMEONE_ELSES = "nest.p2.n1.orders";

    /** What the process was started with, as the wildcard every nest map falls back to. */
    private static final long DEPLOYMENT = 100_000L;

    private HazelcastInstance member;

    /** An undiscoverable single member, with whatever nest map shape the case then adds. */
    private static Config memberConfig() {
        Config config = new Config();
        config.setClusterName("pipeline-budget-" + System.nanoTime());
        config.setProperty("hazelcast.phone.home.enabled", "false");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getAutoDetectionConfig().setEnabled(false);
        join.getMulticastConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(false);
        // One case submits a job to check the budget travels with a submission at all.
        config.getJetConfig().setEnabled(true);
        return config;
    }

    @BeforeEach
    void startMember() {
        member = Hazelcast.newHazelcastInstance(memberConfig().addMapConfig(
                NestSettings.defaults().withEntriesHeldInMemory(DEPLOYMENT).backedStateMaps()));
        NestStateMapStoreFactory.bindTo(member, new HeapKeyedStateStore());
    }

    @AfterEach
    void stopMember() {
        if (member != null) {
            member.shutdown();
        }
    }

    private void apply(long budget) {
        NestMemoryBudget.applyTo(member, Set.of(MINE),
                NestSettings.defaults().withEntriesHeldInMemory(budget));
    }

    /**
     * What a map of this name is actually held to: its own configuration if one was pinned for it, and
     * otherwise the pattern it falls back to. Both are asked because the lookup by pattern does not see a
     * configuration added while the member is running - so reading only that would report every pipeline
     * as being on the deployment's number, whatever had been applied.
     */
    private int sizeOf(String namespace) {
        MapConfig pinned = member.getConfig().getMapConfigs().get(namespace);
        return (pinned != null ? pinned : member.getConfig().findMapConfig(namespace))
                .getEvictionConfig().getSize();
    }

    @Test
    void aPipelineAskingForItsOwnBudgetGetsItOnItsOwnNamespaces() {
        apply(40_000L);

        assertThat(sizeOf(MINE)).isEqualTo(40_000);
    }

    @Test
    void nobodyElsesNamespaceMoves() {
        apply(40_000L);

        // The reason the name has to be exact. Written into the pattern instead, one pipeline's number
        // would quietly become the number every nest on the process is held to.
        assertThat(sizeOf(SOMEONE_ELSES)).isEqualTo((int) DEPLOYMENT);
    }

    @Test
    void applyingTheSameBudgetAgainIsAccepted() {
        apply(40_000L);

        // Every restart of an unchanged pipeline walks this path, so refusing here would mean a pipeline
        // that starts once and never again.
        assertThatCode(() -> apply(40_000L)).doesNotThrowAnyException();
        assertThat(sizeOf(MINE)).isEqualTo(40_000);
    }

    @Test
    void aPipelineAskingForWhatTheProcessAlreadyGivesIsLeftAlone() {
        apply(DEPLOYMENT);

        // Nothing was pinned, so the budget is still free to change on a later run. Pinning a copy of the
        // pattern would cost exactly that, in exchange for a configuration that says nothing new.
        assertThat(member.getConfig().getMapConfigs()).doesNotContainKey(MINE);
        assertThat(sizeOf(MINE)).isEqualTo((int) DEPLOYMENT);
    }

    @Test
    void aProcessRunningItsNestsInMemoryAloneIsNotGivenAColdLayerByABudget() {
        // Regression, and the one that matters most here. A budget is a bound on memory only where
        // something behind the map holds what is past it; applied to a process that has no store, pinning
        // it would declare a cold layer that does not exist and switch eviction on over it - which is not
        // capacity but silent loss. Measured as a nest that assembled nothing at all.
        HazelcastInstance heapOnly = Hazelcast.newHazelcastInstance(memberConfig()
                .addMapConfig(NestSettings.defaults().stateMaps()));
        try {
            NestMemoryBudget.applyTo(heapOnly, Set.of(MINE),
                    NestSettings.defaults().withEntriesHeldInMemory(40_000L));

            assertThat(heapOnly.getConfig().getMapConfigs()).doesNotContainKey(MINE);
            assertThat(heapOnly.getConfig().findMapConfig(MINE).getEvictionConfig().getEvictionPolicy())
                    .describedAs("evicting with nothing to load the entry back from is losing it")
                    .isEqualTo(EvictionPolicy.NONE);
        } finally {
            heapOnly.shutdown();
        }
    }

    @Test
    void submittingAPipelineCarriesItsBudgetOntoItsMaps() {
        // The wiring, not the rule. Everything above calls the applier directly; this is the only case that
        // fails if a submission stops carrying the numbers - and a submission that stopped carrying them
        // would leave every pipeline on the process-wide figure while reading as though it were configured.
        DAG dag = new DAG();
        dag.newVertex("nothing", Processors.noopP());

        new Engine(member).submit("p1", dag, Set.of(MINE),
                NestSettings.defaults().withEntriesHeldInMemory(40_000L));

        assertThat(sizeOf(MINE)).isEqualTo(40_000);
    }

    @Test
    void changingTheBudgetOfAPipelineThisProcessAlreadyRanIsRefusedWithACode() {
        apply(40_000L);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> apply(70_000L));

        assertThat(thrown).isInstanceOf(TapstateException.class);
        assertThat(((TapstateException) thrown).code().code())
                .isEqualTo("nest.memory-budget-changed-while-running");
        // Both numbers, because the author has to be told which one is actually in force - and it is not
        // the one they just wrote.
        assertThat(((TapstateException) thrown).args())
                .containsEntry("configured", 40_000L)
                .containsEntry("requested", 70_000L);
    }

    @Test
    void aRefusedChangeLeavesTheRunningNumberExactlyWhereItWas() {
        apply(40_000L);

        assertThatThrownBy(() -> apply(70_000L)).isInstanceOf(TapstateException.class);

        // The half that makes the refusal worth having. The substrate keeps the earlier number either way;
        // swallowing its exception would leave the pipeline running on 40,000 while its artifact says
        // 70,000, which is "configured, and not in effect" wearing the look of a successful start.
        assertThat(sizeOf(MINE)).isEqualTo(40_000);
    }
}
