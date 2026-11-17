package minidb.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import minidb.sql.Ast.*;
import org.junit.jupiter.api.Test;

class ParserTest {

    private static Statement one(String sql) {
        List<Statement> ss = Parser.parse(sql);
        assertEquals(1, ss.size());
        return ss.get(0);
    }

    /** WHERE 식을 한 줄 글자로: 우선순위(묶인 모양)를 눈으로 비교하려고 */
    private static String show(Expr e) {
        return switch (e) {
            case Literal l -> l.kind() == LiteralKind.STRING ? "'" + l.text() + "'" : l.text();
            case Column c -> c.name().text();
            case Compare c -> c.op() + "(" + show(c.left()) + "," + show(c.right()) + ")";
            case And a -> "AND(" + show(a.left()) + "," + show(a.right()) + ")";
            case Or o -> "OR(" + show(o.left()) + "," + show(o.right()) + ")";
            case Not n -> "NOT(" + show(n.expr()) + ")";
            case Between b -> (b.not() ? "NOT_" : "") + "BETWEEN(" + show(b.value()) + "," + show(b.low()) + "," + show(b.high()) + ")";
            case In in -> (in.not() ? "NOT_" : "") + "IN(" + show(in.value()) + ",["
                    + in.items().stream().map(ParserTest::show).collect(Collectors.joining(",")) + "])";
            case Like l -> (l.not() ? "NOT_" : "") + "LIKE(" + show(l.value()) + ",'" + l.pattern() + "')";
            case IsNull n -> (n.not() ? "IS_NOT_NULL(" : "IS_NULL(") + show(n.value()) + ")";
        };
    }

    private static String where(String cond) {
        return show(((Select) one("SELECT * FROM t WHERE " + cond + ";")).where());
    }

    private static void assertError(String sql, int line, int col, String part) {
        SqlException e = assertThrows(SqlException.class, () -> Parser.parse(sql));
        assertEquals(line, e.line(), e.getMessage());
        assertEquals(col, e.col(), e.getMessage());
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    // ───── CREATE TABLE ─────

    @Test
    void createTableWithAllTypes() {
        CreateTable c = (CreateTable) one("""
                CREATE TABLE Users (name varchar(20) NOT NULL, age INT, big BIGINT, d DOUBLE, price Decimal(10, 2),
                  ok BOOLEAN, born DATE, at DATETIME, ts TIMESTAMP);""");
        assertEquals(new Name("Users", 1, 14), c.table());
        assertEquals(new ColumnDef(new Name("name", 1, 21), ColumnType.VARCHAR, List.of(20), true), c.columns().get(0));
        assertEquals(new ColumnDef(new Name("age", 1, 48), ColumnType.INT, List.of(), false), c.columns().get(1));
        assertEquals(new ColumnDef(new Name("price", 1, 79), ColumnType.DECIMAL, List.of(10, 2), false), c.columns().get(4));
        assertEquals(List.of(ColumnType.VARCHAR, ColumnType.INT, ColumnType.BIGINT, ColumnType.DOUBLE, ColumnType.DECIMAL,
                        ColumnType.BOOLEAN, ColumnType.DATE, ColumnType.DATETIME, ColumnType.TIMESTAMP),
                c.columns().stream().map(ColumnDef::type).toList());
    }

    @Test
    void createTableTypeErrors() {
        assertError("CREATE TABLE t (a ınt);", 1, 19, "모르는 타입: ınt"); // 'ı'를 I로 바꾸지 않음
        assertError("CREATE TABLE t (a TEXT);", 1, 19, "모르는 타입");
        assertError("CREATE TABLE t (a VARCHAR);", 1, 26, "VARCHAR 뒤 '('");
        assertError("CREATE TABLE t (a VARCHAR(1.));", 1, 27, "0 이상의 정수");
        assertError("CREATE TABLE t (a VARCHAR(-3));", 1, 27, "0 이상의 정수");
        assertError("CREATE TABLE t (a VARCHAR(99999999999));", 1, 27, "너무 큼");
        assertError("CREATE TABLE t (a DECIMAL(10));", 1, 29, "','");
        assertError("CREATE TABLE t (a INT(11));", 1, 22, "')'");
        assertError("CREATE TABLE t (a INT NOT);", 1, 26, "NULL 필요");
        assertError("CREATE TABLE t ();", 1, 17, "칸 이름 필요");
    }

    // ───── INSERT ─────

    @Test
    void insertMultiRowWithAllLiteralKinds() {
        Insert ins = (Insert) one("INSERT INTO users VALUES ('kim', 20, -3.5, NULL, true), ('it''s', .5, 1., FALSE, -0);");
        assertEquals(new Name("users", 1, 13), ins.table());
        assertEquals(2, ins.rows().size());
        List<Literal> r0 = ins.rows().get(0);
        assertEquals(List.of(LiteralKind.STRING, LiteralKind.NUMBER, LiteralKind.NUMBER, LiteralKind.NULL, LiteralKind.BOOLEAN),
                r0.stream().map(Literal::kind).toList());
        assertEquals(List.of("kim", "20", "-3.5", "NULL", "TRUE"), r0.stream().map(Literal::text).toList());
        assertEquals(List.of("it's", ".5", "1.", "FALSE", "-0"), ins.rows().get(1).stream().map(Literal::text).toList());
    }

    @Test
    void negativeLiteralIsOneTokenAtMinusPosition() {
        Literal l = ((Insert) one("INSERT INTO t VALUES (\n  -9223372036854775808);")).rows().get(0).get(0);
        assertEquals("-9223372036854775808", l.text()); // 문자열로 묶어 BIGINT 최솟값도 넘치지 않음
        assertEquals(2, l.line());
        assertEquals(3, l.col());
        assertError("INSERT INTO t VALUES (--1);", 1, 24, "'-' 뒤에는 숫자"); // --1은 거부
        assertError("INSERT INTO t VALUES (-'a');", 1, 24, "'-' 뒤에는 숫자");
    }

    @Test
    void insertErrors() {
        assertError("INSERT INTO t VALUES ();", 1, 23, "값 필요");
        assertError("INSERT INTO t VALUES (a);", 1, 23, "값 필요"); // 칸 이름은 값이 아님
        assertError("INSERT t VALUES (1);", 1, 8, "INTO 필요");
        assertError("INSERT INTO t (1);", 1, 15, "VALUES 필요"); // 칸 목록 문법 없음
    }

    // ───── SELECT ─────

    @Test
    void selectStarAndColumns() {
        Select s = (Select) one("SELECT * FROM users;");
        assertEquals(new Select(new Name("users", 1, 15), List.of(), null, List.of(), null), s);
        Select s2 = (Select) one("select Name, age from Users;");
        assertEquals(List.of(new Name("Name", 1, 8), new Name("age", 1, 14)), s2.columns());
        assertEquals(new Name("Users", 1, 23), s2.table());
    }

    @Test
    void selectOrderByAndLimit() {
        Select s = (Select) one("SELECT * FROM t WHERE a > 1 ORDER BY age DESC, name, id ASC LIMIT 10;");
        assertEquals(List.of(new OrderBy(new Name("age", 1, 38), true), new OrderBy(new Name("name", 1, 48), false),
                new OrderBy(new Name("id", 1, 54), false)), s.orderBy());
        assertEquals(10L, s.limit());
        assertEquals(Long.MAX_VALUE, ((Select) one("SELECT * FROM t LIMIT 9223372036854775807;")).limit());
    }

    @Test
    void limitMustBeNonNegativeInteger() {
        assertError("SELECT * FROM t LIMIT 1.5;", 1, 23, "0 이상의 정수");
        assertError("SELECT * FROM t LIMIT .5;", 1, 23, "0 이상의 정수");
        assertError("SELECT * FROM t LIMIT -1;", 1, 23, "0 이상의 정수");
        assertError("SELECT * FROM t LIMIT 9223372036854775808;", 1, 23, "너무 큼");
        assertError("SELECT * FROM t LIMIT;", 1, 22, "0 이상의 정수");
    }

    @Test
    void selectErrors() {
        assertError("SELECT * users;", 1, 10, "FROM 필요");
        assertError("SELECT FROM t;", 1, 8, "예약어 FROM");
        assertError("SELECT order FROM t;", 1, 8, "예약어 ORDER는 칸 이름으로 쓸 수 없음");
        assertError("SELECT * FROM desc;", 1, 15, "예약어 DESC는 테이블 이름으로");
        assertError("SELECT * FROM t ORDER age;", 1, 23, "BY 필요");
        assertError("SELECT * FROM t LIMIT 1 ORDER BY a;", 1, 25, "';' 필요"); // 순서는 ORDER BY → LIMIT
    }

    // ───── UPDATE · DELETE ─────

    @Test
    void updateAndDelete() {
        Update u = (Update) one("UPDATE users SET age = 21, name = 'kim', note = NULL WHERE id = 1;");
        assertEquals(new Name("users", 1, 8), u.table());
        assertEquals(List.of(new Name("age", 1, 18), new Name("name", 1, 28), new Name("note", 1, 42)),
                u.sets().stream().map(Assignment::column).toList());
        assertEquals(List.of("21", "kim", "NULL"), u.sets().stream().map(a -> a.value().text()).toList());
        assertEquals("EQ(id,1)", show(u.where()));
        assertNull(((Update) one("UPDATE t SET a = -1;")).where()); // WHERE 없음 = 전체 행 (실행기 몫)

        Delete d = (Delete) one("DELETE FROM users WHERE age < 20;");
        assertEquals("LT(age,20)", show(d.where()));
        assertEquals(new Delete(new Name("t", 1, 13), null), one("DELETE FROM t;"));
    }

    @Test
    void updateAndDeleteErrors() {
        assertError("UPDATE t SET a = b;", 1, 18, "값 필요"); // SET 값은 리터럴만
        assertError("UPDATE t SET a 1;", 1, 16, "'=' 필요");
        assertError("UPDATE t a = 1;", 1, 10, "SET 필요");
        assertError("DELETE t;", 1, 8, "FROM 필요");
    }

    // ───── WHERE 우선순위 ─────

    @Test
    void andBindsTighterThanOr() {
        assertEquals("OR(EQ(a,1),AND(EQ(b,2),EQ(c,3)))", where("a=1 OR b=2 AND c=3"));
        assertEquals("OR(AND(EQ(a,1),EQ(b,2)),EQ(c,3))", where("a=1 AND b=2 OR c=3"));
        assertEquals("AND(OR(EQ(a,1),EQ(b,2)),EQ(c,3))", where("(a=1 OR b=2) AND c=3"));
        assertEquals("AND(AND(EQ(a,1),EQ(b,2)),EQ(c,3))", where("a=1 AND b=2 AND c=3")); // 왼쪽부터 묶음
    }

    @Test
    void comparisonBindsTighterThanNot() {
        assertEquals("AND(NOT(EQ(a,1)),EQ(b,2))", where("NOT a = 1 AND b = 2"));
        assertEquals("OR(NOT(EQ(a,1)),EQ(b,2))", where("NOT a = 1 OR b = 2"));
        assertEquals("NOT(NOT(EQ(a,1)))", where("NOT NOT a = 1"));
        assertEquals("NOT(OR(EQ(a,1),EQ(b,2)))", where("NOT (a = 1 OR b = 2)"));
    }

    @Test
    void betweenBoundsDoNotSwallowTheNextAnd() {
        assertEquals("AND(BETWEEN(a,1,5),EQ(b,2))", where("a BETWEEN 1 AND 5 AND b = 2"));
        assertEquals("OR(NOT_BETWEEN(a,-1,'z'),EQ(b,2))", where("a NOT BETWEEN -1 AND 'z' OR b = 2"));
        assertError("SELECT * FROM t WHERE a BETWEEN 1 OR 5;", 1, 35, "AND 필요");
    }

    @Test
    void allComparisonOperators() {
        assertEquals("AND(AND(AND(AND(AND(AND(EQ(a,1),NE(b,2)),NE(c,3)),LT(d,4)),LE(e,5)),GT(f,6)),GE(g,'x'))",
                where("a = 1 AND b <> 2 AND c != 3 AND d < 4 AND e <= 5 AND f > 6 AND g >= 'x'"));
        assertEquals("EQ(a,b)", where("a = b")); // 칸끼리 비교
        assertEquals("EQ(1,a)", where("1 = a"));
    }

    @Test
    void inLikeIsNull() {
        assertEquals("IN(a,[1,-2,'x',NULL])", where("a IN (1, -2, 'x', NULL)"));
        assertEquals("NOT_IN(a,[b])", where("a NOT IN (b)"));
        assertEquals("LIKE(name,'k%_')", where("name LIKE 'k%_'"));
        assertEquals("NOT_LIKE(name,'a\\%')", where("name NOT LIKE 'a\\%'")); // \ 는 그대로 (#37이 해석)
        assertEquals("AND(IS_NULL(a),IS_NOT_NULL(b))", where("a IS NULL AND b IS NOT NULL"));
        assertEquals("NOT(IS_NULL(a))", where("NOT a IS NULL"));
    }

    @Test
    void operandsAreOnlyValuesOrColumns() {
        // 괄호는 조건 자리에만 (리뷰 G2): 피연산자 자리의 '('는 값이 아님
        assertError("SELECT * FROM t WHERE a IN ((b OR c));", 1, 29, "값 필요");
        assertError("SELECT * FROM t WHERE x BETWEEN (y AND z) AND 3;", 1, 33, "값 필요");
        assertError("SELECT * FROM t WHERE a = (1);", 1, 27, "값 필요");
        assertEquals("AND(OR(EQ(a,1),EQ(b,2)),EQ(c,3))", where("((a=1 OR b=2)) AND (c=3)"));
        assertEquals("NOT(OR(EQ(a,1),EQ(b,2)))", where("NOT ((a = 1) OR (b = 2))"));
        assertEquals("a", where("(a)")); // 값 하나도 조건이라 괄호로 묶을 수 있음
    }

    @Test
    void whereErrors() {
        assertError("SELECT * FROM t WHERE a IN ();", 1, 29, "값 필요");
        assertError("SELECT * FROM t WHERE a LIKE b;", 1, 30, "문자열 패턴");
        assertError("SELECT * FROM t WHERE a NOT = 1;", 1, 25, "NOT은 BETWEEN, IN, LIKE 앞에만");
        assertError("SELECT * FROM t WHERE a IS 1;", 1, 28, "NULL 필요");
        assertError("SELECT * FROM t WHERE (a = 1;", 1, 29, "')' 필요");
        assertError("SELECT * FROM t WHERE a = 1 = 2;", 1, 29, "';' 필요"); // 비교 연쇄는 받지 않음
        assertError("SELECT * FROM t WHERE;", 1, 22, "값 필요");
        assertError("SELECT * FROM t WHERE a = ;", 1, 27, "값 필요");
    }

    @Test
    void singleValueIsACondition() {
        assertEquals("flag", where("flag"));
        assertEquals("AND(TRUE,NOT(ok))", where("TRUE AND NOT ok"));
    }

    @Test
    void whereNodesCarryPositions() {
        Compare c = (Compare) ((Select) one("SELECT * FROM t\nWHERE  age = 'x';")).where();
        assertEquals(new Column(new Name("age", 2, 8)), c.left());
        assertEquals(new Literal(LiteralKind.STRING, "x", 2, 14), c.right());
    }

    // ───── 여러 문장 · 문장 경계 ─────

    @Test
    void multipleStatementsAreAllParsed() {
        List<Statement> ss = Parser.parse("CREATE TABLE t (a INT);\nINSERT INTO t VALUES (1);\nSELECT * FROM t;");
        assertEquals(3, ss.size());
        assertTrue(ss.get(0) instanceof CreateTable);
        assertTrue(ss.get(1) instanceof Insert);
        assertTrue(ss.get(2) instanceof Select);
        assertEquals(List.of(), Parser.parse("  -- 주석만\n"));
    }

    @Test
    void errorInLaterStatementFailsTheWholeInput() {
        // 첫 문장이 맞아도 결과 목록 없이 예외 → 실행기는 아무것도 실행하지 않음 (설계 1.2)
        assertError("INSERT INTO t VALUES (1);\nSELECT * users;", 2, 10, "FROM 필요");
    }

    @Test
    void statementBoundaryErrors() {
        assertError("SELECT * FROM t", 1, 16, "';' 필요, 받은 것: 입력 끝");
        assertError("SELECT * FROM t;;", 1, 17, "빈 문장");
        assertError(";", 1, 1, "빈 문장");
        assertError("DROP TABLE t;", 1, 1, "CREATE, INSERT, SELECT, UPDATE, DELETE로 시작");
        assertError("SELECT * FROM t SELECT * FROM u;", 1, 17, "';' 필요");
    }

    @Test
    void identifierKeyIgnoresCaseButNotLookalikes() {
        assertEquals(Name.key("users"), Name.key("USERS"));
        assertEquals(Name.key("users"), Name.key("Users"));
        assertEquals("users", new Name("UsErS", 1, 1).key());
        assertNotEquals(Name.key("ınt"), Name.key("INT")); // ı(U+0131)는 i가 아님
        assertNotEquals(Name.key("ſelect"), Name.key("SELECT")); // ſ(U+017F)는 s가 아님
        assertNotEquals(Name.key("\u212Aey"), Name.key("key")); // 켈빈 기호 K(U+212A)는 k가 아님
        assertNotEquals(Name.key("\u212Aey"), Name.key("Key"));
        assertNotEquals(Name.key("\u2126"), Name.key("\u03C9")); // Ω(U+2126 옴 기호)는 ω가 아님
        assertEquals("\u00C9", Name.key("\u00C9")); // ASCII 밖 글자는 그대로 (É ≠ é)
    }

    @Test
    void astListsAreImmutable() {
        Select s = (Select) one("SELECT a FROM t WHERE b IN (1) ORDER BY a;");
        assertThrows(UnsupportedOperationException.class, () -> s.columns().add(new Name("x", 1, 1)));
        assertThrows(UnsupportedOperationException.class, () -> s.orderBy().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((In) s.where()).items().clear());
        Insert ins = (Insert) one("INSERT INTO t VALUES (1);");
        assertThrows(UnsupportedOperationException.class, () -> ins.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> ins.rows().get(0).clear());
        CreateTable c = (CreateTable) one("CREATE TABLE t (a VARCHAR(3));");
        assertThrows(UnsupportedOperationException.class, () -> c.columns().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.columns().get(0).args().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((Update) one("UPDATE t SET a = 1;")).sets().clear());

        // 파서 밖에서 만든 레코드도 넘겨준 목록과 끊긴다
        List<Name> cols = new java.util.ArrayList<>(List.of(new Name("a", 1, 1)));
        Select made = new Select(new Name("t", 1, 1), cols, null, List.of(), null);
        cols.add(new Name("b", 1, 1));
        assertEquals(1, made.columns().size());
    }
}
