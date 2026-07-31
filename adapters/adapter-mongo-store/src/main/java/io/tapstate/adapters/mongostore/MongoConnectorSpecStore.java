package io.tapstate.adapters.mongostore;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import io.tapstate.spi.store.ConnectorSpecStore;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.Objects;
import java.util.Optional;

/**
 * The MongoDB connector spec store: one document per distinct spec source, keyed by the content hash
 * of its bytes. The bytes are stored opaquely as binary rather than as a parsed document — the point
 * of keeping the source is that it survives byte-exact, which a bson round-trip through a structured
 * document would not guarantee (key order, number representation and any shape bson cannot express
 * would all be at risk).
 *
 * <p>Specs are small documents extracted from an artifact, so they live in a plain collection rather
 * than GridFS, which is where whole artifacts go. No driver type escapes this module (rule R3): the
 * binary wrapper is unwrapped to {@code byte[]} on the way out.
 */
public final class MongoConnectorSpecStore implements ConnectorSpecStore {

    private static final String SPEC = "spec";

    private final MongoCollection<Document> collection;

    public MongoConnectorSpecStore(MongoCollection<Document> collection) {
        this.collection = Objects.requireNonNull(collection, "collection");
    }

    @Override
    public void put(String contentHash, byte[] spec) {
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(spec, "spec");
        // Upsert by the content hash (the document _id). Content-addressed, so a second write under a
        // hash carries the same bytes: replacing in place is the idempotent outcome, not an overwrite
        // of something different.
        StoreIo.run(() -> collection.replaceOne(
                new Document("_id", contentHash),
                new Document("_id", contentHash).append(SPEC, new Binary(spec)),
                new ReplaceOptions().upsert(true)));
    }

    @Override
    public Optional<byte[]> get(String contentHash) {
        Objects.requireNonNull(contentHash, "contentHash");
        Document document = StoreIo.call(() -> collection.find(new Document("_id", contentHash)).first());
        if (document == null) {
            return Optional.empty();
        }
        return Optional.of(document.get(SPEC, Binary.class).getData());
    }
}
