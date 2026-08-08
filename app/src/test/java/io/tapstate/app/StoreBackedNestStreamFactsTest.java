package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hazelcast.core.HazelcastInstance;
import io.tapstate.runtime.engine.nest.NestStreamFacts;
import io.tapstate.runtime.srs.CaptureRunUnit;
import io.tapstate.spi.store.SrsMetaStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.Test;

/**
 * What a nest level is told about the streams beneath it, read off what the capture side actually wrote.
 * Two questions with two different sources: whether a stream's initial read has finished comes from the
 * per-table completion the snapshot records, and whose clock its event times come off comes from which
 * source connection the table was read through.
 */
class StoreBackedNestStreamFactsTest {

    /** Policies and claims are two tables of one database, so one mining chain and one clock. */
    private static final Map<String, String> TABLE_BY_STREAM = Map.of("policy", "policies", "claim", "claims");
    private static final Map<String, String> CHAIN_BY_TABLE = Map.of("policies", "mc-1", "claims", "mc-1");
    private static final Map<String, String> SOURCE_BY_TABLE = Map.of("policies", "src-a", "claims", "src-a");

    private static NestStreamFacts factsOn(SrsMetaStore store) {
        return new StoreBackedNestStreamFacts(TABLE_BY_STREAM, CHAIN_BY_TABLE, SOURCE_BY_TABLE)
                .bind(memberWith(store));
    }

    @Test
    void aStreamIsLoadedOnceItsOwnTableIsMarkedCompleteOnItsChain() {
        InMemorySrsMetaStore store = new InMemorySrsMetaStore();
        store.create("mc-1", null);
        store.markSnapshotComplete("mc-1", "policies");

        assertThat(factsOn(store).loaded("policy")).isTrue();
    }

    @Test
    void aStreamWhoseTableHasNotFinishedIsNotLoadedJustBecauseItsChainSiblingHas() {
        InMemorySrsMetaStore store = new InMemorySrsMetaStore();
        store.create("mc-1", null);
        store.markSnapshotComplete("mc-1", "policies");

        // The signal is per table and not per chain, which is the whole reason it exists: a mining chain is
        // derived from the database's coordinates and deliberately carries no table subset, so two tables of
        // one database share one chain record. Reading the chain as a whole would report claims loaded the
        // moment policies finished, and every claim waiting for a policy would be given up on at once.
        assertThat(factsOn(store).loaded("claim")).isFalse();
    }

    @Test
    void nothingIsLoadedWhileNoStoreIsBoundOnTheMember() {
        HazelcastInstance member = mock(HazelcastInstance.class);
        when(member.getUserContext()).thenReturn(new ConcurrentHashMap<>());

        NestStreamFacts facts = new StoreBackedNestStreamFacts(
                TABLE_BY_STREAM, CHAIN_BY_TABLE, SOURCE_BY_TABLE).bind(member);

        // Not knowing reads as "not loaded", never as "loaded": the direction that keeps a change waiting
        // is the one that cannot throw a row away.
        assertThat(facts.loaded("policy")).isFalse();
    }

    @Test
    void twoTablesReadThroughOneSourceShareAClockAndTwoSourcesDoNot() {
        InMemorySrsMetaStore store = new InMemorySrsMetaStore();
        NestStreamFacts sameSource = factsOn(store);

        assertThat(sameSource.clockOf("policy")).isEqualTo(sameSource.clockOf("claim"));

        NestStreamFacts twoSources = new StoreBackedNestStreamFacts(TABLE_BY_STREAM, CHAIN_BY_TABLE,
                Map.of("policies", "src-a", "claims", "src-b")).bind(memberWith(store));

        assertThat(twoSources.clockOf("policy")).isNotEqualTo(twoSources.clockOf("claim"));
    }

    @Test
    void aStreamNobodyDeclaredHasNoClockAndIsNeverLoaded() {
        InMemorySrsMetaStore store = new InMemorySrsMetaStore();
        store.create("mc-1", null);
        store.markSnapshotComplete("mc-1", "policies");
        NestStreamFacts facts = factsOn(store);

        // A null clock is never equal to another, so an unknown stream is never comparable with anything -
        // which is what keeps a wrong or missing wiring from establishing an absence it cannot see.
        assertThat(facts.clockOf("nobody")).isNull();
        assertThat(facts.loaded("nobody")).isFalse();
    }

    private static HazelcastInstance memberWith(SrsMetaStore store) {
        ConcurrentMap<String, Object> context = new ConcurrentHashMap<>();
        context.put(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY, store);
        HazelcastInstance member = mock(HazelcastInstance.class);
        when(member.getUserContext()).thenReturn(context);
        return member;
    }
}
