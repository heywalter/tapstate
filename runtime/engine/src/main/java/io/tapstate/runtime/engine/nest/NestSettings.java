package io.tapstate.runtime.engine.nest;

import com.hazelcast.config.MapConfig;
import io.tapstate.core.common.TapstateException;

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
 * <p>Two exceptions are here for a reason the rest are not: what they bound lives inside a single entry,
 * which a budget counting entries never reaches. A document is rendered whole, so one that has absorbed
 * more than fits is not something a store behind the map can help with; and what one key holds for a parent
 * that has not arrived travels to the store inside the entry it lives in, so any budget is met however long
 * that queue has grown. Both are per namespace, since one tree's levels differ by orders of magnitude and a
 * single number covering all of them catches nothing. A namespace nobody configured takes the default.
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
     * <p>Per document rather than per namespace, and one of the two counts that fail a run rather than being
     * absorbed by the layer behind it: a document is assembled whole, so however wide it has grown is what
     * has to be in memory at once, and no eviction reaches inside one.
     *
     * <p>Measured rather than guessed. One element of a wide row costs about 7 KiB in memory, so a document
     * at this limit is around 140 MiB - and it is rendered into a second copy of itself to go out, so that
     * is paid twice at the moment it is refused. Far past anything healthy, and still inside what a member
     * with a few gigabytes can refuse while it is running, which is the whole requirement: a limit only
     * reached after the heap is gone never fires at all, and what happens in its place is an exhausted
     * member reporting something else, from somewhere else, having taken the rest of the pipeline with it.
     */
    public static final long DEFAULT_ELEMENT_LIMIT = 20_000L;

    /**
     * How many entries each namespace keeps in memory when nothing says otherwise.
     *
     * <p>Low enough that a namespace holding it leaves the process something to run in, and high enough to
     * be worth having: what is past it is not lost but read back from the layer behind the map, which costs
     * a seek per event rather than an error.
     *
     * <p>Measured rather than guessed, against a document that has absorbed a hundred elements - about
     * 240 KiB of memory each - so a namespace at this budget holds a little under a gigabyte, and a cascade
     * pays that once per level.
     *
     * <p><b>Deliberately the conservative end of what was measured</b>, because the two ways of being wrong
     * are not equally visible. Set too low this is slow and says so: the share of reads that fall through to
     * the layer behind the map is published and can be alarmed on. Set too high it is a member that runs out
     * of memory with every metric still green, which nothing announces until it is over. Between an error
     * that reports itself and one that does not, this is sized against the one that does not.
     */
    public static final long DEFAULT_ENTRIES_HELD_IN_MEMORY = 4_000L;

    /**
     * How many changes one key may hold waiting for something that has not arrived, when nothing says
     * otherwise. The second count that fails a run rather than being absorbed: what waits lives inside one
     * entry, so the layer behind the map takes the entry whole and the waiting with it - a bound on how many
     * entries are held in memory never reaches inside one, however low it is set.
     *
     * <p>Per key rather than per namespace, and by change rather than by row: one row rewritten while its
     * parent is missing is one row and as many held changes as it was written.
     *
     * <p>Measured rather than guessed: a held change of a wide row costs about 7.5 KiB, so one key at this
     * limit holds around 70 MiB. Set below what the memory alone would allow, because what it really has to
     * clear is a load arriving in the worst order - every child of one row before that row - and a fan-out
     * past ten thousand under a single parent is rarer than one key stuck holding that much is alarming.
     *
     * <p><b>What this does not bound is many keys each holding a little.</b> A foreign key pointing at a row
     * that never arrives is held for as long as the job runs, and a million such keys holding one change
     * apiece pass this limit without touching it. What bounds those is the budget above, which moves them to
     * the layer behind the map; what they go on holding back is the frontier, which is reported separately
     * and is the thing actually worth watching for this shape.
     */
    public static final long DEFAULT_PENDING_LIMIT = 10_000L;

    /**
     * How many records of deleted elements one document may keep when nothing says otherwise. The third count
     * that fails a run rather than being absorbed by the layer behind the map, and the only one whose size is
     * not a property of the data: a record of a deletion is kept until a restart could no longer replay the
     * deletion, so how many pile up follows how far behind the durable frontier is.
     *
     * <p>Reached only by a document whose deletions cannot be dropped, since what may be dropped is dropped
     * before this is weighed. So it is set for the case it really reports - a frontier that has stopped
     * moving - rather than for how much deleting a workload does: high enough that heavy deletion against a
     * healthy frontier never approaches it, low enough that a frontier stuck for hours is answered by a
     * failed job rather than by a member running out of memory.
     *
     * <p><b>Measured and left where it was, unlike the three around it.</b> A record of a deletion keeps no
     * row - about 620 bytes whatever the row it replaced was carrying, which is the design holding rather
     * than a coincidence - so a document at this limit is around 60 MiB, already inside what a member can
     * refuse while running. The same measurement that moved the other three found nothing to move here.
     */
    public static final long DEFAULT_TOMBSTONE_LIMIT = 100_000L;

    /**
     * How long after a document goes out before it may go out again, when nothing says otherwise.
     *
     * <p>The one number here that is not a capacity. It rations sends rather than bounding memory, and it is
     * here rather than beside the map configuration because it belongs to the same decision as the rest: how
     * much a level may keep and how often it may spend it are set against each other, and a deployment that
     * shrank the memory budget without knowing the send rate would be setting one half of a pair.
     *
     * <p>Short enough that a document is never visibly stale - a reader polling anything sees a version at
     * most this old - and long enough to be worth having: a root taking a hundred changes a second is
     * rewritten twenty times instead of a hundred, and one taking ten thousand is rewritten twenty times as
     * well. That second case is what this is for. A root changing less often than this pays nothing at all,
     * because the first change of a quiet window goes out on the spot.
     */
    public static final long DEFAULT_SEND_WINDOW_MILLIS = 50L;

    private final Map<String, Long> elementLimits;
    private final Map<String, Long> pendingLimits;
    private final Map<String, Long> tombstoneLimits;
    private final Map<String, Long> sendWindows;
    private final long entriesHeldInMemory;

    private NestSettings(Map<String, Long> elementLimits, Map<String, Long> pendingLimits,
            Map<String, Long> tombstoneLimits, Map<String, Long> sendWindows, long entriesHeldInMemory) {
        this.elementLimits = Map.copyOf(elementLimits);
        this.pendingLimits = Map.copyOf(pendingLimits);
        this.tombstoneLimits = Map.copyOf(tombstoneLimits);
        this.sendWindows = Map.copyOf(sendWindows);
        this.entriesHeldInMemory = entriesHeldInMemory;
    }

    /** Every level on the default limit, which is what a deployment that configured nothing gets. */
    public static NestSettings defaults() {
        return new NestSettings(Map.of(), Map.of(), Map.of(), Map.of(), DEFAULT_ENTRIES_HELD_IN_MEMORY);
    }

    /** These settings, with each document of the nest at {@code namespace} allowed {@code limit} elements. */
    public NestSettings withElementLimit(String namespace, long limit) {
        return new NestSettings(with(elementLimits, namespace, limit, "elements"), pendingLimits,
                tombstoneLimits, sendWindows, entriesHeldInMemory);
    }

    /**
     * These settings, with each key of {@code namespace} allowed to hold {@code limit} changes for something
     * that has not arrived.
     */
    public NestSettings withPendingLimit(String namespace, long limit) {
        return new NestSettings(elementLimits, with(pendingLimits, namespace, limit, "pending changes"),
                tombstoneLimits, sendWindows, entriesHeldInMemory);
    }

    /**
     * These settings, with each document of {@code namespace} allowed to keep {@code limit} records of
     * deleted elements that cannot be dropped yet.
     */
    public NestSettings withTombstoneLimit(String namespace, long limit) {
        return new NestSettings(elementLimits, pendingLimits,
                with(tombstoneLimits, namespace, limit, "records of deletion"), sendWindows,
                entriesHeldInMemory);
    }

    /**
     * These settings, with each document of {@code namespace} going out at most once every {@code millis}.
     *
     * <p>Zero is allowed and means no window: every change that produces a document sends one. It is the
     * setting to reach for when a reader is being timed rather than fed, and it is the one that costs the
     * most - the send rate of a hot root becomes its change rate, which is the multiplication this exists
     * to bound.
     */
    public NestSettings withSendWindow(String namespace, long millis) {
        Objects.requireNonNull(namespace, "namespace");
        if (millis < 0) {
            throw new IllegalArgumentException(
                    "a window cannot be shorter than no window: " + millis + " for " + namespace);
        }
        Map<String, Long> widened = new LinkedHashMap<>(sendWindows);
        widened.put(namespace, millis);
        return new NestSettings(elementLimits, pendingLimits, tombstoneLimits, widened, entriesHeldInMemory);
    }

    /**
     * These settings, with each namespace keeping {@code entries} of its state in memory and the rest on
     * the layer behind it.
     *
     * <p>One number for every namespace rather than one each. What it bounds is the memory of the process
     * they all share, and a deployment that had to name each level to bound the whole would be bounding it
     * by however many it remembered to name.
     *
     * <p>Refused below the partition count, where it stops meaning what it says: the substrate spends this
     * budget per partition rather than per map, so a number smaller than the partitions has already been
     * rounded up to one entry each by the time it is enforced - measured at 82 resident against a
     * configured 10 - and a deployment reading its own configuration back would not learn that.
     */
    public NestSettings withEntriesHeldInMemory(long entries) {
        if (entries < NestMaps.SMALLEST_MEANINGFUL_MEMORY_BUDGET) {
            throw new TapstateException(NestError.MEMORY_BUDGET_BELOW_PARTITION_COUNT,
                    Map.of("entries", entries,
                            "partitions", (long) NestMaps.SMALLEST_MEANINGFUL_MEMORY_BUDGET), null);
        }
        return new NestSettings(elementLimits, pendingLimits, tombstoneLimits, sendWindows, entries);
    }

    /** How many entries each namespace keeps in memory before the rest is left to the layer behind it. */
    public long entriesHeldInMemory() {
        return entriesHeldInMemory;
    }

    /** How many elements one document of {@code namespace} may hold before the job is failed. */
    public long elementsAllowedIn(String namespace) {
        return elementLimits.getOrDefault(namespace, DEFAULT_ELEMENT_LIMIT);
    }

    /**
     * How many changes one key of {@code namespace} may hold for something that has not arrived, before the
     * job is failed.
     */
    public long pendingAllowedIn(String namespace) {
        return pendingLimits.getOrDefault(namespace, DEFAULT_PENDING_LIMIT);
    }

    /**
     * How many records of deleted elements one document of {@code namespace} may keep, once everything that
     * could be dropped has been, before the job is failed.
     */
    public long tombstonesAllowedIn(String namespace) {
        return tombstoneLimits.getOrDefault(namespace, DEFAULT_TOMBSTONE_LIMIT);
    }

    /**
     * How long after a document of {@code namespace} goes out before the next version of it may, with zero
     * meaning every version goes out as it is assembled.
     */
    public long sendWindowIn(String namespace) {
        return sendWindows.getOrDefault(namespace, DEFAULT_SEND_WINDOW_MILLIS);
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

    /**
     * As above, reading through the member's nest state store for a key that is not in memory and writing
     * through it, and holding only {@link #entriesHeldInMemory()} of each namespace in memory at a time.
     *
     * <p>The store is named in the configuration and resolved on the member that runs the map, so the
     * member has to have been told its store - see {@code NestStateMapStoreFactory.bindTo} - before any
     * nest map on it is used.
     */
    public MapConfig backedStateMaps() {
        return NestMaps.backedStateMaps(entriesHeldInMemory);
    }

    /**
     * As above, for the one namespace {@code namespace} rather than for all of them, so that this budget
     * applies to that namespace alone. This is the form a pipeline's own budget takes: it names a
     * namespace, and namespaces are not known until there is a pipeline, which is after the member is
     * already running.
     */
    public MapConfig backedStateMaps(String namespace) {
        return NestMaps.backedStateMaps(Objects.requireNonNull(namespace, "namespace"), entriesHeldInMemory);
    }
}
