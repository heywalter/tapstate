package io.tapstate.adapters.mongostore;

import io.tapstate.core.common.TapstateException;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
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
