package io.tapstate.control.core;

import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.common.TapstateException;
import io.tapstate.core.dsl.CapabilityRules;
import io.tapstate.core.dsl.Workspace;
import io.tapstate.core.model.SourceMode;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.Srs;
import io.tapstate.core.model.SrsSchemaEvolution;
import io.tapstate.core.model.TableRef;
import io.tapstate.core.model.canonical.CanonicalWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        SourceResource source = toModel(draft);
        Workspace.of(List.of(source), liveCatalog);
        CapabilityRules.validateOnline(source, liveCatalog);
        return new SourceDraftResult(writer.write(source));
    }

    private static SourceResource toModel(SourceDraft draft) {
        Map<String, Object> config = new java.util.LinkedHashMap<>(draft.config());
        draft.clearSecrets().forEach(config::remove);
        return new SourceResource(
                draft.id(),
                draft.metadata(),
                draft.connector(),
                config,
                sourceMode(draft.mode()),
                tableModels(draft.tables()),
                draft.options(),
                srsModel(draft.srs()),
                draft.experimental());
    }

    private static SourceMode sourceMode(String value) {
        if (value == null) {
            return null;
        }
        for (SourceMode mode : SourceMode.values()) {
            if (mode.yaml().equals(value)) {
                return mode;
            }
        }
        throw malformed("unknown Source mode: " + value);
    }

    private static List<TableRef> tableModels(List<SourceTableDraft> tables) {
        if (tables == null) {
            return null;
        }
        List<TableRef> result = new ArrayList<>(tables.size());
        for (SourceTableDraft table : tables) {
            if (table == null) {
                throw malformed("tables cannot contain null entries");
            }
            result.add(tableModel(table));
        }
        return List.copyOf(result);
    }

    private static TableRef tableModel(SourceTableDraft table) {
        return switch (Objects.toString(table.type(), "")) {
            case "literal" -> {
                require(table.name() != null
                                && table.pattern() == null
                                && table.filter() == null
                                && table.pk() == null
                                && table.options() == null,
                        "literal tables accept only name");
                yield TableRef.literal(table.name());
            }
            case "regex" -> {
                require(table.pattern() != null
                                && table.name() == null
                                && table.filter() == null
                                && table.pk() == null
                                && table.options() == null,
                        "regex tables accept only pattern");
                yield TableRef.regex(table.pattern());
            }
            case "spec" -> {
                require(table.name() != null && table.pattern() == null,
                        "spec tables require name and cannot carry pattern");
                require(table.pk() == null || table.pk().stream().noneMatch(Objects::isNull),
                        "spec table pk cannot contain null entries");
                yield TableRef.spec(table.name(), table.filter(), table.pk(), table.options());
            }
            default -> throw malformed("unknown table type: " + table.type());
        };
    }

    private static Srs srsModel(SourceDraft.SourceSrs value) {
        if (value == null) {
            return null;
        }
        return new Srs(
                value.key(),
                value.retention(),
                schemaEvolution(value.schemaEvolution()),
                value.queryable(),
                value.enabled());
    }

    private static SrsSchemaEvolution schemaEvolution(String value) {
        if (value == null) {
            return null;
        }
        for (SrsSchemaEvolution candidate : SrsSchemaEvolution.values()) {
            if (candidate.yaml().equals(value)) {
                return candidate;
            }
        }
        throw malformed("unknown srs.schemaEvolution: " + value);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) {
            throw malformed(reason);
        }
    }

    private static TapstateException malformed(String reason) {
        return new TapstateException(ControlError.MALFORMED_REQUEST, Map.of("reason", reason), null);
    }
}
