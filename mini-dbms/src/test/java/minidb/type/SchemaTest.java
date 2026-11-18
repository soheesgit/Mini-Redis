package minidb.type;

import static minidb.type.TypeTestSupport.assertError;
import static minidb.type.TypeTestSupport.schema;
import static minidb.type.TypeTestSupport.sets;
import static minidb.type.TypeTestSupport.values;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Map;
import minidb.sql.Ast.ColumnType;
import org.junit.jupiter.api.Test;

/** 테이블 정의 검사, 행 한도, INSERT·UPDATE 값 검사 */
class SchemaTest {

    // ───── 정의: 타입 10종과 id ─────

    @Test
    void definesAllTypesWithIdFirst() {
        Schema s = schema("a INT, b BIGINT, c DOUBLE, d DECIMAL(5,2), e BOOLEAN NOT NULL, f VARCHAR(20), "
                + "g DATE, h DATETIME, i TIMESTAMP, j DECIMAL");
        assertEquals(Schema.ID, s.columns().get(0));
        assertEquals(new Column("d", ColumnType.DECIMAL, 5, 2, false), s.columns().get(4));
        assertEquals(new Column("e", ColumnType.BOOLEAN, 0, 0, true), s.columns().get(5));
        assertEquals(new Column("f", ColumnType.VARCHAR, 20, 0, false), s.columns().get(6));
        assertEquals(new Column("j", ColumnType.DECIMAL, 10, 0, false), s.columns().get(10)); // MySQL 기본 (10,0)
        // 비트맵 2 (칸 11개) + id 4 + 4+8+8+8+1+(2+80)+4+8+8+8
        assertEquals(2 + 4 + 4 + 8 + 8 + 8 + 1 + 82 + 4 + 8 + 8 + 8, s.maxRowSize());
    }

    @Test
    void indexOfIgnoresAsciiCase() {
        Schema s = schema("Name VARCHAR(5), age INT");
        assertEquals(0, s.indexOf("ID"));
        assertEquals(1, s.indexOf("name"));
        assertEquals(2, s.indexOf("AGE"));
        assertEquals(-1, s.indexOf("nope"));
    }

    @Test
    void rejectsDeclaredId() {
        assertError(() -> schema("a INT,\n  id INT"), 2, 3, "id 칸은 자동으로");
        assertError(() -> schema("ID BIGINT"), 1, 17, "id 칸은 자동으로");
    }

    @Test
    void rejectsDuplicateColumnByKey() {
        assertError(() -> schema("a INT,\n  A VARCHAR(3)"), 2, 3, "같은 이름의 칸이 이미 있음: A");
    }

    @Test
    void rejectsVarcharZero() {
        assertError(() -> schema("a INT,\n  b VARCHAR(0)"), 2, 3, "VARCHAR 글자 수는 1 이상");
        schema("b VARCHAR(1)");
    }

    @Test
    void decimalPrecisionAndScaleLimits() {
        assertEquals(18, schema("a DECIMAL(18,18)").columns().get(1).n());
        schema("a DECIMAL(1,0)");
        assertError(() -> schema("a INT,\n  b DECIMAL(19,0)"), 2, 3, "DECIMAL 전체 자릿수는 1~18");
        assertError(() -> schema("b DECIMAL(0)"), 1, 17, "DECIMAL 전체 자릿수는 1~18");
        assertError(() -> schema("b DECIMAL(5,6)"), 1, 17, "DECIMAL 소수 자릿수는 전체 자릿수 이하");
    }

    // ───── 행 한도 8172B (설계 1.6) ─────

    @Test
    void designExampleVarcharLimit() {
        // 1 + 4 + (2 + 4n) + 4 ≤ 8172 → n ≤ 2040
        assertEquals(8171, schema("name VARCHAR(2040), age INT").maxRowSize());
        assertEquals(Schema.MAX_ROW_SIZE, schema("name VARCHAR(2040), age INT, ok BOOLEAN").maxRowSize()); // 딱 한도
        assertError(() -> schema("name VARCHAR(2040), age INT, ok BOOLEAN,\n  z BOOLEAN"), 2, 3, "이 칸까지 8173B");
        // 넘기는 칸은 VARCHAR가 아니라 뒤의 age (이름 칸까지는 8171B)
        assertError(() -> schema("name VARCHAR(2041),\n  age INT"), 2, 3, "8172B를 넘음 (이 칸까지 8175B)");
    }

    @Test
    void singleVarcharLimit() {
        assertEquals(8171, schema("s VARCHAR(2041)").maxRowSize()); // 1 + 4 + 2 + 8164
        assertError(() -> schema("s VARCHAR(2042)"), 1, 17, "이 칸까지 8175B");
        assertError(() -> schema("s VARCHAR(2147483647)"), 1, 17, "8172B를 넘음"); // int로 계산하면 넘쳐 통과함
    }

    @Test
    void bitmapGrowsAtNinthColumnIncludingId() {
        assertEquals(1 + 4 + 7, schema("a BOOLEAN, b BOOLEAN, c BOOLEAN, d BOOLEAN, e BOOLEAN, f BOOLEAN, g BOOLEAN")
                .maxRowSize());
        assertEquals(2 + 4 + 8, schema("a BOOLEAN, b BOOLEAN, c BOOLEAN, d BOOLEAN, e BOOLEAN, f BOOLEAN, g BOOLEAN, "
                + "h BOOLEAN").maxRowSize());
        // 칸 9개(비트맵 2B): 2 + 4 + 7 + (2 + 4n) ≤ 8172 → n ≤ 2039
        String sevenBools = "a BOOLEAN, b BOOLEAN, c BOOLEAN, d BOOLEAN, e BOOLEAN, f BOOLEAN, g BOOLEAN, ";
        assertEquals(8171, schema(sevenBools + "s VARCHAR(2039)").maxRowSize());
        assertError(() -> schema(sevenBools + "s VARCHAR(2040)"), 1, 94, "이 칸까지 8175B");
    }

    // ───── INSERT 값 검사 (설계 1.7①) ─────

    @Test
    void insertReturnsRowWithIdSlotEmpty() {
        Object[] row = schema("name VARCHAR(3), age INT, price DECIMAL(5,2)").checkInsert(values("('kim', 20, 1.5)"));
        assertArrayEquals(new Object[] {null, "kim", 20, new BigDecimal("1.50")}, row);
    }

    @Test
    void insertRejectsWrongCount() {
        Schema s = schema("a INT, b INT");
        assertError(() -> s.checkInsert(values("(1, 2,\n  3)")), 2, 3, "칸 2개(id 제외), 값 3개"); // 첫 남는 값
        assertError(() -> s.checkInsert(values("(1)")), 1, 23, "칸 2개(id 제외), 값 1개"); // 행 첫 값
    }

    @Test
    void insertNullAndNotNull() {
        Schema s = schema("a INT, b VARCHAR(2) NOT NULL");
        assertNull(s.checkInsert(values("(NULL, 'x')"))[1]);
        assertError(() -> s.checkInsert(values("(1,\n  NULL)")), 2, 3, "칸 b(VARCHAR(2)): NOT NULL 칸에 NULL");
    }

    @Test
    void insertRejectsTypeMismatch() {
        Schema s = schema("a INT, b VARCHAR(5), c BOOLEAN, d DATE");
        assertError(() -> s.checkInsert(values("('1', 'x', TRUE, '2026-01-01')")), 1, 23, "칸 a(INT): 문자열을 넣을 수 없음");
        assertError(() -> s.checkInsert(values("(1, 2, TRUE, '2026-01-01')")), 1, 26, "칸 b(VARCHAR(5)): 숫자를");
        assertError(() -> s.checkInsert(values("(1, 'x', 1, '2026-01-01')")), 1, 31, "칸 c(BOOLEAN): 숫자를");
        assertError(() -> s.checkInsert(values("(1, 'x', TRUE, 20260101)")), 1, 37, "칸 d(DATE): 숫자를");
    }

    @Test
    void insertVarcharCountsCharactersNotBytes() {
        Schema s = schema("a VARCHAR(2)");
        assertEquals("한글", s.checkInsert(values("('한글')"))[1]); // 6바이트지만 2글자
        assertEquals("😀😀", s.checkInsert(values("('😀😀')"))[1]); // UTF-16 4칸이지만 2글자
        assertEquals("", s.checkInsert(values("('')"))[1]);
        assertError(() -> s.checkInsert(values("('abc')")), 1, 23, "2글자까지 넣을 수 있음, 받은 값 3글자");
    }

    // ───── UPDATE SET 검사 (설계 1.9②) ─────

    @Test
    void updateReturnsIndexToValue() {
        Map<Integer, Object> m = schema("a INT, b VARCHAR(3)").checkUpdate(sets("B = 'xy', a = NULL"));
        assertEquals(java.util.List.of(2, 1), java.util.List.copyOf(m.keySet())); // SET에 쓴 순서
        assertEquals("xy", m.get(2));
        assertNull(m.get(1));
    }

    @Test
    void updateRejectsSameColumnTwice() {
        assertError(() -> schema("a INT").checkUpdate(sets("a = 1,\n  A = 2")), 2, 3, "같은 칸을 두 번 SET: A");
    }

    @Test
    void updateRejectsIdAndUnknownColumn() {
        Schema s = schema("a INT");
        assertError(() -> s.checkUpdate(sets("ID = 5")), 1, 14, "id 칸은 고칠 수 없음");
        assertError(() -> s.checkUpdate(sets("a = 1,\n  zz = 2")), 2, 3, "없는 칸: zz");
    }

    @Test
    void updateChecksTypeNullAndLength() {
        Schema s = schema("a INT NOT NULL, b VARCHAR(2)");
        assertError(() -> s.checkUpdate(sets("a = 'x'")), 1, 18, "칸 a(INT): 문자열을");
        assertError(() -> s.checkUpdate(sets("a = NULL")), 1, 18, "NOT NULL 칸에 NULL");
        assertError(() -> s.checkUpdate(sets("b = 'abc'")), 1, 18, "2글자까지");
    }
}
