package io.tapstate.adapters.mongostore;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBuckets;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.CatalogStore;
import io.tapstate.spi.store.ConnectionTestResultStore;
import io.tapstate.spi.store.ConnectorCatalogStore;
import io.tapstate.spi.store.ConnectorSpecStore;
import io.tapstate.spi.store.ConnectorRegistry;
import io.tapstate.spi.store.DesiredStore;
import io.tapstate.spi.store.KeyedStateStore;
import io.tapstate.spi.store.ObservationStore;
import io.tapstate.spi.store.SchemaStore;
import io.tapstate.spi.store.SrsMetaStore;
import io.tapstate.spi.store.StateStore;
import io.tapstate.spi.store.StorePort;

import java.util.Objects;

/**
 * The MongoDB implementation of the persistence port: it aggregates the ten sub-stores — the artifact
 * truth layer, the epoch-fencing pipeline state store, the plain-upsert pipeline desired-state store,
 * the connection catalog, the discovered source-schema store, the connector distribution registry, the
 * derived connector catalog rows, the latest connection-test result per connection, the plain-upsert
 * per-pipeline observation store, and the SRS meta store (one durable coordination record per mining
 * chain) — each bound to its own collection (or GridFS bucket) on the verified connection's database.
 * Operator state is the one exception and sits in a database of its own on the same client, for the
 * reasons on {@link #NEST_STATE_DATABASE}. This is the store bridge the assembly root wires into the
 * platform under {@code --role=all}; the app sees only the driver-free {@link StorePort}, so no driver
 * type escapes this module (rule R3).
 */
public final class MongoStorePort implements StorePort {

    /** The collection holding the canonical artifact truth layer. */
    public static final String ARTIFACTS = "artifacts";
    /** The collection holding one epoch-fenced checkpoint per pipeline. */
    public static final String PIPELINE_STATE = "pipeline_state";
    /** The collection holding one plain-upsert desired-intent doc per pipeline. */
    public static final String PIPELINE_DESIRED = "pipeline_desired";
    /** The collection holding one plain-upsert observation doc per pipeline. */
    public static final String PIPELINE_OBSERVATION = "pipeline_observation";
    /** The collection holding the registered connection configurations. */
    public static final String CONNECTIONS = "connections";
    /** The collection holding one discovered source model per connection. */
    public static final String SOURCE_SCHEMAS = "source_schemas";
    /** The GridFS bucket holding one registered connector artifact per content hash. */
    public static final String CONNECTOR_ARTIFACTS = "connector_artifacts";
    /** The collection holding one derived catalog row per registered connector. */
    public static final String CONNECTOR_CATALOG = "connector_catalog";

    /** Spec sources kept beside the derived rows, keyed by content hash. */
    public static final String CONNECTOR_SPECS = "connector_specs";
    /** The collection holding the latest connection-test result per connection. */
    public static final String CONNECTION_TEST_RESULTS = "connection_test_results";
    /** The collection holding one SRS coordination record per mining chain. */
    public static final String SRS_META = "srs_meta";
    /** The collection holding one stateful-operator state document per key, per namespace. */
    public static final String OPERATOR_STATE = "operator_state";

    /**
     * The database holding operator state, which is deliberately not the one holding everything else.
     * What an operator configured and what a running job is holding have nothing in common but the
     * connection: one is edited by hand and read rarely, the other is written at event rates and read
     * back by key, and an operation aimed at one of them - a restore, a dump, a drop - should not be able
     * to reach the other by accident.
     *
     * <p>The name is fixed rather than derived or configurable. Two installs pointed at one Mongo are
     * meant to find the same database, and a pipeline of the same id in both of them then shares one
     * state: the price of the fixed name, taken knowingly. Telling them apart later is a matter of what
     * goes in the namespace, not of what the database is called.
     */
    public static final String NEST_STATE_DATABASE = "tapstate_nest";

    private final ArtifactStore artifacts;
    private final StateStore state;
    private final DesiredStore desired;
    private final CatalogStore catalog;
    private final SchemaStore schemas;
    private final ConnectorRegistry connectors;
    private final ConnectorCatalogStore connectorCatalog;
    private final ConnectorSpecStore connectorSpecs;
    private final ConnectionTestResultStore connectionTestResults;
    private final ObservationStore observations;
    private final SrsMetaStore meta;
    private final KeyedStateStore keyedState;

    /**
     * Binds the sub-stores to their own collections on the verified connection's database, bar operator
     * state, which gets a database of its own on the same client ({@link #NEST_STATE_DATABASE}). The
     * connection must have been verified first (its client opened); the sub-stores share that one
     * client and are closed with it when the connection closes.
     */
    public MongoStorePort(MongoConnection connection) {
        Objects.requireNonNull(connection, "connection");
        MongoDatabase database = connection.database();
        this.artifacts = new MongoArtifactStore(connection.client(), database.getCollection(ARTIFACTS));
        this.state = new MongoStateStore(database.getCollection(PIPELINE_STATE));
        this.desired = new MongoDesiredStore(database.getCollection(PIPELINE_DESIRED));
        this.catalog = new MongoCatalogStore(database.getCollection(CONNECTIONS));
        this.schemas = new MongoSchemaStore(database.getCollection(SOURCE_SCHEMAS));
        this.connectors = new MongoConnectorRegistry(GridFSBuckets.create(database, CONNECTOR_ARTIFACTS));
        this.connectorCatalog = new MongoConnectorCatalogStore(database.getCollection(CONNECTOR_CATALOG));
        this.connectorSpecs = new MongoConnectorSpecStore(database.getCollection(CONNECTOR_SPECS));
        this.connectionTestResults =
                new MongoConnectionTestResultStore(database.getCollection(CONNECTION_TEST_RESULTS));
        this.observations = new MongoObservationStore(database.getCollection(PIPELINE_OBSERVATION));
        this.meta = new MongoSrsMetaStore(database.getCollection(SRS_META));
        // Operator state alone sits in its own database on the same client, for the reasons on the
        // constant. Same connection, same credentials, same lifecycle - a different database.
        this.keyedState = new MongoKeyedStateStore(
                connection.client().getDatabase(NEST_STATE_DATABASE).getCollection(OPERATOR_STATE));
    }

    @Override
    public ArtifactStore artifacts() {
        return artifacts;
    }

    @Override
    public StateStore state() {
        return state;
    }

    @Override
    public DesiredStore desired() {
        return desired;
    }

    @Override
    public CatalogStore catalog() {
        return catalog;
    }

    @Override
    public SchemaStore schemas() {
        return schemas;
    }

    @Override
    public ConnectorRegistry connectors() {
        return connectors;
    }

    @Override
    public ConnectorCatalogStore connectorCatalog() {
        return connectorCatalog;
    }

    @Override
    public ConnectorSpecStore connectorSpecs() {
        return connectorSpecs;
    }

    @Override
    public ConnectionTestResultStore connectionTestResults() {
        return connectionTestResults;
    }

    @Override
    public ObservationStore observations() {
        return observations;
    }

    @Override
    public SrsMetaStore meta() {
        return meta;
    }

    @Override
    public KeyedStateStore keyedState() {
        return keyedState;
    }
}
