package io.tapstate.runtime.engine.nest;

import com.hazelcast.config.MapConfig;
import com.hazelcast.core.HazelcastInstance;
import io.tapstate.core.common.TapstateException;

import java.util.Map;
import java.util.Set;

/**
 * Puts a pipeline's memory budget onto the maps that pipeline's levels keep their state in.
 *
 * <p>Each namespace is named exactly. The pattern the member started with covers every nest on the
 * process, so a number written into it would be every pipeline's number - which is the thing having a
 * per-pipeline budget at all is meant to stop.
 *
 * <p><b>What a map holds is settled the first time its name is configured and stays settled for as long
 * as the process runs.</b> Measured, and it decides the shape of everything below: adding the same
 * configuration again is accepted, adding a different one is refused by the substrate, and the number
 * already in place is the one that remains in force. So the three cases are not symmetrical, and treating
 * them as though they were is how a pipeline comes to run on a budget its own artifact contradicts.
 */
public final class NestMemoryBudget {

    private NestMemoryBudget() {
    }

    /**
     * Holds each of {@code namespaces} to what {@code settings} asks for, on {@code member}.
     *
     * <p>A namespace already configured to hold that number is left alone, which is what every restart of
     * an unchanged pipeline does. One asking for a different number is refused with a code rather than
     * with the substrate's own exception: the substrate keeps the earlier number, so the honest report is
     * which number is in force and what it would take to change it, not that a configuration clashed.
     *
     * <p>A pipeline asking for exactly what the process already gives is not pinned at all. An exact
     * configuration repeating the pattern would say nothing new and would cost the one thing the pattern
     * still has - that it can be started with a different number tomorrow.
     */
    public static void applyTo(HazelcastInstance member, Set<String> namespaces, NestSettings settings) {
        for (String namespace : namespaces) {
            MapConfig wanted = settings.backedStateMaps(namespace);
            int asked = wanted.getEvictionConfig().getSize();
            MapConfig pinned = member.getConfig().getMapConfigs().get(namespace);
            if (pinned != null) {
                refuseIfDifferent(namespace, pinned.getEvictionConfig().getSize(), asked);
                continue;
            }
            MapConfig fallsBackTo = member.getConfig().findMapConfig(namespace);
            // A budget is a bound on memory only where something stands behind the map to hold what is
            // past it. On a process running its nests in memory alone there is nothing behind them, and
            // pinning this configuration would do two wrong things at once: declare a cold layer that is
            // not there, and turn eviction on over it - which is not a capacity bound but silent loss.
            // Such a process holds everything it has, which is what a run with no store already promised.
            if (!fallsBackTo.getMapStoreConfig().isEnabled()) {
                continue;
            }
            if (fallsBackTo.getEvictionConfig().getSize() == asked) {
                continue;
            }
            member.getConfig().addMapConfig(wanted);
        }
    }

    private static void refuseIfDifferent(String namespace, int configured, int asked) {
        if (configured == asked) {
            return;
        }
        throw new TapstateException(NestError.MEMORY_BUDGET_CHANGED_WHILE_RUNNING,
                Map.of("namespace", namespace,
                        "configured", (long) configured,
                        "requested", (long) asked), null);
    }
}
