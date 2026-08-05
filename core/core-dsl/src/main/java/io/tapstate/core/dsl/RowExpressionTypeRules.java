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

import java.util.ArrayList;
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
 * <p>The obligation is scoped to what can actually reach the expression, followed back through the
 * pipeline's own wiring rather than taken as everything the pipeline reads. Demanding an unrelated
 * source be discovered would be sending the author to do something with no bearing on the expression
 * being judged. Where the wiring cannot be resolved offline — a regex selecting tables that cannot be
 * enumerated without a connection — every source the pipeline reads is required, since that is the
 * only answer that cannot let an undiscovered one through.
 *
 * <p><b>A table is judged against its own columns, never a pooled view.</b> The wiring names the
 * tables an expression reads, so each is judged on what it holds; where an expression genuinely reads
 * several — a {@code union}, a regex the connection has to resolve — it is judged against each of
 * them in turn and must hold for every one. Pooling them instead would have to call a column two
 * tables type differently unresolved, which is the ordinary shape of a real database rather than a
 * corner of it, and would refuse expressions that are perfectly correct about the table they read.
 * Judging each in turn refuses the same genuine conflicts while naming the table that caused one.
 *
 * <p>Once the columns are known, three outcomes, split because each has a different next step for the
 * author: a column whose type cannot survive the operation is refused as unsupported; a column
 * nothing resolved a type for is refused as unknown, because allowing it would mean guessing; and an
 * expression that is simply wrong about a well-resolved column is the ordinary illegal-expression
 * diagnostic, since the offline layer would say the same thing if it had the types.
 *
 * <p>A column absent from the table's model is deliberately not refused: it stays untyped and passes,
 * because for a source whose model comes from sampling, a column the model missed is a normal state
 * rather than an error. A table absent from the model reads the same way, for the same reason.
 */
public final class RowExpressionTypeRules {

    private final Map<String, Resource> byId = new LinkedHashMap<>();
    private final Map<String, List<DiscoveredTable>> tablesBySource;

    private RowExpressionTypeRules(
            Collection<Resource> batch, Map<String, List<DiscoveredTable>> tablesBySource) {
        for (Resource r : batch) {
            byId.putIfAbsent(r.id(), r);
        }
        this.tablesBySource = tablesBySource;
    }

    /**
     * Validates every pipeline's row expressions against {@code tablesBySource} — the tables each
     * source was discovered to hold, keyed by the source's id — and throws on the first violation. A
     * source the map omits has not been discovered; one it maps to an empty list has been discovered
     * and holds nothing, which is a different thing and is not refused.
     */
    public static void validate(
            Collection<Resource> batch, Map<String, List<DiscoveredTable>> tablesBySource) {
        RowExpressionTypeRules rules = new RowExpressionTypeRules(batch, tablesBySource);
        for (Resource r : batch) {
            if (r instanceof PipelineResource p) {
                rules.validatePipeline(p);
            }
        }
    }

    private void validatePipeline(PipelineResource p) {
        Wiring wiring = new Wiring(p);
        List<Step> transforms = p.transforms();
        if (transforms != null) {
            for (int i = 0; i < transforms.size(); i++) {
                Step step = transforms.get(i);
                validateStep(step, wiring.reaching(step.from()), "transforms[" + i + "]");
            }
        }
        validateServe(p.serve(), wiring);
    }

    // ---- where an expression can sit -----------------------------------------------------

    private void validateStep(Step step, Set<Upstream> upstream, String path) {
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

    private void validateBody(TransformBody body, Set<Upstream> upstream, String path) {
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
                    validatePush(inline.push(), wiring.reaching(inline.from()), "serve");
            case ServeBlock.Use use -> {
                if (byId.get(use.use()) instanceof ServeResource definition) {
                    validatePush(definition.push(), wiring.reaching(use.from()), "serve.use");
                }
            }
        }
    }

    private void validatePush(List<PushElement> push, Set<Upstream> upstream, String path) {
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
            Map<String, FieldRule> fields, Set<Upstream> upstream, String path) {
        fields.forEach((name, rule) -> {
            if (rule instanceof FieldRule.Computed computed) {
                judgeValue(computed.celExpr(), upstream, path + "." + name);
            }
        });
    }

    // ---- the judgment --------------------------------------------------------------------

    private void judgePredicate(String expr, Set<Upstream> upstream, String path) {
        Set<String> referenced = RowExpressions.rowColumns(expr);
        if (referenced.isEmpty()) {
            return;     // reads no row data, so no source model bears on it
        }
        for (DiscoveredTable table : tablesReaching(expr, upstream, path)) {
            judgeTypes(expr, referenced, table, path,
                    RowExpressions.typedPredicateError(expr, table.columns()));
        }
    }

    private void judgeValue(String expr, Set<Upstream> upstream, String path) {
        Set<String> referenced = RowExpressions.rowColumns(expr);
        if (referenced.isEmpty()) {
            return;     // reads no row data, so no source model bears on it
        }
        for (DiscoveredTable table : tablesReaching(expr, upstream, path)) {
            judgeTypes(expr, referenced, table, path,
                    RowExpressions.typedValueError(expr, table.columns()));
        }
    }

    /**
     * Enforces the discovery obligation and returns every discovered table the expression can run on,
     * each to be judged on its own columns.
     *
     * <p>An upstream naming a table the model does not carry contributes nothing rather than falling
     * back to the source's other tables: the fallback is exactly the pooled view this check exists
     * without, and it would let a table the expression never reads decide the fate of one it does.
     */
    private List<DiscoveredTable> tablesReaching(String expr, Set<Upstream> upstream, String path) {
        List<DiscoveredTable> reached = new ArrayList<>();
        for (Upstream up : upstream) {
            List<DiscoveredTable> discovered = tablesBySource.get(up.source());
            if (discovered == null) {
                // Reported as undiscovered rather than as columns with no resolved type: both would
                // refuse, but only this one tells the author what to do next.
                throw error(DslError.ROW_EXPRESSION_NEEDS_DISCOVERY, path,
                        Map.of("expr", expr, "source", up.source()));
            }
            for (DiscoveredTable table : discovered) {
                if (up.table() == null || up.table().equals(table.name())) {
                    reached.add(table);
                }
            }
        }
        return reached;
    }

    private void judgeTypes(String expr, Set<String> referenced, DiscoveredTable table,
            String path, String compileError) {
        // A column nothing resolved is refused however it is used - even a presence test - because
        // what may be done with it is exactly what is not known.
        for (String column : referenced) {
            if (table.columns().get(column) == TapstateType.UNKNOWN) {
                throw error(DslError.ROW_EXPRESSION_TYPE_UNKNOWN, path,
                        Map.of("expr", expr, "column", column, "table", table.name()));
            }
        }
        if (compileError == null) {
            return;
        }
        // The expression does not check out. Naming the column whose type has no exact counterpart
        // separates "this column cannot be computed on" from "this expression is wrong", which are
        // different problems with different fixes.
        for (String column : referenced) {
            TapstateType type = table.columns().get(column);
            if (type != null && RowExpressions.withoutExactCelType(type)) {
                throw error(DslError.ROW_EXPRESSION_TYPE_UNSUPPORTED, path, Map.of(
                        "expr", expr, "column", column, "type", type.name(), "table", table.name()));
            }
        }
        throw error(DslError.ILLEGAL_EXPRESSION, path, Map.of("expr", expr, "detail", compileError));
    }

    private static DslException error(DslError code, String path, Map<String, Object> args) {
        return new DslException(code, path, 0, 0, null, args);
    }

    // ---- following the wiring back to the tables -------------------------------------------

    /**
     * One thing an expression reads: a source, and the table within it the wiring names. A null table
     * is "whichever tables that source holds" — the answer where the wiring cannot say, which is the
     * only answer that cannot leave a table unexamined.
     */
    private record Upstream(String source, String table) {
    }

    /**
     * One pipeline's wiring, read backwards: what a given node reads. A reference is a step or view id
     * (follow it), a source-qualified table (that table of that source), or a bare table name
     * (that table, of whichever sources can supply it).
     */
    private final class Wiring {

        private final PipelineResource pipeline;
        private final Set<String> allSources;
        private final Map<String, FromClause> nodeFrom = new LinkedHashMap<>();
        private final Map<String, Set<String>> tablesBySelector = new LinkedHashMap<>();
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
                tablesBySelector.put(sourceId, tables);
                return;
            }
            for (TableRef table : source.tables()) {
                switch (table) {
                    case TableRef.Literal literal -> tables.add(literal.name());
                    case TableRef.Spec spec -> tables.add(spec.name());
                    case TableRef.Regex regex -> openSources.add(sourceId);
                }
            }
            tablesBySelector.put(sourceId, tables);
        }

        Set<Upstream> reaching(FromClause from) {
            Set<Upstream> reached = new LinkedHashSet<>();
            collect(from, reached, new HashSet<>());
            return attributed(reached);
        }

        /** A serve or view block is wired by a single reference rather than a list of them. */
        Set<Upstream> reaching(FromRef ref) {
            Set<Upstream> reached = new LinkedHashSet<>();
            collect(ref, reached, new HashSet<>());
            return attributed(reached);
        }

        /**
         * Reaching nothing is not the same as reading nothing. Wiring that closes on itself — a step
         * whose chain of references leads back to a step already being followed — runs out of nodes
         * before it reaches a source, and every node in that chain still reads whatever the pipeline
         * reads. Answering "nothing" there would drop the discovery obligation and leave every column
         * untyped, so the expression would pass unexamined; the whole source set is the same answer an
         * unattributable name gets.
         */
        private Set<Upstream> attributed(Set<Upstream> reached) {
            return reached.isEmpty() ? whole(allSources) : reached;
        }

        private void collect(FromClause from, Set<Upstream> reached, Set<String> visiting) {
            switch (from) {
                case null -> {
                }
                case FromClause.Flow flow -> flow.refs().forEach(ref -> collect(ref, reached, visiting));
                // nest / join: the alias map's values are what the node reads
                case FromClause.Aliases aliases ->
                        aliases.aliases().values().forEach(ref -> collect(ref, reached, visiting));
            }
        }

        private void collect(FromRef ref, Set<Upstream> reached, Set<String> visiting) {
            if (ref instanceof FromRef.Regex) {
                // which tables it selects needs a connection to answer, so every table is in play
                reached.addAll(whole(allSources));
                return;
            }
            String token = ((FromRef.Literal) ref).ref();
            int dot = token.indexOf('.');
            if (dot >= 0) {
                String prefix = token.substring(0, dot);
                String table = token.substring(dot + 1);
                if (allSources.contains(prefix)) {
                    reached.add(new Upstream(prefix, table));
                } else {
                    reached.addAll(whole(allSources));
                }
                return;
            }
            if (nodeFrom.containsKey(token)) {
                if (visiting.add(token)) {
                    collect(nodeFrom.get(token), reached, visiting);
                }
                return;
            }
            // A bare token is a table name, so the table is known even where the source is not: it is
            // read from whichever sources can supply that name.
            Set<String> supplying = new LinkedHashSet<>(openSources);
            tablesBySelector.forEach((sourceId, tables) -> {
                if (tables.contains(token)) {
                    supplying.add(sourceId);
                }
            });
            // A name nothing claims cannot be attributed, so no source may be ruled out.
            for (String sourceId : supplying.isEmpty() ? allSources : supplying) {
                reached.add(new Upstream(sourceId, token));
            }
        }

        /** Every table of each named source — the answer where the wiring names no single one. */
        private Set<Upstream> whole(Set<String> sources) {
            Set<Upstream> all = new LinkedHashSet<>();
            for (String sourceId : sources) {
                all.add(new Upstream(sourceId, null));
            }
            return all;
        }

        @Override
        public String toString() {
            return "wiring of " + pipeline.id();
        }
    }
}
