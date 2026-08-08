package io.tapstate.app;

import com.hazelcast.core.HazelcastInstance;
import io.tapstate.runtime.engine.nest.NestStreamFacts;
import io.tapstate.runtime.srs.CaptureRunUnit;
import io.tapstate.spi.store.SrsMeta;
import io.tapstate.spi.store.SrsMetaStore;
import java.util.Map;

/**
 * What the capture side already knows about a nest's streams, carried onto the graph so the levels can
 * ask it: whether a stream's initial read has finished, and whose clock its event times come off.
 *
 * <p>The two are answered from different places for different reasons. Whether a read has finished is
 * recorded per table as each snapshot drains, and is read live: it becomes true while the job runs, which
 * is exactly when it matters. <b>Per table and not per chain</b> - a mining chain is derived from the
 * database's coordinates and deliberately carries no table subset, so two tables of one database share one
 * chain record, and reading the chain as a whole would report every table finished the moment the first
 * one did.
 *
 * <p>Whose clock a stream's times come off is fixed when the pipeline is wired and never changes, so it
 * travels as a plain map. It is the source connection the table is read through: two tables read through
 * one connection are one server and one clock, two connections are two clocks that may sit minutes apart -
 * and comparing times across them would establish a parent absent while it was still on its way.
 *
 * <p>Every unknown reads as "not loaded" and "no clock", which are the answers that keep a change waiting.
 * A missing coordinate here is a wiring mistake, and the direction a wiring mistake must fail in is the
 * one that holds rows rather than the one that discards them.
 */
final class StoreBackedNestStreamFacts implements NestStreamFacts {

    private static final long serialVersionUID = 1L;

    private final Map<String, String> tableByStream;
    private final Map<String, String> chainIdByTable;
    private final Map<String, String> sourceIdByTable;
    private final transient SrsMetaStore meta;

    StoreBackedNestStreamFacts(Map<String, String> tableByStream, Map<String, String> chainIdByTable,
            Map<String, String> sourceIdByTable) {
        this(tableByStream, chainIdByTable, sourceIdByTable, null);
    }

    private StoreBackedNestStreamFacts(Map<String, String> tableByStream, Map<String, String> chainIdByTable,
            Map<String, String> sourceIdByTable, SrsMetaStore meta) {
        this.tableByStream = Map.copyOf(tableByStream);
        this.chainIdByTable = Map.copyOf(chainIdByTable);
        this.sourceIdByTable = Map.copyOf(sourceIdByTable);
        this.meta = meta;
    }

    @Override
    public NestStreamFacts bind(HazelcastInstance member) {
        Object bound = member.getUserContext().get(CaptureRunUnit.SRS_META_USER_CONTEXT_KEY);
        return new StoreBackedNestStreamFacts(tableByStream, chainIdByTable, sourceIdByTable,
                bound instanceof SrsMetaStore store ? store : null);
    }

    @Override
    public boolean loaded(String stream) {
        String table = tableByStream.get(stream);
        String miningChainId = table == null ? null : chainIdByTable.get(table);
        if (meta == null || miningChainId == null) {
            return false;
        }
        return meta.read(miningChainId)
                .map(SrsMeta::snapshotCompletedTables)
                .map(completed -> completed.contains(table))
                .orElse(false);
    }

    @Override
    public String clockOf(String stream) {
        String table = tableByStream.get(stream);
        return table == null ? null : sourceIdByTable.get(table);
    }
}
