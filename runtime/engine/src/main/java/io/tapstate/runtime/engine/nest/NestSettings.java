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
 * <p><b>How much a level holds is not a number anyone sets.</b> A level holds one key per row of its
 * source and a nest one document per root of its own, neither of which the tree bounds, and both are
 * absorbed by the layer behind the memory rather than refused: growing past what is held in memory is what
 * the store is there for. What a deployment sets is how much stays in memory; what is beyond it is on
 * disk, and how much of that there may be is a question for the disk.
 *
 * <p>The one exception is per document, and is here for a reason the others are not: a document is
 * rendered whole, so a document that has absorbed more than fits is not something a store behind the map
 * can help with. That one is per namespace, since one tree's documents differ in width by orders of
 * magnitude and a single number covering all of them catches nothing. A namespace nobody configured takes
 * the default.
 *
 * <p>This is carried to the member that runs each vertex, because that is where a limit is enforced while
 * here is where it is chosen. Anything added to it has to survive that trip: a knob that stayed behind
 * would leave every vertex running unconfigured while the configuration reads as set.
 */
public final class NestSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * How many elements one document may hold when nothing says otherwise.
     *
     * <p>Per document rather than per namespace, and the only count that fails a run rather than being
     * absorbed by the layer behind it: a document is assembled whole, so however wide it has grown is what
     * has to be in memory at once, and no eviction reaches inside one.
     *
     * <p>Provisional. What a deployment should run with follows from measuring what one element of its
     * documents costs, and until that measurement exists this is the honest placeholder: high enough not to
     * fail work that was always going to fit, low enough to be reached before nothing is left to reach it
     * with.
     */
    public static final long DEFAULT_ELEMENT_LIMIT = 100_000L;

    private final Map<String, Long> elementLimits;

    private NestSettings(Map<String, Long> elementLimits) {
        this.elementLimits = Map.copyOf(elementLimits);
    }

    /** Every level on the default limit, which is what a deployment that configured nothing gets. */
    public static NestSettings defaults() {
        return new NestSettings(Map.of());
    }

    /** These settings, with each document of the nest at {@code namespace} allowed {@code limit} elements. */
    public NestSettings withElementLimit(String namespace, long limit) {
        return new NestSettings(with(elementLimits, namespace, limit, "elements"));
    }

    /** How many elements one document of {@code namespace} may hold before the job is failed. */
    public long elementsAllowedIn(String namespace) {
        return elementLimits.getOrDefault(namespace, DEFAULT_ELEMENT_LIMIT);
    }

    private static Map<String, Long> with(Map<String, Long> limits, String namespace, long limit,
            String held) {
        Objects.requireNonNull(namespace, "namespace");
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "allowed " + limit + " " + held + ", it could hold nothing at all: " + namespace);
        }
        Map<String, Long> widened = new LinkedHashMap<>(limits);
        widened.put(namespace, limit);
        return widened;
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
