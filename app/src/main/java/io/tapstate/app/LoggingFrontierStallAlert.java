package io.tapstate.app;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.lifecycle.FrontierStall;
import io.tapstate.runtime.engine.EngineError;
import io.tapstate.runtime.scheduler.FrontierStallAlert;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says that a chain's durable position has stopped moving for too long, and later that it is moving again.
 *
 * <p>This is the whole of the signal. A pipeline with a pinned chain goes on running with a zero error
 * count, short queues and its usual throughput — it may be busily processing every other chain it has —
 * while the source keeps its log from the pinned position onwards. Nothing fails when the log finally
 * rotates past it either: the pipeline simply cannot resume any more, and neither can anything else
 * mining the same chain. Nobody is going to notice this by watching, so it is said.
 *
 * <p>The line names which of the two pins it is, because the two are worked on from opposite ends and the
 * duration alone would send someone looking without saying where. Where the distance is not reported at
 * all the line says so rather than showing a zero: no distance means the chain never advanced, and zero
 * means a frontier sitting on its bound, which is a chain that has been advancing.
 *
 * <p>The crossing is coded and the recovery is not, because there is no severity that means "this is
 * fine" and dressing an all-clear as a warning would be worse than not coding it. The recovery names the
 * code anyway: whoever went looking did so by the code, and a withdrawal their search does not return is a
 * withdrawal they never read.
 *
 * <p>Numbers are formatted in a fixed locale rather than the process default. Which locale a server boots
 * under is nobody's deliberate choice, and a decimal comma is not cosmetic in a line that gets grepped.
 */
final class LoggingFrontierStallAlert implements FrontierStallAlert {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingFrontierStallAlert.class);

    /** What a number that is not there is called, so a missing one does not read as a truncated line. */
    private static final String UNKNOWN = "unknown";

    @Override
    public void crossed(String pipelineId, FrontierStall stall) {
        // Built rather than thrown: the code names what is happening and the pipeline goes on running,
        // which is what this severity means. Throwing it would stop a job over a position that is merely
        // stuck, which is the one thing guaranteed to make the retention window matter.
        TapstateException coded = new TapstateException(EngineError.FRONTIER_PINNED, argsOf(stall), null);
        LOG.warn("{} (pipeline {})", coded.getMessage(), pipelineId);
    }

    @Override
    public void cleared(String pipelineId, FrontierStall stall) {
        LOG.info("{} no longer applies to chain {} of pipeline {}: its durable position advanced {} ago",
                EngineError.FRONTIER_PINNED.code(), stall.chain(), pipelineId, minutes(stall));
    }

    private static Map<String, Object> argsOf(FrontierStall stall) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chain", stall.chain());
        args.put("minutes", minutes(stall));
        args.put("gap", gap(stall));
        args.put("cause", cause(stall));
        return args;
    }

    /**
     * Which of the two pins this is, in the words an operator acts on. Not a code of its own: it is one
     * finding with two shapes, and splitting it into two codes would have whoever greps for one of them
     * miss the other half of the same condition.
     */
    private static String cause(FrontierStall stall) {
        return stall.starvedOfPositions()
                ? "starved of positions to advance to"
                : "held back by changes still pending upstream";
    }

    /** How far the bound has run on, or that there is no such reading rather than a zero one. */
    private static String gap(FrontierStall stall) {
        OptionalLong gap = stall.gap();
        return gap.isEmpty() ? UNKNOWN : Long.toString(gap.getAsLong());
    }

    /** How long the position has been pinned, in whole minutes - the scale the threshold is set on. */
    private static String minutes(FrontierStall stall) {
        return String.format(Locale.ROOT, "%.0f", stall.pinnedMillis() / 60_000d);
    }
}
