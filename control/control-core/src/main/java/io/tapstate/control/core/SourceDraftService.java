package io.tapstate.control.core;

import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.dsl.CapabilityRules;
import io.tapstate.core.dsl.Workspace;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.canonical.CanonicalWriter;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Validates a Source view and renders canonical YAML without touching artifact storage. */
public final class SourceDraftService {

    private final Supplier<TapstateCatalog> catalog;
    private final CanonicalWriter writer = new CanonicalWriter();

    public SourceDraftService(TapstateCatalog catalog) {
        this(() -> Objects.requireNonNull(catalog, "catalog"));
    }

    public SourceDraftService(Supplier<TapstateCatalog> catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public SourceDraftResult draft(SourceDraft draft) {
        Objects.requireNonNull(draft, "draft");
        TapstateCatalog liveCatalog = catalog.get();
        SourceResource source = new SourceRepresentation(liveCatalog).toDraftModel(draft);
        Workspace.of(List.of(source), liveCatalog);
        CapabilityRules.validateOnline(source, liveCatalog);
        return new SourceDraftResult(writer.write(source));
    }
}
