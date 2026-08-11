package io.tapstate.app;

import com.hazelcast.core.HazelcastInstance;
import io.tapstate.core.common.TapstateException;
import io.tapstate.runtime.engine.nest.NestDeadLetter;
import io.tapstate.runtime.engine.nest.NestError;
import io.tapstate.runtime.engine.nest.NestVertex;
import io.tapstate.runtime.engine.nest.ReleasedChild;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says out loud that a nest is discarding changes, and passes them on to wherever they are kept.
 *
 * <p>The saying and the keeping are separate on purpose. Keeping is what lets the rows be looked at
 * afterwards and belongs with the state they came from; saying is what puts them in front of somebody while
 * it is happening, and needs a logging framework — which the ring that does the keeping deliberately has
 * none of. This is the outer half, assembled here because here is where both halves are reachable.
 *
 * <p>Only the first of each decade is logged. A parent deleted with many children below it produces one of
 * these per child, so logging every one turns a single ordinary event into a flood that buries the rest of
 * the log — while the count on the run's statistics still says exactly how many there were.
 *
 * <p>The count restarts with each binding, which is once per vertex per member per job. It decides only how
 * often a line is written; the number a reader acts on is the one the run publishes.
 */
final class LoggingNestDeadLetter implements NestDeadLetter {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(LoggingNestDeadLetter.class);

    private final NestDeadLetter next;
    private final AtomicLong count = new AtomicLong();

    LoggingNestDeadLetter(NestDeadLetter next) {
        this.next = Objects.requireNonNull(next, "next");
    }

    /**
     * Binds the half behind this to the member, and wraps whatever that produced. Binding the inner half
     * without re-wrapping would be a channel that keeps changes and never mentions them; not binding at all
     * would be one that mentions them and keeps nothing.
     */
    @Override
    public NestDeadLetter bind(HazelcastInstance member) {
        return new LoggingNestDeadLetter(next.bind(member));
    }

    @Override
    public void unassemblable(NestVertex from, ReleasedChild released) {
        // Kept first. If keeping the row fails, that failure must reach the caller rather than being
        // preceded by a log line saying the row was dealt with.
        next.unassemblable(from, released);
        long seen = count.incrementAndGet();
        if (!isFirstOfItsDecade(seen)) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chain", chainOf(released));
        args.put("bucket", from.name());
        args.put("order", released.child().order());
        args.put("heldFor", released.heldFor());
        // Built rather than thrown: the code names what happened and the pipeline goes on running, which
        // is what this severity means. Throwing it would stop a job over a reference pointing nowhere.
        TapstateException coded = new TapstateException(NestError.PARENT_ABSENT, args, null);
        LOG.warn("{} ({} handed over so far on this member)", coded.getMessage(), seen);
    }

    /** The streams the change covered, which is what a reader needs to know where to go looking. */
    private static String chainOf(ReleasedChild released) {
        return released.child().positions().isEmpty()
                ? "none"
                : String.join(",", released.child().positions().keySet());
    }

    /** How many have been handed over since this was bound — what a caller asserts on rather than the log. */
    long count() {
        return count.get();
    }

    private static boolean isFirstOfItsDecade(long seen) {
        for (long decade = 1; decade <= seen; decade *= 10) {
            if (decade == seen) {
                return true;
            }
        }
        return false;
    }
}
