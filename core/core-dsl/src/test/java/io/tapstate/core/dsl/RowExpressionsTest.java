package io.tapstate.core.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cel.common.CelAbstractSyntaxTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The public CEL compilation surface shared by the validate layer (diagnostic strings) and the
 * runtime evaluation layer (a checked AST the engine's transform ports run). One compiler
 * environment over the §6 envelope root serves both, so a predicate that type-checks at validate
 * time is the same one that evaluates at runtime — the two can never drift.
 */
class RowExpressionsTest {

    @Test
    @DisplayName("predicateAst compiles a bool row predicate to a checked AST")
    void predicateAstCompilesBoolPredicate() {
        CelAbstractSyntaxTree ast = RowExpressions.predicateAst("after.deleted == 0");

        assertThat(ast).isNotNull();
    }

    @Test
    @DisplayName("valueAst compiles a computed row value to a checked AST")
    void valueAstCompilesValue() {
        CelAbstractSyntaxTree ast = RowExpressions.valueAst("after.first + ' ' + after.last");

        assertThat(ast).isNotNull();
    }

    @Test
    @DisplayName("predicateAst refuses an expression that references an undeclared envelope field")
    void predicateAstRefusesUndeclaredField() {
        assertThatThrownBy(() -> RowExpressions.predicateAst("afterr.region == 'US'"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("predicateAst refuses a predicate that does not yield bool")
    void predicateAstRefusesNonBool() {
        assertThatThrownBy(() -> RowExpressions.predicateAst("src"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The row is read by name, and an index into it is refused rather than left to compile. The
     * untyped row is a map, so an indexed read type-checks here and carries no column name — which is
     * what makes it worth refusing: the column it reads never reaches the layer that judges columns
     * against the types a source resolved them to, so the one spelling would run unexamined while the
     * other is checked.
     */
    @Test
    @DisplayName("a row field read by index is refused, and the diagnostic names the spelling to use")
    void valueErrorRefusesAnIndexedRowRead() {
        String diagnostic = RowExpressions.valueError("after[\"amount\"] * 2");

        assertThat(diagnostic).isNotNull().contains("after.");
    }

    @Test
    @DisplayName("the same refusal covers a predicate, and the before row alongside after")
    void predicateErrorRefusesAnIndexedRowRead() {
        assertThat(RowExpressions.predicateError("before['id'] == 1")).isNotNull();
    }

    /**
     * A key that is only known at evaluation time is the same escape with a different spelling, and a
     * macro body is where it is most natural to write. Refusing the constant-key form alone would move
     * the hole rather than close it.
     */
    @Test
    @DisplayName("an indexed row read is refused inside a macro body too, dynamic key and all")
    void predicateErrorRefusesAnIndexedRowReadInsideAMacro() {
        assertThat(RowExpressions.predicateError("after.exists(k, after[k] > 0)")).isNotNull();
    }

    /**
     * Only the row root is refused. {@code schema} is envelope metadata rather than source columns, so
     * nothing about it is judged against a source model and indexing it escapes no check; and indexing
     * a value read out of the row — a list or map column — is the ordinary way to reach into it, with
     * the column itself still named. Refusing either would cost authoring reach for no gain.
     */
    @Test
    @DisplayName("indexing envelope metadata, or a value read out of the row, stays allowed")
    void indexingOutsideTheRowRootIsAllowed() {
        assertThat(RowExpressions.valueError("schema['name']")).isNull();
        assertThat(RowExpressions.valueError("after.tags[0]")).isNull();
        assertThat(RowExpressions.valueError("['a', 'b'][1]")).isNull();
    }
}
