package minidb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Ast.Select;
import minidb.sql.Parser;
import minidb.sql.SqlException;
import minidb.type.Schema;
import org.junit.jupiter.api.Test;

/** WHERE 식 평가: 3값 논리, 비교·BETWEEN·IN·LIKE, compile 때의 타입 검사 */
class WhereTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Schema S = Schema.define((CreateTable) Parser.parse(
            "CREATE TABLE t (age INT, big BIGINT, d DOUBLE, price DECIMAL(5,2), active BOOLEAN, "
                    + "name VARCHAR(20), born DATE, at DATETIME, ts TIMESTAMP);").get(0));
    // 칸 순서: id, age, big, d, price, active, name, born, at, ts

    private static Where where(String cond) {
        return Where.compile(((Select) Parser.parse("SELECT * FROM t WHERE " + cond + ";").get(0)).where(), S, SEOUL);
    }

    private static Object[] row(Object age, Object d, Object price, Object active, Object name, Object born, Object ts) {
        return new Object[] {1, age, null, d, price, active, name, born, null, ts};
    }

    private static Object[] ageRow(Object age) {
        return row(age, null, null, null, null, null, null);
    }

    private static Object[] nameRow(Object name) {
        return row(null, null, null, null, name, null, null);
    }

    private static Boolean eval(String cond, Object[] row) {
        return where(cond).test(row);
    }

    // ───── 비교 ─────

    @Test
    void compareOpsTrueAndFalse() {
        Object[] r = ageRow(20);
        assertEquals(true, eval("age = 20", r));
        assertEquals(false, eval("age = 21", r));
        assertEquals(true, eval("age <> 21", r));
        assertEquals(true, eval("age != 21", r));
        assertEquals(false, eval("age <> 20", r));
        assertEquals(true, eval("age < 21", r));
        assertEquals(false, eval("age < 20", r));
        assertEquals(true, eval("age <= 20", r));
        assertEquals(false, eval("age <= 19", r));
        assertEquals(true, eval("age > 19", r));
        assertEquals(false, eval("age > 20", r));
        assertEquals(true, eval("age >= 20", r));
        assertEquals(false, eval("age >= 21", r));
        assertEquals(true, eval("21 > age", r)); // 리터럴이 왼쪽
    }

    @Test
    void nullOperandIsUnknown() {
        Object[] r = ageRow(null);
        assertNull(eval("age = 1", r));
        assertNull(eval("age <> 1", r));
        assertNull(eval("age < 1", r));
        assertNull(eval("NULL = NULL", r)); // NULL끼리도 TRUE가 아님
        assertNull(eval("age = NULL", ageRow(5)));
        assertNull(eval("NULL = 1", r));
    }

    @Test
    void decimalEqualsIntLiteralAndScale() {
        Object[] r = row(null, null, new BigDecimal("12.00"), null, null, null, null);
        assertEquals(true, eval("price = 12", r));
        assertEquals(true, eval("price = 12.0", r)); // 1.0 = 1.00
        assertEquals(true, eval("price >= 11.99", r));
        assertEquals(false, eval("price = 12.01", r));
    }

    @Test
    void doubleColumnEqualsPointOne() {
        Object[] r = row(null, 0.1, null, null, null, null, null);
        assertEquals(true, eval("d = 0.1", r));
        assertEquals(false, eval("d > 0.1", r));
    }

    @Test
    void columnToColumn() {
        Object[] r = new Object[] {1, 5, 5L, 5.0, new BigDecimal("5.00"), null, null, null, null, null};
        assertEquals(true, eval("age = big", r));
        assertEquals(true, eval("age = d", r));
        assertEquals(true, eval("price = age", r));
    }

    @Test
    void dateColumnVsString() {
        Object[] r = row(null, null, null, null, null, LocalDate.of(2026, 11, 17), null);
        assertEquals(true, eval("born = '2026-11-17'", r));
        assertEquals(true, eval("born < '2026-11-18'", r));
        assertEquals(false, eval("born > '2026-11-17'", r));
    }

    @Test
    void badDateStringIsErrorAtLiteral() {
        SqlException e = assertThrows(SqlException.class, () -> where("born = '2026-02-30'"));
        assertEquals(1, e.line());
        assertEquals("SELECT * FROM t WHERE born = ".length() + 1, e.col());
        assertTrue(e.getMessage().contains("YYYY-MM-DD"));
        // 빈 테이블이어도 compile에서 이미 터진다 (test를 부르기 전)
    }

    @Test
    void timestampUsesGivenZone() {
        Object[] r = row(null, null, null, null, null, null, Instant.parse("2026-11-17T00:00:00Z"));
        assertEquals(true, eval("ts = '2026-11-17 09:00:00'", r)); // 서울 09시 = UTC 00시
        assertEquals(false, eval("ts = '2026-11-17 00:00:00'", r));
        Where utc = Where.compile(((Select) Parser.parse("SELECT * FROM t WHERE ts = '2026-11-17 00:00:00';").get(0)).where(),
                S, ZoneId.of("UTC"));
        assertEquals(true, utc.test(r));
    }

    // ───── 3값 논리 ─────

    @Test
    void andOrNotTruthTable() {
        // T = (age = 1), F = (age = 2), U = (name = 'x') 이고 name이 NULL. 행: age 1
        Object[] r = row(1, null, null, null, null, null, null);
        String t = "age = 1", f = "age = 2", u = "name = 'x'";
        assertEquals(true, eval(t + " AND " + t, r));
        assertEquals(false, eval(t + " AND " + f, r));
        assertNull(eval(t + " AND " + u, r));
        assertEquals(false, eval(f + " AND " + u, r)); // FALSE AND unknown = FALSE
        assertEquals(false, eval(u + " AND " + f, r));
        assertNull(eval(u + " AND " + u, r));

        assertEquals(true, eval(t + " OR " + f, r));
        assertEquals(true, eval(t + " OR " + u, r)); // TRUE OR unknown = TRUE
        assertEquals(true, eval(u + " OR " + t, r));
        assertEquals(false, eval(f + " OR " + f, r));
        assertNull(eval(f + " OR " + u, r));
        assertNull(eval(u + " OR " + u, r));

        assertEquals(false, eval("NOT (" + t + ")", r));
        assertEquals(true, eval("NOT (" + f + ")", r));
        assertNull(eval("NOT (" + u + ")", r)); // NOT unknown = unknown
    }

    @Test
    void isNull() {
        assertEquals(true, eval("age IS NULL", ageRow(null)));
        assertEquals(false, eval("age IS NULL", ageRow(3)));
        assertEquals(false, eval("age IS NOT NULL", ageRow(null)));
        assertEquals(true, eval("age IS NOT NULL", ageRow(3)));
        assertEquals(true, eval("NULL IS NULL", ageRow(3)));
    }

    @Test
    void betweenAndNotBetween() {
        assertEquals(true, eval("age BETWEEN 10 AND 20", ageRow(10)));
        assertEquals(true, eval("age BETWEEN 10 AND 20", ageRow(20)));
        assertEquals(false, eval("age BETWEEN 10 AND 20", ageRow(21)));
        assertEquals(false, eval("age NOT BETWEEN 10 AND 20", ageRow(15)));
        assertEquals(true, eval("age NOT BETWEEN 10 AND 20", ageRow(21)));
        assertNull(eval("age BETWEEN 10 AND 20", ageRow(null)));
        assertNull(eval("age NOT BETWEEN 10 AND 20", ageRow(null)));
        assertNull(eval("age BETWEEN 10 AND NULL", ageRow(15)));
        assertEquals(false, eval("age BETWEEN 10 AND NULL", ageRow(5))); // FALSE AND unknown
    }

    @Test
    void inAndNotInWithNullItem() {
        assertEquals(true, eval("age IN (1, 2, 3)", ageRow(2)));
        assertEquals(false, eval("age IN (1, 2, 3)", ageRow(9)));
        assertEquals(true, eval("age NOT IN (1, 2, 3)", ageRow(9)));
        assertEquals(true, eval("age IN (1, NULL)", ageRow(1))); // 찾으면 NULL이 있어도 TRUE
        assertNull(eval("age IN (1, NULL)", ageRow(9)));
        assertNull(eval("age NOT IN (1, NULL)", ageRow(9))); // 이것이 TRUE이면 안 됨
        assertEquals(false, eval("age NOT IN (1, NULL)", ageRow(1)));
        assertNull(eval("age IN (1, 2)", ageRow(null)));
    }

    // ───── LIKE ─────

    @Test
    void likePercentAndUnderscore() {
        assertEquals(true, eval("name LIKE 'ab%'", nameRow("abc")));
        assertEquals(true, eval("name LIKE 'ab%'", nameRow("ab"))); // % = 빈 문자열도
        assertEquals(false, eval("name LIKE 'ab%'", nameRow("xab")));
        assertEquals(true, eval("name LIKE '%b%'", nameRow("abc")));
        assertEquals(true, eval("name LIKE 'a_c'", nameRow("abc")));
        assertEquals(false, eval("name LIKE 'a_c'", nameRow("ac")));
        assertEquals(false, eval("name LIKE 'a_c'", nameRow("abbc")));
        assertEquals(true, eval("name LIKE '%'", nameRow("")));
        assertEquals(false, eval("name LIKE 'ABC'", nameRow("abc"))); // 대소문자 구분
        assertEquals(true, eval("name LIKE 'a%b%c'", nameRow("aXXbYYc")));
        assertEquals(false, eval("name LIKE 'a%b%c'", nameRow("aXXbYY")));
        assertEquals(true, eval("name LIKE '%aab'", nameRow("aaaab"))); // 되돌아가야 맞는 경우
        assertNull(eval("name LIKE 'a%'", nameRow(null)));
        assertNull(eval("name NOT LIKE 'a%'", nameRow(null)));
        assertEquals(false, eval("name NOT LIKE 'a%'", nameRow("abc")));
        assertEquals(true, eval("name NOT LIKE 'a%'", nameRow("xbc")));
    }

    @Test
    void likeMultibyteAndSupplementaryCountAsOneChar() {
        assertEquals(true, eval("name LIKE '김_수'", nameRow("김영수")));
        assertEquals(false, eval("name LIKE '김_수'", nameRow("김수")));
        assertEquals(true, eval("name LIKE '김%'", nameRow("김철수")));
        assertEquals(true, eval("name LIKE 'a_'", nameRow("a😀"))); // 😀는 UTF-16 두 칸이어도 한 글자
    }

    @Test
    void likeBackslashIsPlainChar() {
        assertEquals(true, eval("name LIKE 'a\\%'", nameRow("a\\b"))); // 패턴 a\% = 'a', '\', 아무거나
        assertEquals(false, eval("name LIKE 'a\\%'", nameRow("a%")));
        assertEquals(true, eval("name LIKE 'a\\'", nameRow("a\\")));
    }

    @Test
    void likeOnNonStringIsError() {
        SqlException e = assertThrows(SqlException.class, () -> where("age LIKE '1%'"));
        assertEquals(1, e.line());
        assertEquals("SELECT * FROM t WHERE ".length() + 1, e.col());
        assertThrows(SqlException.class, () -> where("1 LIKE '1%'"));
        assertEquals(true, eval("'abc' LIKE 'a%'", ageRow(null)));
    }

    // ───── 컴파일 때 에러 ─────

    @Test
    void typeMismatchPointsAtLiteral() {
        SqlException e = assertThrows(SqlException.class, () -> where("age = 'x'"));
        assertEquals(1, e.line());
        assertEquals("SELECT * FROM t WHERE age = ".length() + 1, e.col());
        assertTrue(e.getMessage().contains("비교할 수 없는 타입: 칸 age(INT)와 문자열"), e.getMessage());

        e = assertThrows(SqlException.class, () -> where("'x' = age")); // 리터럴이 왼쪽이면 그 리터럴 자리
        assertEquals("SELECT * FROM t WHERE ".length() + 1, e.col());

        assertThrows(SqlException.class, () -> where("name = 5"));
        assertThrows(SqlException.class, () -> where("active = 1"));
        assertThrows(SqlException.class, () -> where("born = 5"));
        assertThrows(SqlException.class, () -> where("age BETWEEN 1 AND 'x'"));
        assertThrows(SqlException.class, () -> where("age IN (1, 'x')"));
        assertThrows(SqlException.class, () -> where("1 = 'x'"));
    }

    @Test
    void columnToColumnMismatchPointsAtRightName() {
        SqlException e = assertThrows(SqlException.class, () -> where("age = name"));
        assertEquals("SELECT * FROM t WHERE age = ".length() + 1, e.col());
        assertTrue(e.getMessage().contains("칸 age(INT)와 칸 name(VARCHAR(20))"), e.getMessage());
    }

    @Test
    void unknownColumnIsErrorAtName() {
        SqlException e = assertThrows(SqlException.class, () -> where("age = 1 AND zzz > 2"));
        assertEquals(1, e.line());
        assertEquals("SELECT * FROM t WHERE age = 1 AND ".length() + 1, e.col());
        assertTrue(e.getMessage().contains("없는 칸: zzz"), e.getMessage());
        assertEquals(true, eval("AGE = 1", ageRow(1))); // 칸 이름은 대소문자 무관
    }

    // ───── 조건 자리의 값, 조건 없음 ─────

    @Test
    void bareOperandAsCondition() {
        assertEquals(true, eval("active", row(null, null, null, true, null, null, null)));
        assertEquals(false, eval("active", row(null, null, null, false, null, null, null)));
        assertNull(eval("active", row(null, null, null, null, null, null, null)));
        assertEquals(true, eval("TRUE", ageRow(null)));
        assertEquals(false, eval("FALSE", ageRow(null)));
        assertNull(eval("NULL", ageRow(null)));
        assertEquals(false, eval("NOT active", row(null, null, null, true, null, null, null)));
        assertEquals(true, eval("active AND age = 1", row(1, null, null, true, null, null, null)));

        SqlException e = assertThrows(SqlException.class, () -> where("age"));
        assertEquals("SELECT * FROM t WHERE ".length() + 1, e.col());
        assertTrue(e.getMessage().contains("조건에는 TRUE/FALSE 값이 와야 함"));
        assertThrows(SqlException.class, () -> where("1"));
        assertThrows(SqlException.class, () -> where("'x'"));
    }

    @Test
    void nullExprIsAlwaysTrue() {
        assertEquals(true, Where.compile(null, S, SEOUL).test(ageRow(null)));
    }
}
