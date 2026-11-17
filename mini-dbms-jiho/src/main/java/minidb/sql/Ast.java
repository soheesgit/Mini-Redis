package minidb.sql;

import java.util.List;

/**
 * 파서가 만드는 문장 트리 (설계 1.1, 1.2). 이름은 쓴 모양 그대로 담고, 비교는 {@link Parser#key}로 한다.
 * 값·칸 노드에만 위치(line, col)를 둔다: 뒤 단계(#24 값 검사, #26·#37 없는 칸)가 에러를 낼 곳이 여기라서.
 */
public final class Ast {

    private Ast() {
    }

    // ───── 문장 ─────

    public sealed interface Statement permits CreateTable, Insert, Select, Update, Delete {
    }

    public record CreateTable(String table, List<ColumnDef> columns) implements Statement {
    }

    /** type은 대문자 표준 이름(INT, VARCHAR...). args는 VARCHAR(n)이면 [n], DECIMAL(p,s)면 [p, s] */
    public record ColumnDef(String name, String type, List<Integer> args, boolean notNull) {
    }

    /** VALUES의 행 목록. 칸 목록 `(a, b)` 문법은 없다: 값은 id를 뺀 모든 칸 순서대로 (설계 1.1) */
    public record Insert(String table, List<List<Literal>> rows) implements Statement {
    }

    /** columns가 비어 있으면 `*`. where가 null이면 조건 없음, limit이 null이면 제한 없음 */
    public record Select(String table, List<String> columns, Expr where, List<OrderBy> orderBy, Long limit)
            implements Statement {
    }

    public record OrderBy(String column, boolean desc) {
    }

    public record Update(String table, List<Assignment> sets, Expr where) implements Statement {
    }

    public record Assignment(String column, Literal value) {
    }

    public record Delete(String table, Expr where) implements Statement {
    }

    // ───── WHERE 식 ─────

    public sealed interface Expr permits Literal, Column, Compare, And, Or, Not, Between, In, Like, IsNull {
    }

    public enum LiteralKind { NUMBER, STRING, BOOLEAN, NULL }

    /** text: NUMBER는 쓴 글자 그대로(음수면 "-" 포함), STRING은 따옴표 벗긴 내용, BOOLEAN은 TRUE/FALSE, NULL은 NULL */
    public record Literal(LiteralKind kind, String text, int line, int col) implements Expr {
    }

    public record Column(String name, int line, int col) implements Expr {
    }

    /** op는 EQ NE LT LE GT GE 중 하나 (`<>`와 `!=`는 둘 다 NE) */
    public record Compare(TokenType op, Expr left, Expr right) implements Expr {
    }

    public record And(Expr left, Expr right) implements Expr {
    }

    public record Or(Expr left, Expr right) implements Expr {
    }

    public record Not(Expr expr) implements Expr {
    }

    public record Between(Expr value, Expr low, Expr high, boolean not) implements Expr {
    }

    public record In(Expr value, List<Expr> items, boolean not) implements Expr {
    }

    /** pattern은 문자열 그대로. `%`, `_`, `\` 해석은 평가기(#37)가 한다 */
    public record Like(Expr value, String pattern, boolean not) implements Expr {
    }

    public record IsNull(Expr value, boolean not) implements Expr {
    }
}
