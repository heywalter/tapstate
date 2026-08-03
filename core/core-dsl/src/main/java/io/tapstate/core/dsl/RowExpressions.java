package io.tapstate.core.dsl;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelFunctionDecl;
import dev.cel.common.CelOverloadDecl;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;
import dev.cel.common.types.CelType;
import dev.cel.common.types.CelTypeProvider;
import dev.cel.common.types.ListType;
import dev.cel.common.types.MapType;
import dev.cel.common.types.OpaqueType;
import dev.cel.common.types.SimpleType;
import dev.cel.common.types.StructType;
import dev.cel.common.types.StructTypeReference;
import dev.cel.compiler.CelCompiler;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.parser.CelStandardMacro;
import io.tapstate.core.common.TapstateType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Compiles + type-checks the row expressions (CEL): the single place the cel-java dependency is
 * touched for compilation. It does not evaluate — evaluation is the runtime layer's concern — but
 * it serves both sides from one compiler environment: the validate layer takes the diagnostic
 * string ({@link #predicateError} / {@link #valueError}), the runtime layer takes the checked AST
 * it then evaluates ({@link #predicateAst} / {@link #valueAst}). Sharing the one environment is
 * what guarantees an expression that type-checks at validate time is the same one that evaluates at
 * runtime — the two can never drift.
 *
 * <p>Expressions bind the event envelope as their root: {@code op} / {@code ts} / {@code src} are
 * scalars, {@code before} / {@code after} / {@code schema} are maps. The function environment is the
 * CEL standard library (with the standard macros {@code has} / {@code exists} / {@code all} /
 * {@code map} / {@code filter}) plus the one builtin the grammar commits ({@code now()}). Two shapes:
 * a predicate must compile to {@code bool}; a computed value may compile to any type.
 *
 * <p>The row's columns are typed in one of two ways, and the difference is the whole point of the
 * split:
 * <ul>
 *   <li><b>Untyped</b> ({@link #predicateError} / {@link #valueError}) — no source is known, so
 *       {@code after.region} resolves to {@code dyn}. A typo in a top-level envelope name is still
 *       caught; a column-level type error cannot be.</li>
 *   <li><b>Typed</b> ({@link #typedPredicateError} / {@link #typedValueError}) — a discovered source
 *       has resolved each column's type, so {@code after.amount} carries it. An operation the column
 *       cannot survive then fails to compile rather than reaching the runtime.</li>
 * </ul>
 *
 * <p>A tapstate type becomes a CEL type only where CEL represents it exactly. Everything else — an
 * exact fixed-point number, the temporal shapes, a value that is only known to be JSON — becomes a
 * named opaque type instead, which carries the value through unchanged but matches no operator
 * overload, so computing on it is refused and the diagnostic names the type. Guessing an
 * approximation here is exactly the silent conversion the typed check exists to prevent.
 *
 * <p>A column the source model does not carry stays {@code dyn} and compiles. For a source whose
 * model comes from sampling, a column missing from the model is a normal state rather than an error,
 * so refusing it would turn a correct pipeline into an authoring failure.
 */
public final class RowExpressions {

    private RowExpressions() {
    }

    /** {@code now()} — current event time; the one builtin beyond the CEL standard library. */
    private static final CelFunctionDecl NOW = CelFunctionDecl.newFunctionDeclaration(
            "now", CelOverloadDecl.newGlobalOverload("now_timestamp", SimpleType.TIMESTAMP));

    /** The name the typed row carries in the compiler's type namespace. */
    private static final String ROW_TYPE = "tapstate.Row";

    /** The envelope's row maps before any source model is known: every column is dyn. */
    private static final CelType UNTYPED_ROW = MapType.create(SimpleType.STRING, SimpleType.DYN);

    private static final CelCompiler PREDICATE = envelope().setResultType(SimpleType.BOOL).build();
    private static final CelCompiler VALUE = envelope().build();

    /** The shared half of the environment: everything outside the row itself. */
    private static CelCompilerBuilder base() {
        return CelCompilerFactory.standardCelCompilerBuilder()
                // the CEL standard macros (has / exists / all / map / filter) are off by default in
                // cel-java; has() in particular is the canonical presence test for the dyn-typed
                // envelope map fields, so enable the standard set explicitly.
                .setStandardMacros(CelStandardMacro.STANDARD_MACROS)
                .addVar("op", SimpleType.STRING)
                .addVar("ts", SimpleType.INT)
                .addVar("src", SimpleType.STRING)
                .addVar("schema", MapType.create(SimpleType.STRING, SimpleType.DYN))
                .addFunctionDeclarations(NOW);
    }

    /** A compiler over the envelope root with an untyped row, and no result-type constraint. */
    private static CelCompilerBuilder envelope() {
        return base()
                .addVar("before", UNTYPED_ROW)
                .addVar("after", UNTYPED_ROW);
    }

    /** The same envelope with the row carrying per-column types. */
    private static CelCompilerBuilder envelope(StructType row) {
        return base()
                .setTypeProvider(providerOf(row))
                .addVar("before", StructTypeReference.create(ROW_TYPE))
                .addVar("after", StructTypeReference.create(ROW_TYPE));
    }

    /** Checks a predicate; returns the diagnostic, or {@code null} when it compiles to {@code bool}. */
    static String predicateError(String expr) {
        return error(PREDICATE, expr);
    }

    /** Checks a computed value; returns the diagnostic, or {@code null} when it compiles. */
    static String valueError(String expr) {
        return error(VALUE, expr);
    }

    /**
     * The row columns {@code expr} reads through {@code after} / {@code before}, in the order it
     * reads them. Empty means the expression reads no row data at all — it works off the envelope
     * scalars, the schema metadata or builtins alone — and therefore needs no source model to be
     * judged. A presence test counts: {@code has(after.region)} reads the column as much as a value
     * access does.
     *
     * <p>{@code expr} is expected to have already passed the untyped check, so a compile failure
     * here is a programmer error (an unchecked expression reaching this point), not a diagnosable
     * user condition — it bare-throws.
     */
    public static Set<String> rowColumns(String expr) {
        Set<String> columns = new LinkedHashSet<>();
        CelNavigableAst.fromAst(ast(VALUE, expr)).getRoot().allNodes()
                .filter(node -> node.getKind() == CelExpr.ExprKind.Kind.SELECT)
                .forEach(node -> {
                    CelExpr.CelSelect select = node.expr().select();
                    CelExpr operand = select.operand();
                    if (operand.getKind() == CelExpr.ExprKind.Kind.IDENT) {
                        String root = operand.ident().name();
                        if (root.equals("after") || root.equals("before")) {
                            columns.add(select.field());
                        }
                    }
                });
        return columns;
    }

    /**
     * Checks a predicate against the row columns' resolved types; returns the diagnostic, or
     * {@code null} when it compiles to {@code bool}. {@code columns} maps a column name to what the
     * source resolved it to; a column the map omits stays {@code dyn}.
     */
    public static String typedPredicateError(String expr, Map<String, TapstateType> columns) {
        return error(typed(expr, columns).setResultType(SimpleType.BOOL).build(), expr);
    }

    /** Checks a computed value the same way {@link #typedPredicateError} checks a predicate. */
    public static String typedValueError(String expr, Map<String, TapstateType> columns) {
        return error(typed(expr, columns).build(), expr);
    }

    /**
     * Compiles a predicate to a checked AST for evaluation. The expression is expected to have
     * already passed validation, so a compile failure here is a programmer error (an unchecked
     * expression reaching the runtime), not a diagnosable user condition — it bare-throws.
     */
    public static CelAbstractSyntaxTree predicateAst(String expr) {
        return ast(PREDICATE, expr);
    }

    /** Compiles a computed value to a checked AST for evaluation; bare-throws like {@link #predicateAst}. */
    public static CelAbstractSyntaxTree valueAst(String expr) {
        return ast(VALUE, expr);
    }

    /**
     * The typed environment for one expression. The row's field set is the model's columns plus the
     * ones this expression reads, because a struct answers only for the fields it declares: a column
     * the model does not carry has to be declared as {@code dyn} to stay allowed rather than being
     * reported as an undefined field.
     */
    private static CelCompilerBuilder typed(String expr, Map<String, TapstateType> columns) {
        Map<String, CelType> fields = new LinkedHashMap<>();
        columns.forEach((name, type) -> fields.put(name, celTypeOf(type)));
        for (String referenced : rowColumns(expr)) {
            fields.putIfAbsent(referenced, SimpleType.DYN);
        }
        StructType row = StructType.create(ROW_TYPE, ImmutableSet.copyOf(fields.keySet()),
                name -> Optional.ofNullable(fields.get(name)));
        return envelope(row);
    }

    /** A tapstate type as CEL sees it, or a named opaque type where CEL has no exact counterpart. */
    private static CelType celTypeOf(TapstateType type) {
        return switch (type) {
            case STRING -> SimpleType.STRING;
            case INT64 -> SimpleType.INT;
            case DOUBLE -> SimpleType.DOUBLE;
            case BOOLEAN -> SimpleType.BOOL;
            case BINARY -> SimpleType.BYTES;
            case MAP -> MapType.create(SimpleType.STRING, SimpleType.DYN);
            case ARRAY -> ListType.create(SimpleType.DYN);
            // No exact counterpart: an exact fixed-point number loses its scale in any binary
            // floating point type, a temporal value without a zone cannot become an instant without
            // assuming one, and a JSON value is not known to be any single shape. Each stays a named
            // opaque type that carries through but matches no overload.
            case DECIMAL, DATE, TIME, DATETIME, YEAR, JSON, UNKNOWN -> opaqueTypeOf(type);
        };
    }

    private static CelType opaqueTypeOf(TapstateType type) {
        return OpaqueType.create("tapstate." + type.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether CEL has no exact counterpart for {@code type}, so a column holding it can be carried
     * through an expression but not computed on. Derived from the mapping itself rather than restated,
     * so the two can never disagree about which types those are.
     */
    static boolean withoutExactCelType(TapstateType type) {
        return celTypeOf(type) instanceof OpaqueType;
    }

    private static CelTypeProvider providerOf(StructType row) {
        return new CelTypeProvider() {
            @Override
            public ImmutableCollection<CelType> types() {
                return ImmutableList.of(row);
            }

            @Override
            public Optional<CelType> findType(String typeName) {
                return ROW_TYPE.equals(typeName) ? Optional.of(row) : Optional.empty();
            }
        };
    }

    private static String error(CelCompiler compiler, String expr) {
        CelValidationResult result = compiler.compile(expr);
        return result.hasError() ? result.getErrorString() : null;
    }

    private static CelAbstractSyntaxTree ast(CelCompiler compiler, String expr) {
        CelValidationResult result = compiler.compile(expr);
        if (result.hasError()) {
            throw new IllegalArgumentException(result.getErrorString());
        }
        try {
            return result.getAst();
        } catch (CelValidationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
