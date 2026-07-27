package io.tapstate.adapters.mongostore;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SourceField;
import io.tapstate.spi.store.SourceIndex;
import io.tapstate.spi.store.SourceModel;
import io.tapstate.spi.store.SourceTable;
import io.tapstate.testsupport.RequiresDocker;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Witnesses the discovered-schema store against a real Mongo replica-set: a saved discovery envelope
 * (connector id, discovery time, and the source model's tables, fields including one with no resolved
 * type, primary key, and both a unique and a non-unique index) reads back equal through the real bson
 * encode / decode, an absent connection reads back empty, and a re-discovery of the same connection
 * replaces the stored envelope in place (last write wins) rather than accumulating documents. Where
 * Docker is absent this aborts on a developer machine and fails in CI, where a skip would be a green
 * build that ran nothing.
 */
@RequiresDocker
class MongoSchemaStoreIT {

    private static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

    @Container
    private static final MongoDBContainer REPLICA_SET = new MongoDBContainer(MONGO_IMAGE);

    private static SourceModel ordersModel() {
        return new SourceModel(List.of(
                new SourceTable(
                        "orders",
                        List.of(new SourceField("id", "bigint"), new SourceField("note", null)),
                        List.of("id"),
                        List.of(
                                new SourceIndex("pk_orders", List.of("id"), true),
                                new SourceIndex("by_note", List.of("note"), false))),
                new SourceTable("customers", List.of(new SourceField("email", "varchar")), List.of("email"), List.of())));
    }

    @Test
    void savedEnvelopeReadsBackEqualThroughRealBson() {
        withStore((store, collection) -> {
            DiscoveredSourceModel envelope =
                    new DiscoveredSourceModel("orders-db", "mysql", 1783998000000L, ordersModel());
            store.save(envelope);

            Optional<DiscoveredSourceModel> read = store.get("orders-db");
            assertThat(read).contains(envelope);
        });
    }

    @Test
    void getReturnsEmptyForAnAbsentConnection() {
        withStore((store, collection) -> assertThat(store.get("never-discovered")).isEmpty());
    }

    @Test
    void reDiscoveryReplacesTheStoredEnvelopeInPlace() {
        withStore((store, collection) -> {
            store.save(new DiscoveredSourceModel("orders-db", "mysql", 1L, ordersModel()));
            DiscoveredSourceModel rediscovered = new DiscoveredSourceModel(
                    "orders-db",
                    "mysql",
                    2L,
                    new SourceModel(List.of(
                            new SourceTable("orders", List.of(new SourceField("id", "bigint")), List.of("id"), List.of()))));
            store.save(rediscovered);

            assertThat(collection.countDocuments()).isEqualTo(1);
            assertThat(store.get("orders-db")).contains(rediscovered);
        });
    }

    private interface StoreTest {
        void run(MongoSchemaStore store, MongoCollection<Document> collection);
    }

    /** Runs a test body against a fresh schema store over a clean collection on the real replica-set. */
    private static void withStore(StoreTest test) {
        try (MongoClient client = MongoClients.create(REPLICA_SET.getReplicaSetUrl())) {
            MongoCollection<Document> collection = client.getDatabase("tapstate").getCollection("source_schemas");
            collection.drop();
            test.run(new MongoSchemaStore(collection), collection);
        }
    }
}
