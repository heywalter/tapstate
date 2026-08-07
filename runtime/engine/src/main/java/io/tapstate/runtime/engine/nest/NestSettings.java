package io.tapstate.runtime.engine.nest;

import com.hazelcast.config.MapConfig;
import io.tapstate.spi.store.KeyedStateStore;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The one place a nest is told what it is allowed to be: what each of its levels may hold, and what the
 * maps those levels keep their state in are configured to be.
 *
 * <p>Both belong here rather than in two places because they are two ends of one capacity decision. How
 * much a level may keep in memory and how much it may hold at all are separate numbers that only mean
 * anything against each other, and a deployment that sets them from two different places will eventually
 * set them into a combination that cannot work - with nothing able to say so, because neither place can
 * see the other's number.
 *
 * <p><b>Every limit is per namespace.</b> One tree's levels differ in how many keys they hold by orders of
 * magnitude: a level of policies holds one key per policy, the level of claims beneath it one per claim
 * against any of them. A single number covering both is either too large to catch the narrow level growing
 * without bound or too small for the wide one to run at all, so a limit that applies to everything is a
 * limit that catches nothing. A namespace nobody configured takes the default.
 *
 * <p>This is carried to the member that runs each vertex, because that is where a limit is enforced while
 * here is where it is chosen. Anything added to it has to survive that trip: a knob that stayed behind
 * would leave every vertex running unconfigured while the configuration reads as set.
 */
public final class NestSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * How many keys a level may hold when nothing says otherwise.
     *
     * <p>It is a ceiling rather than a sizing: it stands above the widest level anyone has described
     * wanting - a single document drawing on a few hundred thousand keys of one level is an ordinary
     * shape, not an abusive one - and it is there so that a level growing without bound is stopped by
     * something that can say what happened, rather than by the heap running out, which cannot.
     *
     * <p>Provisional. The number a deployment should actually run with follows from measuring what one
     * entry of a level costs it, and until that measurement exists this is the honest placeholder: high
     * enough not to fail work that was always going to fit, low enough to be reached before nothing is
     * left to reach it with.
     */
    public static final long DEFAULT_KEY_LIMIT = 1_000_000L;

    private final Map<String, Long> keyLimits;

    private NestSettings(Map<String, Long> keyLimits) {
        this.keyLimits = Map.copyOf(keyLimits);
    }

    /** Every level on the default limit, which is what a deployment that configured nothing gets. */
    public static NestSettings defaults() {
        return new NestSettings(Map.of());
    }

    /** These settings, with {@code namespace} allowed {@code limit} keys instead of the default. */
    public NestSettings withKeyLimit(String namespace, long limit) {
        Objects.requireNonNull(namespace, "namespace");
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "a level allowed " + limit + " keys could hold nothing at all: " + namespace);
        }
        Map<String, Long> widened = new LinkedHashMap<>(keyLimits);
        widened.put(namespace, limit);
        return new NestSettings(widened);
    }

    /** How many keys {@code namespace} may hold before the job is failed for holding too many. */
    public long keysAllowedIn(String namespace) {
        return keyLimits.getOrDefault(namespace, DEFAULT_KEY_LIMIT);
    }

    /**
     * What every nest state map is configured to be, with nothing behind it: what it holds lives only as
     * long as the member does.
     */
    public MapConfig stateMaps() {
        return NestMaps.stateMaps();
    }

    /** As above, reading through {@code store} for a key that is not in memory and writing through it. */
    public MapConfig stateMaps(KeyedStateStore store) {
        return NestMaps.stateMaps(store);
    }
}
