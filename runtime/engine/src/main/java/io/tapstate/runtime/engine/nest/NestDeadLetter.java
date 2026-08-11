package io.tapstate.runtime.engine.nest;

import com.hazelcast.core.HazelcastInstance;

import java.io.Serializable;

/**
 * Where changes that can never reach a document go.
 *
 * <p>Three things end up here and they are not the same news. A child whose parent row is known deleted,
 * and a child still waiting when that parent is deleted, are past saving: no later event will give them a
 * document to sit in. A child whose parent was established absent — the stream it would have come on
 * finished loading and has been read past the child's own time — is the ordinary shape of a reference
 * pointing nowhere. A child the backstop gave up on is neither: nothing was established, the wait was
 * simply ended so the frontier could move again, and the parent may have been about to arrive. The verdict
 * is what tells them apart, and running them together into one undifferentiated channel leaves whoever
 * reads it unable to see which of "this is how the data is" and "a row may just have been lost" they are
 * looking at.
 *
 * <p>They must still go somewhere. Dropping them loses data that no assertion about a document can ever
 * see, because those rows were never going to appear in one - and holding them instead would pin the
 * durable frontier for events that will never be emitted.
 *
 * <p>Whoever builds the vertex decides where they go; the vertex only insists that they go.
 */
@FunctionalInterface
public interface NestDeadLetter extends Serializable {

    /** Hands over a change that can never reach a document, from the level that was holding it. */
    void unassemblable(NestVertex from, ReleasedChild released);

    /**
     * Binds this to the member whose vertices it is about to serve, returning what to hand changes to from
     * there on. It exists for the reason the stores have the same hook: this is serialized when the job is
     * submitted, while somewhere durable to put a change is reached through a handle that is not — only
     * coordinates travel, and the live handle is picked up once the vertex is where it will run. Somewhere
     * that needs nothing from the member answers itself, which is why the default is to do so.
     */
    default NestDeadLetter bind(HazelcastInstance member) {
        return this;
    }
}
