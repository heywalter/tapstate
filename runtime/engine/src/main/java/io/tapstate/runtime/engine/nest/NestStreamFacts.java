package io.tapstate.runtime.engine.nest;

import com.hazelcast.core.HazelcastInstance;

import java.io.Serializable;

/**
 * What a nest level is told about the streams feeding it: whether one has finished its initial read, and
 * whose clock its event times come off.
 *
 * <p>Both are used to answer whether a change waiting for a parent may stop waiting, and neither is
 * something the engine can work out for itself. A stream's position names the stream, not the database
 * behind it, so two tables read from one server look exactly like two tables read from two — and the
 * difference decides whether their event times may be compared at all. Whoever wired the pipeline knows
 * it, the same way they know which table an alias means.
 *
 * <p>{@link Serializable} because it is carried to the member that runs each vertex.
 */
public interface NestStreamFacts extends Serializable {

    /**
     * Knows nothing, which is the safe answer rather than the empty one: no stream is ever reported
     * loaded and no two streams are ever reported to share a clock, so nothing is ever established absent
     * and a change is only ever let go of by the backstop. Under-reporting holds changes longer than it
     * needs to; over-reporting throws away rows whose parent was on its way.
     */
    NestStreamFacts UNKNOWN = new NestStreamFacts() {

        @Override
        public boolean loaded(String stream) {
            return false;
        }

        @Override
        public String clockOf(String stream) {
            return null;
        }
    };

    /**
     * Binds these facts to the member whose vertices are about to ask them, returning what to ask from
     * there on. It exists because whether a read has finished is not a fact of the graph but of a store
     * that is only reachable once the vertex is where it will run: only coordinates travel with the job.
     * Facts that need nothing from the member answer themselves, which is why the default is to do so.
     */
    default NestStreamFacts bind(HazelcastInstance member) {
        return this;
    }

    /** Whether the initial read of {@code stream} has produced everything it was going to produce. */
    boolean loaded(String stream);

    /**
     * The clock {@code stream}'s event times are read from — two streams answering the same value may
     * have their event times compared, and null means it is not known, which is never comparable.
     */
    String clockOf(String stream);
}
