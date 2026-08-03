package io.tapstate.core.dsl;

import io.tapstate.core.common.TapstateType;
import io.tapstate.core.model.FieldRule;
import io.tapstate.core.model.FromClause;
import io.tapstate.core.model.FromRef;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.PushElement;
import io.tapstate.core.model.PushFormat;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.ServeBlock;
import io.tapstate.core.model.ServeResource;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.Step;
import io.tapstate.core.model.TableRef;
import io.tapstate.core.model.TransformBody;
import io.tapstate.core.model.TransformResource;
import io.tapstate.core.model.ViewBlock;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Judges a batch's row expressions against the columns their sources hold. The offline layers check
 * an expression's syntax, its result type and its envelope names; this checks it against the data it
 * will actually run on, which is knowable only where a connection has been discovered.
 *
 * <p>Reading a row field therefore obliges the author to have discovered the sources that feed it.
 * Without that obligation the check would be dead weight in normal use: an author applies first and
 * discovers afterwards, so there would be no model to judge against and every expression would pass
 * unexamined. An expression that reads no row field carries no obligation at all.
 *
 * <p>The obligation is scoped to the sources that can actually reach the expression, followed back
 * through the pipeline's own wiring rather than taken as everything the pipeline reads. Demanding an
 * unrelated source be discovered would be sending the author to do something with no bearing on the
 * expression being judged. Where the wiring cannot be resolved offline — a regex selecting tables
 * that cannot be enumerated without a connection — every source the pipeline reads is required,
 * since that is the only answer that cannot let an undiscovered one through.
 *
 * <p>Once the sources are known, three outcomes, split because each has a different next step for the
 * author: a column whose type cannot survive the operation is refused as unsupported; a column
 * nothing resolved a type for is refused as unknown, because allowing it would mean guessing; and an
 * expression that is simply wrong about a well-resolved column is the ordinary illegal-expression
 * diagnostic, since the offline layer would say the same thing if it had the types.
 *
 * <p>A column absent from the source model is deliberately not refused: it stays untyped and passes,
 * because for a source whose model comes from sampling, a column the model missed is a normal state
 * rather than an error.
 */
public final class RowExpressionTypeRules {

    private final Map<String, Resource> byId = new LinkedHashMap<>();
    private final Map<String, Map<String, TapstateType>> columnsBySource;

    private RowExpressionTypeRules(
            Collection<Resource> batch, Map<String, Map<String, TapstateType>> columnsBySource) {
        for (Resource r : batch) {
            byId.putIfAbsent(r.id(), r);
        }
        this.columnsBySource = columnsBySource;
    }

    /**
     * Validates every pipeline's row expressions against {@code columnsBySource} — the discovered
     * columns of each source, keyed by the source's id — and throws on the first violation. A source
     * the map omits has not been discovered; one it maps to an empty set has been discovered and holds
     * nothing, which is a different thing and is not refused.
     */
    public static void validate(
            Collection<Resource> batch, Map<String, Map<String, TapstateType>> columnsBySource) {
        RowExpressionTypeRules rules = new RowExpressionTypeRules(batch, columnsBySource);
        for (Resource r : batch) {
            if (r instanceof PipelineResource p) {
                rules.validatePipeline(p);
            }
        }
    }

    /**
     * Folds one stream's column into a view merged across several. A column two streams resolved
     * differently is left unresolved, which is what the refusal downstream reports: taking either
     * side would be a guess. Callers that merge a source's own tables before handing the columns in
     * share this, so the two levels cannot disagree about what a conflict means.
     */
    public static void mergeColumn(Map<String, TapstateType> into, String column, TapstateType type) {
        TapstateType seen = into.putIfAbsent(column, type);
        if (seen != null && seen != type) {
            into.put(column, TapstateType.UNKNOWN);
        }
    }

    private void validatePipeline(PipelineResource p) {
        Wiring wiring = new Wiring(p);
        List<Step> transforms = p.transforms();
        if (transforms != null) {
            for (int i = 0; i < transforms.size(); i++) {
                Step step = transforms.get(i);
                validateStep(step, wiring.sourcesReaching(step.from()), "transforms[" + i + "]");
            }
        }
        validateServe(p.serve(), wiring);
    }

    // ---- where an expression can sit -----------------------------------------------------

    private void validateStep(Step step, Set<String> upstream, String path) {
        switch (step) {
            case Step.Inline inline -> validateBody(inline.body(), upstream, path);
            // The body lives in the referenced definition, but a definition on its own reads no
            // source: it is judged here, where a pipeline gives it one.
            case Step.Use use -> {
                if (byId.get(use.use()) instanceof TransformResource definition) {
                    validateBody(definition.body(), upstream, path + ".use");
                }
            }
        }
    }

    private void validateBody(TransformBody body, Set<String> upstream, String path) {
        switch (body) {
            case TransformBody.Filter filter -> judgePredicate(filter.expr(), upstream, path + ".expr");
            case TransformBody.MapProjection projection ->
                    validateFieldRules(projection.fields(), upstream, path + ".fields");
            default -> {
                // the remaining bodies carry no row expression
            }
        }
    }

    /**
     * A serve block's expressions read what serve is wired to read. Its sink elements each name a
     * source too, but that is the connection being written to or pushed at, never where the rows
     * being shaped came from.
     */
    private void validateServe(ServeBlock serve, Wiring wiring) {
        switch (serve) {
            case null -> {
            }
            case ServeBlock.Inline inline ->
                    validatePush(inline.push(), wiring.sourcesReaching(inline.from()), "serve");
            case ServeBlock.Use use -> {
                if (byId.get(use.use()) instanceof ServeResource definition) {
                    validatePush(definition.push(), wiring.sourcesReaching(use.from()), "serve.use");
                }
            }
        }
    }

    private void validatePush(List<PushElement> push, Set<String> upstream, String path) {
        if (push == null) {
            return;
        }
        for (int i = 0; i < push.size(); i++) {
            PushFormat format = push.get(i).format();
            String at = path + ".push[" + i + "].format";
            switch (format) {
                case null -> {
                }
                case PushFormat.Cel cel -> judgeValue(cel.expr(), upstream, at);
                case PushFormat.Fields fields -> validateFieldRules(fields.fields(), upstream, at);
            }
        }
    }

    private void validateFieldRules(
            Map<String, FieldRule> fields, Set<String> upstream, String path) {
        fields.forEach((name, rule) -> {
            if (rule instanceof FieldRule.Computed computed) {
                judgeValue(computed.celExpr(), upstream, path + "." + name);
            }
        });
    }

    // ---- the judgment --------------------------------------------------------------------

    private void judgePredicate(String expr, Set<String> upstream, String path) {
        Map<String, TapstateType> columns = judgeReach(expr, upstream, path);
        if (columns != null) {
            judgeTypes(expr, columns, path, RowExpressions.typedPredicateError(expr, columns));
        }
    }

    private void judgeValue(String expr, Set<String> upstream, String path) {
        Map<String, TapstateType> columns = judgeReach(expr, upstream, path);
        if (columns != null) {
            judgeTypes(expr, columns, path, RowExpressions.typedValueError(expr, columns));
        }
    }

    /**
     * Enforces the discovery obligation and returns the columns to judge against, or null when the
     * expression reads no row field and so needs no judgment at all.
     */
    private Map<String, TapstateType> judgeReach(String expr, Set<String> upstream, String path) {
        if (RowExpressions.rowColumns(expr).isEmpty()) {
            return null;    // reads no row data, so no source model bears on it
        }
        Map<String, TapstateType> columns = new LinkedHashMap<>();
        for (String sourceId : upstream) {
            Map<String, TapstateType> discovered = columnsBySource.get(sourceId);
            if (discovered == null) {
                // Reported as undiscovered rather than as columns with no resolved type: both would
                // refuse, but only this one tells the author what to do next.
                throw error(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY, path,
                        Map.of("expr", expr, "source", sourceId));
            }
            discovered.forEach((column, type) -> mergeColumn(columns, column, type));
        }
        return columns;
    }

    private void judgeTypes(
            String expr, Map<String, TapstateType> columns, String path, String compileError) {
        Set<String> referenced = RowExpressions.rowColumns(expr);
        // A column nothing resolved is refused however it is used - even a presence test - because
        // what may be done with it is exactly what is not known.
        for (String column : referenced) {
            if (columns.get(column) == TapstateType.UNKNOWN) {
                throw error(DslError.ROW_EXPRESSION_TYPE_UNKNOWN, path,
                        Map.of("expr", expr, "column", column));
            }
        }
        if (compileError == null) {
            return;
        }
        // The expression does not check out. Naming the column whose type has no exact counterpart
        // separates "this column cannot be computed on" from "this expression is wrong", which are
        // different problems with different fixes.
        for (String column : referenced) {
            TapstateType type = columns.get(column);
            if (type != null && RowExpressions.withoutExactCelType(type)) {
                throw error(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED, path,
                        Map.of("expr", expr, "column", column, "type", type.name()));
            }
        }
        throw error(DslError.ILLEGAL_EXPRESSION, path, Map.of("expr", expr, "detail", compileError));
    }

    private static DslException error(DslError code, String path, Map<String, Object> args) {
        return new DslException(code, path, 0, 0, null, args);
    }

    // ---- following the wiring back to the sources ------------------------------------------

    /**
     * One pipeline's wiring, read backwards: which of its sources can reach a given node. A reference
     * is a step or view id (follow it), a source-qualified table (that source), or a bare table name
     * (whichever sources can supply it).
     */
    private final class Wiring {

        private final PipelineResource pipeline;
        private final Set<String> allSources;
        private final Map<String, FromClause> nodeFrom = new LinkedHashMap<>();
        private final Map<String, Set<String>> tablesBySource = new LinkedHashMap<>();
        /** Sources whose table set cannot be enumerated offline, so any table name may come from them. */
        private final Set<String> openSources = new LinkedHashSet<>();

        Wiring(PipelineResource pipeline) {
            this.pipeline = pipeline;
            this.allSources = new LinkedHashSet<>(pipeline.sources());
            if (pipeline.transforms() != null) {
                for (Step step : pipeline.transforms()) {
                    nodeFrom.put(step.id(), step.from());
                }
            }
            indexView(pipeline.view());
            for (String sourceId : allSources) {
                indexSource(sourceId);
            }
        }

        private void indexView(ViewBlock view) {
            switch (view) {
                case null -> {
                }
                case ViewBlock.Inline inline -> nodeFrom.put(inline.id(), new FromClause.Flow(List.of(inline.from())));
                case ViewBlock.Use use -> nodeFrom.put(use.id(), new FromClause.Flow(List.of(use.from())));
            }
        }

        private void indexSource(String sourceId) {
            Set<String> tables = new LinkedHashSet<>();
            if (!(byId.get(sourceId) instanceof SourceResource source) || source.tables() == null) {
                openSources.add(sourceId);      // no table selector: the whole source is in play
                tablesBySource.put(sourceId, tables);
                return;
            }
            for (TableRef table : source.tables()) {
                switch (table) {
                    case TableRef.Literal literal -> tables.add(literal.name());
                    case TableRef.Spec spec -> tables.add(spec.name());
                    case TableRef.Regex regex -> openSources.add(sourceId);
                }
            }
            tablesBySource.put(sourceId, tables);
        }

        Set<String> sourcesReaching(FromClause from) {
            Set<String> reached = new LinkedHashSet<>();
            collect(from, reached, new HashSet<>());
            return reached;
        }

        /** A serve or view block is wired by a single reference rather than a list of them. */
        Set<String> sourcesReaching(FromRef ref) {
            Set<String> reached = new LinkedHashSet<>();
            collect(ref, reached, new HashSet<>());
            return reached;
        }

        private void collect(FromClause from, Set<String> reached, Set<String> visiting) {
            switch (from) {
                case null -> {
                }
                case FromClause.Flow flow -> flow.refs().forEach(ref -> collect(ref, reached, visiting));
                // nest / join: the alias map's values are what the node reads
                case FromClause.Aliases aliases ->
                        aliases.aliases().values().forEach(ref -> collect(ref, reached, visiting));
            }
        }

        private void collect(FromRef ref, Set<String> reached, Set<String> visiting) {
            if (ref instanceof FromRef.Regex) {
                reached.addAll(allSources);     // which tables it selects needs a connection to answer
                return;
            }
            String token = ((FromRef.Literal) ref).ref();
            int dot = token.indexOf('.');
            if (dot >= 0) {
                String prefix = token.substring(0, dot);
                reached.addAll(allSources.contains(prefix) ? Set.of(prefix) : allSources);
                return;
            }
            if (nodeFrom.containsKey(token)) {
                if (visiting.add(token)) {
                    collect(nodeFrom.get(token), reached, visiting);
                }
                return;
            }
            Set<String> supplying = new LinkedHashSet<>(openSources);
            tablesBySource.forEach((sourceId, tables) -> {
                if (tables.contains(token)) {
                    supplying.add(sourceId);
                }
            });
            // A name nothing claims cannot be attributed, so no source may be ruled out.
            reached.addAll(supplying.isEmpty() ? allSources : supplying);
        }

        @Override
        public String toString() {
            return "wiring of " + pipeline.id();
        }
    }
}
