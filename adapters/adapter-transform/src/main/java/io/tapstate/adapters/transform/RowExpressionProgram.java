package io.tapstate.adapters.transform;

import com.google.protobuf.ByteString;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import io.tapstate.core.dsl.RowExpressions;
import io.tapstate.core.event.Envelope;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * One compiled row expression, ready to evaluate against an event. The compiler environment is the
 * validate layer's — the AST comes from {@link RowExpressions}, so what type-checks at validate
 * time is exactly what runs here. The program is built once (member-side, from the serializable
 * expression text) and evaluated per event; a compiled program is immutable and reused.
 *
 * <p>Evaluation binds the envelope as the expression root the same way the compiler declared it:
 * {@code op} as its wire symbol, {@code ts} / {@code src} as scalars, and {@code before} / {@code
 * after} / {@code schema} as maps (an absent map binds empty, so a present-field test is well
 * defined rather than a null dereference).
 */
final class RowExpressionProgram {

    // A standard runtime with no custom function bindings. It is immutable and shared: building the
    // per-expression program is the only per-instance cost.
    private static final CelRuntime RUNTIME = CelRuntimeFactory.standardCelRuntimeBuilder().build();

    private final CelRuntime.Program program;
    // The source expression, kept so an evaluation failure can name the expression that failed.
    private final String expr;

    private RowExpressionProgram(CelRuntime.Program program, String expr) {
        this.program = program;
        this.expr = expr;
    }

    /** Compiles a predicate (bool) expression into an evaluable program. */
    static RowExpressionProgram predicate(String expr) {
        return of(RowExpressions.predicateAst(expr), expr);
    }

    /** Compiles a computed-value expression (any type) into an evaluable program. */
    static RowExpressionProgram value(String expr) {
        return of(RowExpressions.valueAst(expr), expr);
    }

    private static RowExpressionProgram of(CelAbstractSyntaxTree ast, String expr) {
        try {
            return new RowExpressionProgram(RUNTIME.createProgram(ast), expr);
        } catch (CelEvaluationException e) {
            // A checked AST builds into a program; a failure here is an invariant violation, not a
            // user condition.
            throw new IllegalStateException("row expression program could not be built", e);
        }
    }

    /** Evaluates the expression against one event, returning the raw CEL result. */
    Object eval(Envelope event) {
        Map<String, Object> vars = new HashMap<>(8);
        vars.put("op", event.op().symbol());
        vars.put("ts", event.ts());
        vars.put("src", event.src());
        vars.put("before", bind(event.before()));
        vars.put("after", bind(event.after()));
        vars.put("schema", bind(event.schema()));
        try {
            return unbound(program.eval(vars));
        } catch (CelEvaluationException e) {
            // A row-level evaluation failure (a missing field, a type clash on a dyn value, a function
            // that type-checks but is unbound at runtime) is a user-diagnosable condition: surface it
            // as a coded diagnostic naming the expression, not a bare crash that fails the job opaquely.
            throw TransformErrors.expressionFailed(expr, e);
        }
    }

    // A row image as the expression sees it. An absent map binds empty, so a present-field test is
    // well defined rather than a null dereference.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> bind(Map<String, Object> row) {
        return row == null ? Map.of() : (Map<String, Object>) bound(row);
    }

    // A connector hands over whatever type its driver uses, which is not always one the expression
    // language has: an int column may arrive in any integral box, a real one as a float, a binary one
    // as a byte array. Each becomes the type the language does have, so an operation apply already
    // judged safe can actually run instead of failing once the pipeline is live. Every conversion
    // here widens or re-wraps and none of them rounds, so no value changes on the way in.
    //
    // Only a value the target type actually holds is converted. A wider integer is left as it came:
    // the expression then refuses it by name, where narrowing it would hand the expression a
    // different number and report the result as a success.
    //
    // Nested values are converted too, since a document's own fields and an array's elements are as
    // reachable from an expression as a top-level column. A container whose contents all pass through
    // unchanged is returned as it is, so the common row costs no copy.
    private static Object bound(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value instanceof BigInteger big && big.bitLength() < Long.SIZE) {
            return big.longValue();
        }
        if (value instanceof byte[] bytes) {
            return ByteString.copyFrom(bytes);
        }
        return mapped(value, RowExpressionProgram::bound);
    }

    // The reverse, for what an expression hands back. The byte string is the expression language's
    // own wrapper; a sink is owed the row's bytes, so a value that merely travelled through an
    // expression leaves as the kind of value it arrived as.
    private static Object unbound(Object value) {
        if (value instanceof ByteString bytes) {
            return bytes.toByteArray();
        }
        return mapped(value, RowExpressionProgram::unbound);
    }

    // Applies a conversion through a map or a list, returning the original container when nothing
    // inside it changed. Any other value is its own conversion.
    private static Object mapped(Object value, UnaryOperator<Object> conversion) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> converted = new LinkedHashMap<>(map.size());
            boolean changed = false;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object element = conversion.apply(entry.getValue());
                changed |= element != entry.getValue();
                converted.put(entry.getKey(), element);
            }
            return changed ? converted : map;
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                Object next = conversion.apply(element);
                changed |= next != element;
                converted.add(next);
            }
            return changed ? converted : list;
        }
        return value;
    }
}
