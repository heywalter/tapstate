package io.tapstate.adapters.mongostore;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.MongoCollection;
import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.IoError;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The spec-source codec is the mapping core of the spec store: source bytes are stored opaquely as
 * binary under their content hash and handed back byte-exact, never re-serialized. These witness the
 * mapping deterministically, without a Mongo server — a byte-exact round-trip and a coded diagnostic
 * when a stored document cannot be read. A real Mongo round-trip is exercised by
 * {@code MongoConnectorSpecStoreIT} (skipped where Docker is absent).
 */
class MongoConnectorSpecStoreTest {

    private static final String HASH = "sha256:2f1a";

    @Test
    void storesTheSourceBytesOpaquelyUnderTheContentHash() {
        // Key order, number form and any shape bson cannot express have to survive: the reason the source
        // is kept at all is that the derived row already lost them. Binary is what keeps them.
        byte[] source = "{\"properties\":{\"id\":\"mysql\"},\"zz\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8);

        Document document = MongoConnectorSpecStore.toDocument(HASH, source);

        assertThat(document.getString("_id")).isEqualTo(HASH);
        assertThat(document.get("spec")).isInstanceOf(Binary.class);
        assertThat(MongoConnectorSpecStore.toSpec(document, HASH)).isEqualTo(source);
    }

    @Test
    void aDocumentWithNoSpecFieldIsDocumentUnreadable() {
        // The read face states "no source stored" when nothing is filed under the hash. A document that
        // IS filed but carries nothing readable is a different thing — corruption — and dereferencing it
        // blind would throw an uncoded failure out of this module and answer a bodyless 500 upstream.
        Document empty = new Document("_id", HASH);

        Throwable thrown = catchThrowable(() -> MongoConnectorSpecStore.toSpec(empty, HASH));

        assertThat(thrown).isInstanceOf(TapstateException.class);
        TapstateException coded = (TapstateException) thrown;
        assertThat(coded.code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
        assertThat(coded.args()).containsEntry("id", HASH);
    }

    @Test
    void losingTheInsertRaceOnTheContentHashIsNotAFailure() {
        // Two writers can both find nothing under a hash and both insert; one loses on the key. Because
        // the key IS the content, the winner wrote exactly the bytes this call carries - the store holds
        // what was asked for, so raising here would fail a write that succeeded. It matters because the
        // register path no longer swallows store failures: this one would fail a whole registration
        // whose artifact bytes are already committed.
        MongoCollection<Document> losesTheRace = collectionWhoseWritesFailWith(
                new MongoWriteException(
                        new WriteError(11000, "E11000 duplicate key error", new BsonDocument()),
                        new ServerAddress(),
                        Set.of()));

        assertThatCode(() -> new MongoConnectorSpecStore(losesTheRace)
                .put(HASH, "{\"properties\":{\"id\":\"mysql\"}}".getBytes(StandardCharsets.UTF_8)))
                .doesNotThrowAnyException();
    }

    @Test
    void anyOtherWriteFailureIsStillCodedAndStillRaised() {
        // The swallow is for the one error that means "already exactly this". Every other driver failure
        // is a real failure and must leave this module coded, not silently succeed.
        MongoCollection<Document> refuses = collectionWhoseWritesFailWith(
                new MongoWriteException(
                        new WriteError(13, "not authorized", new BsonDocument()),
                        new ServerAddress(),
                        Set.of()));

        Throwable thrown = catchThrowable(() -> new MongoConnectorSpecStore(refuses)
                .put(HASH, "{\"properties\":{\"id\":\"mysql\"}}".getBytes(StandardCharsets.UTF_8)));

        assertThat(thrown).isInstanceOf(TapstateException.class);
        assertThat(((TapstateException) thrown).code()).isEqualTo(IoError.STORE_UNAVAILABLE);
    }

    /** A collection whose every call raises {@code failure} — enough to drive the write path's branches. */
    @SuppressWarnings("unchecked")
    private static MongoCollection<Document> collectionWhoseWritesFailWith(RuntimeException failure) {
        return (MongoCollection<Document>) Proxy.newProxyInstance(
                MongoCollection.class.getClassLoader(),
                new Class<?>[] {MongoCollection.class},
                (proxy, method, args) -> {
                    throw failure;
                });
    }

    @Test
    void aDocumentWhoseSpecFieldIsNotBinaryIsDocumentUnreadable() {
        // The same for a field that exists but holds the wrong thing — a rename, or a migration that
        // wrote the source as text. A blind cast would crash uncoded; the field's type is not the
        // caller's fault to see as a stack trace.
        Document mistyped = new Document("_id", HASH).append("spec", "{\"properties\":{}}");

        Throwable thrown = catchThrowable(() -> MongoConnectorSpecStore.toSpec(mistyped, HASH));

        assertThat(thrown).isInstanceOf(TapstateException.class);
        assertThat(((TapstateException) thrown).code()).isEqualTo(IoError.DOCUMENT_UNREADABLE);
    }
}
