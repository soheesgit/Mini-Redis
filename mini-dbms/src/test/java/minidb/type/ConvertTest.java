package minidb.type;

import static minidb.type.TypeTestSupport.assertError;
import static minidb.type.TypeTestSupport.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import minidb.sql.Ast.ColumnType;
import org.junit.jupiter.api.Test;

/** 리터럴 → 칸 타입 값 (숫자, 날짜). 에러 위치는 리터럴 자리: "INSERT INTO t VALUES (" 뒤라 1:23 */
class ConvertTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Column col(ColumnType type, int n, int s) {
        return new Column("c", type, n, s, false);
    }

    private static Object convert(ColumnType type, String value) {
        return col(type, 0, 0).convert(literal(value), SEOUL);
    }

    // ───── 정수 ─────

    @Test
    void intRange() {
        assertEquals(Integer.MAX_VALUE, convert(ColumnType.INT, "2147483647"));
        assertEquals(Integer.MIN_VALUE, convert(ColumnType.INT, "-2147483648"));
        assertEquals(0, convert(ColumnType.INT, "-0"));
        assertError(() -> convert(ColumnType.INT, "2147483648"), 1, 23, "칸 c(INT): 범위 밖: 2147483648");
        assertError(() -> convert(ColumnType.INT, "-2147483649"), 1, 23, "범위 밖");
    }

    @Test
    void bigintRange() {
        assertEquals(Long.MAX_VALUE, convert(ColumnType.BIGINT, "9223372036854775807"));
        assertEquals(Long.MIN_VALUE, convert(ColumnType.BIGINT, "-9223372036854775808"));
        assertError(() -> convert(ColumnType.BIGINT, "9223372036854775808"), 1, 23, "범위 밖");
    }

    @Test
    void integerColumnsRejectFractionButAcceptZeroFraction() {
        assertEquals(1, convert(ColumnType.INT, "1.0"));
        assertEquals(1L, convert(ColumnType.BIGINT, "1."));
        assertError(() -> convert(ColumnType.INT, "1.5"), 1, 23, "정수만 넣을 수 있음: 1.5");
        assertError(() -> convert(ColumnType.BIGINT, ".5"), 1, 23, "정수만");
    }

    // ───── DECIMAL: double을 거치지 않음 ─────

    @Test
    void decimalIsExact() {
        // double로 바꾸면 1234567890123456.8 이 된다 (유효 숫자 약 16자리)
        Column c = col(ColumnType.DECIMAL, 18, 2);
        assertEquals(new BigDecimal("1234567890123456.78"), c.convert(literal("1234567890123456.78")));
        assertEquals(new BigDecimal("0.10"), col(ColumnType.DECIMAL, 2, 2).convert(literal(".1")));
        assertEquals(new BigDecimal("-3.00"), col(ColumnType.DECIMAL, 5, 2).convert(literal("-3")));
    }

    @Test
    void decimalPrecisionBoundary() {
        Column c = col(ColumnType.DECIMAL, 18, 0);
        assertEquals(new BigDecimal("999999999999999999"), c.convert(literal("999999999999999999")));
        assertEquals(new BigDecimal("-999999999999999999"), c.convert(literal("-999999999999999999")));
        assertError(() -> c.convert(literal("1000000000000000000")), 1, 23, "칸 c(DECIMAL(18,0)): 범위 밖");
        Column d = col(ColumnType.DECIMAL, 5, 2);
        assertEquals(new BigDecimal("999.99"), d.convert(literal("999.99")));
        assertError(() -> d.convert(literal("1000")), 1, 23, "범위 밖: 1000");
    }

    @Test
    void decimalRejectsTooManyFractionDigits() {
        Column c = col(ColumnType.DECIMAL, 5, 2);
        assertEquals(new BigDecimal("1.23"), c.convert(literal("1.230"))); // 버리는 자리가 0이면 됨
        assertError(() -> c.convert(literal("1.234")), 1, 23, "소수 자릿수 2자리 초과: 1.234");
    }

    // ───── DOUBLE ─────

    @Test
    void doubleConversion() {
        assertEquals(0.1, convert(ColumnType.DOUBLE, "0.1"));
        assertEquals(-2.5, convert(ColumnType.DOUBLE, "-2.5"));
        String huge = "1" + "0".repeat(400); // Double.parseDouble이 Infinity를 줌
        assertError(() -> convert(ColumnType.DOUBLE, huge), 1, 23, "칸 c(DOUBLE): 범위 밖");
    }

    @Test
    void booleanOnlyTrueFalse() {
        assertEquals(true, convert(ColumnType.BOOLEAN, "true"));
        assertEquals(false, convert(ColumnType.BOOLEAN, "FALSE"));
        assertError(() -> convert(ColumnType.BOOLEAN, "'TRUE'"), 1, 23, "문자열을 넣을 수 없음");
    }

    @Test
    void varcharRejectsUnpairedSurrogate() {
        // UTF-8로 바꾸면 '?'가 되어 몰래 값이 바뀌므로 거부 (API 서버의 JSON "\ud800" 같은 입력)
        assertError(() -> col(ColumnType.VARCHAR, 5, 0).convert(literal("'a\uD800'")), 1, 23, "UTF-8로 바꿀 수 없는 글자");
    }

    // ───── 날짜 ─────

    @Test
    void dateParsing() {
        assertEquals(LocalDate.of(2026, 11, 17), convert(ColumnType.DATE, "'2026-11-17'"));
        assertEquals(LocalDate.of(1, 1, 1), convert(ColumnType.DATE, "'0001-01-01'"));
        assertEquals(LocalDate.of(9999, 12, 31), convert(ColumnType.DATE, "'9999-12-31'"));
        assertError(() -> convert(ColumnType.DATE, "'2026-02-30'"), 1, 23, "칸 c(DATE): 날짜 형식은 'YYYY-MM-DD'");
        assertError(() -> convert(ColumnType.DATE, "'2026-1-5'"), 1, 23, "날짜 형식");
        assertError(() -> convert(ColumnType.DATE, "'0000-01-01'"), 1, 23, "연도는 1~9999");
        assertError(() -> convert(ColumnType.DATE, "''"), 1, 23, "날짜 형식");
    }

    @Test
    void datetimeParsing() {
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 5, 7), convert(ColumnType.DATETIME, "'2026-11-17 09:05:07'"));
        assertEquals(LocalDateTime.of(2026, 11, 17, 9, 5, 7, 123_456_000),
                convert(ColumnType.DATETIME, "'2026-11-17 09:05:07.123456'"));
        assertEquals(LocalDateTime.of(2026, 11, 17, 0, 0), convert(ColumnType.DATETIME, "'2026-11-17'"));
        assertError(() -> convert(ColumnType.DATETIME, "'2026-11-17 09:05:07.1234567'"), 1, 23, "소수 초는 6자리까지");
        assertError(() -> convert(ColumnType.DATETIME, "'2026-11-17 24:00:00'"), 1, 23, "날짜·시각 형식");
        assertError(() -> convert(ColumnType.DATETIME, "'2026-02-30 00:00:00'"), 1, 23, "날짜·시각 형식"); // 28일로 줄이지 않음
        assertError(() -> convert(ColumnType.DATETIME, "'2026-11-17T09:00:00'"), 1, 23, "날짜·시각 형식");
    }

    @Test
    void timestampConvertsServerZoneToUtc() {
        assertEquals(Instant.parse("2026-11-17T00:00:00Z"), convert(ColumnType.TIMESTAMP, "'2026-11-17 09:00:00'"));
        // 같은 글자라도 시간대가 다르면 다른 순간 (DATETIME은 시간대가 없어 그대로)
        assertEquals(Instant.parse("2026-11-17T14:00:00Z"),
                col(ColumnType.TIMESTAMP, 0, 0).convert(literal("'2026-11-17 09:00:00'"), NEW_YORK));
    }

    @Test
    void timestampDstGapShiftsForward() {
        // 2026-03-08 02:00 EST → 03:00 EDT: 02:30은 없는 시각 → 1시간 밀려 03:30 EDT = 07:30Z
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), Values.parseTimestamp("2026-03-08 02:30:00", NEW_YORK));
        // 2026-11-01 01:30은 두 번 있음 → 앞의 것(EDT, -4)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), Values.parseTimestamp("2026-11-01 01:30:00", NEW_YORK));
    }

    @Test
    void parseErrorsAreIllegalArgumentForEvaluator() {
        assertThrows(IllegalArgumentException.class, () -> Values.parseDate("nope"));
        assertThrows(IllegalArgumentException.class, () -> Values.parseDateTime("2026-13-01 00:00:00"));
    }
}
