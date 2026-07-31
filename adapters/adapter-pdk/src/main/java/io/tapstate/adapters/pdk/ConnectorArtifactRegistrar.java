package io.tapstate.adapters.pdk;

import io.tapstate.core.catalog.CatalogEntryAssembler;
import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.NormalizedSpec;
import io.tapstate.core.catalog.SpecNormalizer;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.common.JsonReader;
import io.tapstate.spi.store.CapabilityDeriver;
import io.tapstate.spi.store.ConnectorCapabilities;
import io.tapstate.spi.store.ConnectorCatalogStore;
import io.tapstate.spi.store.ConnectorRegistrar;
import io.tapstate.spi.store.ConnectorRegistration;
import io.tapstate.spi.store.ConnectorRegistry;
import io.tapstate.spi.store.ConnectorSpecStore;
import io.tapstate.spi.store.ContentHash;
import io.tapstate.spi.store.RegistrationOutcome;
import io.tapstate.spi.store.RegistrationSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registers a connector artifact on disk into the distribution store by what the artifact itself
 * declares: self-scan supplies the entry class and PDK API version, the spec's {@code properties.id}
 * supplies the connector id, and the artifact bytes go through the store's content-hash idempotent
 * register-if-absent. The startup seed sweep and the explicit runtime register operation are both
 * this one call, differing only in the {@link RegistrationSource} they record.
 *
 * <p>An artifact whose spec cannot yield an id — not valid JSON, or no {@code properties.id} — is
 * refused with a coded connector-domain exception: registration would have no identity to file the
 * artifact under. A different artifact under an already-registered id is likewise refused — a single
 * active artifact is kept per id. A raw I/O failure reading the artifact is not a connector defect and
 * surfaces as an unchecked I/O exception.
 *
 * <p>After the bytes are registered, the connector's normalized catalog row is derived (its declared
 * capabilities merged with its spec) and stored, so the online catalog view can list the connector and
 * validate against it. Derivation is skipped when the bytes were already registered and a row already
 * exists; a re-register whose row is missing backfills it, so a crash between the two never leaves a
 * registered connector without a row.
 */
public final class ConnectorArtifactRegistrar implements ConnectorRegistrar {

    /**
     * The connectors this release officially supports, in the order a refusal message names them. A
     * list rather than a set so that order — and therefore the message — is fixed; the membership test
     * over two entries costs nothing. This is the default accepted set and the only one any shipped
     * artifact uses; a test pins its exact contents, because a silent addition here would be a support
     * promise nobody made.
     */
    static final List<String> OFFICIAL_CONNECTOR_IDS = List.of("mysql", "mongodb");

    private final ConnectorRegistry registry;
    private final ConnectorIntrospector introspector;
    private final CapabilityDeriver capabilityDeriver;
    private final ConnectorCatalogStore catalogStore;
    private final ConnectorSpecStore specStore;
    private final List<String> acceptedConnectorIds;

    /** Registers with the release's own accepted set — what every shipped artifact uses. */
    public ConnectorArtifactRegistrar(ConnectorRegistry registry, ConnectorIntrospector introspector,
                                      CapabilityDeriver capabilityDeriver, ConnectorCatalogStore catalogStore,
                                      ConnectorSpecStore specStore) {
        this(registry, introspector, capabilityDeriver, catalogStore, specStore, List.of());
    }

    /**
     * Registers with further connector ids accepted beyond the official set. Empty in every shipped
     * artifact: this exists for a deployment that stands up its own server and supplies its own
     * connector — the product's end-to-end harness is the case it was built for. Naming ids here does
     * not make them supported; it only stops the register path refusing them.
     */
    public ConnectorArtifactRegistrar(ConnectorRegistry registry, ConnectorIntrospector introspector,
                                      CapabilityDeriver capabilityDeriver, ConnectorCatalogStore catalogStore,
                                      ConnectorSpecStore specStore, List<String> alsoAccept) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.introspector = Objects.requireNonNull(introspector, "introspector");
        this.capabilityDeriver = Objects.requireNonNull(capabilityDeriver, "capabilityDeriver");
        this.catalogStore = Objects.requireNonNull(catalogStore, "catalogStore");
        this.specStore = Objects.requireNonNull(specStore, "specStore");
        Objects.requireNonNull(alsoAccept, "alsoAccept");
        List<String> accepted = new ArrayList<>(OFFICIAL_CONNECTOR_IDS);
        accepted.addAll(alsoAccept);
        this.acceptedConnectorIds = List.copyOf(accepted);
    }

    /** Registers the artifact at {@code artifact} if its content hash is not already registered. */
    public RegistrationOutcome register(Path artifact, RegistrationSource source) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(source, "source");
        IntrospectedConnector introspected = introspector.introspect(List.of(artifact));
        Object specTree = parseSpecTree(introspected, artifact);
        String connectorId = declaredId(specTree, introspected, artifact);
        rejectUnofficialConnector(connectorId, source);
        byte[] bytes = bytesOf(artifact);
        rejectConflictingArtifact(connectorId, ContentHash.of(bytes));
        RegistrationOutcome outcome = registry.register(connectorId, introspected.pdkApiVersion(), source, bytes);
        try {
            persistCatalogRow(connectorId, introspected, specTree, outcome);
        } catch (TapstateException containedDerivationFailure) {
            // The catalog row is best-effort: the artifact is registered whether or not its capabilities can
            // be derived. A connector that introspects (entry class + spec id) but will not load in this
            // deployment (an incompatible PDK level, or a construct-time failure) fails derivation; that coded
            // failure is contained so the register never reports failure over already-stored bytes and wedges
            // the id. The row stays absent — so the connector is out of the online catalog — until a
            // re-register derives it (the missing-row backfill re-runs derivation). A programmer bug (a bare
            // RuntimeException, not a coded one) still crashes rather than being swallowed.
        }
        return outcome;
    }

    /**
     * Registers the artifact carried by {@code artifact} bytes — the entry the runtime register
     * operation uses, since a remote caller hands over bytes rather than a server path. The bytes are
     * staged to a temporary jar because introspection needs a real file, then registered exactly as the
     * on-disk seed path is; the staged jar is removed afterwards.
     */
    @Override
    public RegistrationOutcome register(byte[] artifact, RegistrationSource source) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(source, "source");
        Path staged = stage(artifact);
        try {
            return register(staged, source);
        } finally {
            deleteStaged(staged);
        }
    }

    /**
     * Refuses a runtime register naming a connector outside the officially supported set, before any
     * byte is stored — so a refused register leaves no half-registration wedging the id.
     *
     * <p>Only the runtime register path is gated. A seed sweep registers what the release itself
     * ships from its own seed directory: gating that would let a packaging change fail silently at
     * startup (the sweep contains a per-jar failure and carries on) while doing nothing about the
     * surface this guard exists for, which is an artifact arriving over the wire from a client.
     */
    private void rejectUnofficialConnector(String connectorId, RegistrationSource source) {
        if (source != RegistrationSource.REGISTER || acceptedConnectorIds.contains(connectorId)) {
            return;
        }
        // The message names what would actually be accepted here, not the release default: a caller
        // debugging a refusal needs the set this server is applying, whatever it was started with.
        throw new TapstateException(ConnectorError.NOT_OFFICIAL,
                Map.of("connector", connectorId,
                        "official", String.join(", ", acceptedConnectorIds)),
                null);
    }

    /** Refuses a different artifact under a connector id that already has one — no silent overwrite. */
    private void rejectConflictingArtifact(String connectorId, String incomingHash) {
        for (ConnectorRegistration existing : registry.list()) {
            if (existing.connectorId().equals(connectorId) && !existing.contentHash().equals(incomingHash)) {
                throw new TapstateException(ConnectorError.REGISTRATION_CONFLICT,
                        Map.of("connector", connectorId,
                                "existing", existing.contentHash(),
                                "incoming", incomingHash),
                        null);
            }
        }
    }

    private static Path stage(byte[] artifact) {
        Path staged;
        try {
            staged = Files.createTempFile("tapstate-connector-", ".jar");
        } catch (IOException e) {
            throw new UncheckedIOException("staging connector artifact for registration", e);
        }
        try {
            Files.write(staged, artifact);
        } catch (IOException e) {
            // The temp file exists but the write failed: delete it so a write failure does not leak it (the
            // caller's cleanup only runs once register(byte[]) holds the returned path).
            deleteStaged(staged);
            throw new UncheckedIOException("staging connector artifact for registration", e);
        }
        return staged;
    }

    private static void deleteStaged(Path staged) {
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            // Best-effort: a leaked staging jar is OS-reclaimable and must not mask the register outcome.
        }
    }

    /** Parses the spec text to its object tree, refusing coded when it is not valid JSON. */
    private static Object parseSpecTree(IntrospectedConnector introspected, Path artifact) {
        try {
            return JsonReader.parse(introspected.spec());
        } catch (IllegalArgumentException e) {
            throw specInvalid(introspected, artifact, "the spec is not valid JSON", e);
        }
    }

    /** The connector id the spec declares under {@code properties.id} — the registration identity. */
    private static String declaredId(Object specTree, IntrospectedConnector introspected, Path artifact) {
        if (specTree instanceof Map<?, ?> root
                && root.get("properties") instanceof Map<?, ?> properties
                && properties.get("id") instanceof String id
                && !id.isBlank()) {
            return id;
        }
        throw specInvalid(introspected, artifact, "the spec does not declare properties.id as a non-blank string", null);
    }

    /**
     * Derives the connector's normalized catalog row and stores it, so the online catalog view can see
     * the registered connector. Skipped when the bytes were already registered and a row already exists;
     * otherwise the row is (re)derived, which backfills a row missing after a prior partial register.
     */
    private void persistCatalogRow(
            String connectorId, IntrospectedConnector introspected, Object specTree, RegistrationOutcome outcome) {
        byte[] specSource = introspected.spec().getBytes(StandardCharsets.UTF_8);
        String specHash = ContentHash.of(specSource);
        if (!outcome.newlyRegistered()
                && catalogStore.get(connectorId).isPresent()
                && specStore.get(specHash).isPresent()) {
            // Both artifacts of this registration are already stored, so there is nothing to redo. The
            // spec source is part of the condition, not just the row: a connector registered before the
            // source was kept has a row but no source, and that gap is what a re-register backfills.
            return;
        }
        // Stored before derivation, which may fail for a connector that will not load here: the source
        // is read straight off the artifact and does not depend on the connector loading, so there is no
        // reason to lose it when the capability row cannot be built.
        specStore.put(specHash, specSource);
        ConnectorCapabilities capabilities = capabilityDeriver.derive(connectorId);
        NormalizedSpec normalized = SpecNormalizer.normalize(asSpecObject(specTree));
        ConnectorCatalogEntry row = CatalogEntryAssembler.assemble(
                normalized, capabilities.capabilityIds(), null, introspected.specPath(), specHash);
        catalogStore.upsert(row);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSpecObject(Object specTree) {
        // declaredId has already confirmed the spec parses to a JSON object carrying properties.id.
        return (Map<String, Object>) specTree;
    }

    private static TapstateException specInvalid(
            IntrospectedConnector introspected, Path artifact, String detail, Throwable cause) {
        return new TapstateException(ConnectorError.SPEC_INVALID,
                Map.of("artifact", artifact.getFileName().toString(),
                        "spec", introspected.specPath(),
                        "detail", detail),
                cause);
    }

    private static byte[] bytesOf(Path artifact) {
        try {
            return Files.readAllBytes(artifact);
        } catch (IOException e) {
            throw new UncheckedIOException("reading connector artifact " + artifact, e);
        }
    }
}
