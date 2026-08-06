package io.tapstate.app;

import com.hazelcast.core.HazelcastInstance;
import io.tapstate.runtime.engine.nest.NestStateStats;
import io.tapstate.spi.store.KeyedStateStore;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lets go of the state a pipeline's nests kept, in the two places it is kept: the maps holding it on the
 * member, and the store behind them holding what those maps wrote through. Both, because either alone
 * leaves the state readable - dropping only the store leaves the live map answering from memory, and
 * destroying only the map leaves the next run's first read pulling it all back off disk.
 *
 * <p>A drop is written down before it is done and forgotten only once it is finished, so that a process
 * that dies halfway through one leaves behind a note saying so rather than a pipeline half let go of. The
 * note is what makes this resumable: a stop is driven once, on the transition, and never again - so
 * without it a drop that got two namespaces into five would be the state of the world from then on, and
 * the next run would read the other three as its own.
 *
 * <p>The note carries the names rather than only the fact, because between the stop that wrote it and the
 * start that finishes it the pipeline may have been applied again. Working the names out afresh at that
 * point would name where the <em>next</em> run will write; what has to be dropped is where the last one
 * did.
 *
 * <p>Nothing here enumerates: a namespace is dropped by naming it, which is the one bulk operation the
 * state store offers and the reason it needs no way to list what is in one.
 */
final class NestStateTeardown {

    /** The namespace a pipeline's outstanding drop is noted in, one entry, named for that pipeline. */
    private static final String NAMESPACE_PREFIX = "nest.teardown.";

    /** The one key in that namespace. The note is per pipeline, so it needs no name of its own. */
    private static final String KEY = "namespaces";

    private static final String SEPARATOR = "\n";

    private final HazelcastInstance member;
    private final KeyedStateStore store;

    NestStateTeardown(HazelcastInstance member, KeyedStateStore store) {
        this.member = Objects.requireNonNull(member, "member");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Notes that {@code namespaces} are to be dropped for this pipeline. Writing the note first is what
     * makes the drop that follows resumable; noting nothing to drop writes nothing, so a pipeline with no
     * nest in it leaves no note for a later start to read.
     */
    void note(String pipelineId, Set<String> namespaces) {
        if (namespaces.isEmpty()) {
            return;
        }
        store.save(namespaceOf(pipelineId), KEY,
                String.join(SEPARATOR, new TreeSet<>(namespaces)).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Carries out the drop this pipeline has a note outstanding for, and forgets the note once every
     * namespace in it is gone. Does nothing where there is no note - which is what keeps a start that
     * follows a crash rather than a stop from dropping state the run was still entitled to, and so keeps
     * the shape comparison something to compare against.
     *
     * <p>Safe to call again after it has already run, and safe to call again after it died partway: a
     * namespace already dropped drops as a no-op, and the note stays until the last one has.
     */
    void finishPending(String pipelineId) {
        Set<String> pending = noted(pipelineId);
        if (pending.isEmpty()) {
            return;
        }
        NestStateStats stats = NestStateStats.of(member);
        for (String namespace : pending) {
            member.getMap(namespace).destroy();
            store.dropNamespace(namespace);
            // What was counted about a namespace goes with the namespace. Left behind, the counts would
            // keep describing a run that is over, and a reader cannot tell a count standing still from one
            // that is merely quiet.
            stats.forget(namespace);
        }
        // Last, and only once the namespaces above are gone: the note outliving what it describes costs a
        // repeated drop, where what it describes outliving the note is state nothing will name again.
        store.dropNamespace(namespaceOf(pipelineId));
    }

    /** The namespaces noted as outstanding for this pipeline, empty where none are. */
    private Set<String> noted(String pipelineId) {
        return store.load(namespaceOf(pipelineId), KEY)
                .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                .filter(noted -> !noted.isEmpty())
                .<Set<String>>map(noted -> new LinkedHashSet<>(List.of(noted.split(SEPARATOR, -1))))
                .orElseGet(Set::of);
    }

    private static String namespaceOf(String pipelineId) {
        return NAMESPACE_PREFIX + pipelineId;
    }
}
