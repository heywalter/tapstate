package io.tapstate.core.dsl;

import io.tapstate.core.common.TapstateType;
import io.tapstate.core.model.FieldRule;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.PushElement;
import io.tapstate.core.model.PushFormat;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.ServeBlock;
import io.tapstate.core.model.ServeResource;
import io.tapstate.core.model.Step;
import io.tapstate.core.model.TransformBody;
import io.tapstate.core.model.TransformResource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Judges a batch's row expressions against the columns their sources hold. The offline layers check
 * an expression's syntax, its result type and its envelope names; this checks it against the data it
 * will actually run on, which is knowable only where a connection has been discovered.
 *
 * <p>Three outcomes, and the split matters because each has a different next step for the author:
 * a column whose type cannot survive the operation is refused as unsupported; a column nothing
 * resolved a type for is refused as unknown, because allowing it would mean guessing; and an
 * expression that is simply wrong about a well-resolved column is the ordinary illegal-expression
 * diagnostic, since the offline layer would say the same thing if it had the types.
 *
 * <p>Two things are deliberately not refused. A column absent from the source model stays untyped
 * and passes: for a source whose model comes from sampling, a column the model missed is a normal
 * state rather than an error. A source with no model at all refuses nothing here either — with no
 * columns to judge there is nothing to say, and requiring the discovery to have happened is a
 * separate rule that runs before this one.
 *
 * <p>Scope: a pipeline's expressions are judged against all of the sources it reads. Narrowing that
 * to the sources that can actually reach the node carrying the expression is a further step; until
 * then the merge is the whole pipeline's, which can only ever be more conservative.
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
     * the map omits has not been discovered and contributes no columns.
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

    private void validatePipeline(PipelineResource p) {
        Map<String, TapstateType> columns = columnsOf(p);
        List<Step> transforms = p.transforms();
        if (transforms != null) {
            for (int i = 0; i < transforms.size(); i++) {
                validateStep(transforms.get(i), columns, "transforms[" + i + "]");
            }
        }
        validateServe(p.serve(), columns);
    }

    /**
     * The columns a pipeline's row expressions see: the union over the sources it reads. A column two
     * sources resolve differently is left unresolved on purpose — taking either side would be a guess,
     * and the refusal that follows says so.
     */
    private Map<String, TapstateType> columnsOf(PipelineResource p) {
        Map<String, TapstateType> merged = new LinkedHashMap<>();
        for (String sourceId : p.sources()) {
            Map<String, TapstateType> columns = columnsBySource.get(sourceId);
            if (columns == null) {
                continue;
            }
            columns.forEach((column, type) -> {
                TapstateType seen = merged.putIfAbsent(column, type);
                if (seen != null && seen != type) {
                    merged.put(column, TapstateType.UNKNOWN);
                }
            });
        }
        return merged;
    }

    // ---- where an expression can sit -----------------------------------------------------

    private void validateStep(Step step, Map<String, TapstateType> columns, String path) {
        switch (step) {
            case Step.Inline inline -> validateBody(inline.body(), columns, path);
            // The body lives in the referenced definition, but a definition on its own reads no
            // source: it is judged here, where a pipeline gives it one.
            case Step.Use use -> {
                if (byId.get(use.use()) instanceof TransformResource definition) {
                    validateBody(definition.body(), columns, path + ".use");
                }
            }
        }
    }

    private void validateBody(TransformBody body, Map<String, TapstateType> columns, String path) {
        switch (body) {
            case TransformBody.Filter filter -> judgePredicate(filter.expr(), columns, path + ".expr");
            case TransformBody.MapProjection projection ->
                    validateFieldRules(projection.fields(), columns, path + ".fields");
            default -> {
                // the remaining bodies carry no row expression
            }
        }
    }

    private void validateServe(ServeBlock serve, Map<String, TapstateType> columns) {
        switch (serve) {
            case null -> {
            }
            case ServeBlock.Inline inline -> validatePush(inline.push(), columns, "serve");
            case ServeBlock.Use use -> {
                if (byId.get(use.use()) instanceof ServeResource definition) {
                    validatePush(definition.push(), columns, "serve.use");
                }
            }
        }
    }

    private void validatePush(List<PushElement> push, Map<String, TapstateType> columns, String path) {
        if (push == null) {
            return;
        }
        for (int i = 0; i < push.size(); i++) {
            PushFormat format = push.get(i).format();
            String at = path + ".push[" + i + "].format";
            switch (format) {
                case null -> {
                }
                case PushFormat.Cel cel -> judgeValue(cel.expr(), columns, at);
                case PushFormat.Fields fields -> validateFieldRules(fields.fields(), columns, at);
            }
        }
    }

    private void validateFieldRules(
            Map<String, FieldRule> fields, Map<String, TapstateType> columns, String path) {
        fields.forEach((name, rule) -> {
            if (rule instanceof FieldRule.Computed computed) {
                judgeValue(computed.celExpr(), columns, path + "." + name);
            }
        });
    }

    // ---- the judgment --------------------------------------------------------------------

    private void judgePredicate(String expr, Map<String, TapstateType> columns, String path) {
        judge(expr, columns, path, RowExpressions.typedPredicateError(expr, columns));
    }

    private void judgeValue(String expr, Map<String, TapstateType> columns, String path) {
        judge(expr, columns, path, RowExpressions.typedValueError(expr, columns));
    }

    private void judge(String expr, Map<String, TapstateType> columns, String path, String compileError) {
        Set<String> referenced = RowExpressions.rowColumns(expr);
        if (referenced.isEmpty()) {
            return;     // reads no row data, so no source model bears on it
        }
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
}
