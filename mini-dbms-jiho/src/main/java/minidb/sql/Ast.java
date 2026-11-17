package minidb.sql;

import java.util.List;

/**
 * 파서가 만드는 문장 트리 (설계 1.1, 1.2). 이름은 쓴 모양 그대로 {@link Name}에 담고, 비교는 {@link Name#key}로 한다.
 * 이름·값 노드에 위치(line, col)를 둔다: 뒤 단계(#24 값·스키마 검사, #26·#37 없는 테이블·칸)가 에러를 낼 곳이 여기라서.
 * 목록은 모두 바꿀 수 없는 복사본이다.
 */
public final class Ast {

    private Ast() {
    }

    /** 테이블·칸 이름. text는 쓴 모양 그대로, 위치는 이름 첫 글자 */
    public record Name(String text, int line, int col) {

        /** 비교·테이블 파일 이름용 키 */
        public String key() {
            return key(text);
        }

        /**
         * ASCII A-Z만 a-z로 바꾼다 (설계 1.1: 식별자는 ASCII 글자만 대소문자 무관).
         * toLowerCase는 켈빈 기호 K(U+212A)를 k로, Ω(U+2126)를 ω로 바꿔 다른 이름을 같은 파일로 합친다
         */
        public static String key(String ident) {
            char[] cs = ident.toCharArray();
            for (int i = 0; i < cs.length; i++) {
                if (cs[i] >= 'A' && cs[i] <= 'Z') cs[i] += 'a' - 'A';
            }
            return new String(cs);
        }
    }

    // ───── 문장 ─────

    public sealed interface Statement permits CreateTable, Insert, Select, Update, Delete {
    }

    public record CreateTable(Name table, List<ColumnDef> columns) implements Statement {
        public CreateTable {
            columns = List.copyOf(columns);
        }
    }

    public enum ColumnType { INT, BIGINT, DOUBLE, DECIMAL, BOOLEAN, VARCHAR, DATE, DATETIME, TIMESTAMP }

    /** args는 VARCHAR(n)이면 [n], DECIMAL이면 언제나 [p, s] (생략하면 MySQL처럼 p=10, s=0), 나머지는 [] */
    public record ColumnDef(Name name, ColumnType type, List<Integer> args, boolean notNull) {
        public ColumnDef {
            args = List.copyOf(args);
        }
    }

    /** VALUES의 행 목록. 칸 목록 `(a, b)` 문법은 없다: 값은 id를 뺀 모든 칸 순서대로 (설계 1.1) */
    public record Insert(Name table, List<List<Literal>> rows) implements Statement {
        public Insert {
            rows = rows.stream().<List<Literal>>map(List::copyOf).toList();
        }
    }

    /** columns가 비어 있으면 `*`. where가 null이면 조건 없음, limit이 null이면 제한 없음 */
    public record Select(Name table, List<Name> columns, Expr where, List<OrderBy> orderBy, Long limit)
            implements Statement {
        public Select {
            columns = List.copyOf(columns);
            orderBy = List.copyOf(orderBy);
        }
    }

    public record OrderBy(Name column, boolean desc) {
    }

    public record Update(Name table, List<Assignment> sets, Expr where) implements Statement {
        public Update {
            sets = List.copyOf(sets);
        }
    }

    public record Assignment(Name column, Literal value) {
    }

    public record Delete(Name table, Expr where) implements Statement {
    }

    // ───── WHERE 식 ─────

    public sealed interface Expr permits Operand, Compare, And, Or, Not, Between, In, Like, IsNull {
    }

    /** 비교·BETWEEN·IN·LIKE·IS의 피연산자는 값이나 칸만. 괄호로 묶은 조건은 들어올 수 없다 */
    public sealed interface Operand extends Expr permits Literal, Column {
    }

    public enum LiteralKind { NUMBER, STRING, BOOLEAN, NULL }

    /** text: NUMBER는 쓴 글자 그대로(음수면 "-" 포함), STRING은 따옴표 벗긴 내용, BOOLEAN은 TRUE/FALSE, NULL은 NULL */
    public record Literal(LiteralKind kind, String text, int line, int col) implements Operand {
    }

    public record Column(Name name) implements Operand {
    }

    /** `<>`와 `!=`는 둘 다 NE */
    public enum CompareOp { EQ, NE, LT, LE, GT, GE }

    public record Compare(CompareOp op, Operand left, Operand right) implements Expr {
    }

    public record And(Expr left, Expr right) implements Expr {
    }

    public record Or(Expr left, Expr right) implements Expr {
    }

    public record Not(Expr expr) implements Expr {
    }

    public record Between(Operand value, Operand low, Operand high, boolean not) implements Expr {
    }

    public record In(Operand value, List<Operand> items, boolean not) implements Expr {
        public In {
            items = List.copyOf(items);
        }
    }

    /** pattern은 문자열 그대로. `%`, `_`, `\` 해석은 평가기(#37)가 한다 */
    public record Like(Operand value, String pattern, boolean not) implements Expr {
    }

    public record IsNull(Operand value, boolean not) implements Expr {
    }
}
