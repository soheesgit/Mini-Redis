package minidb.type;

import static minidb.type.TypeTestSupport.literal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 칸 타입 없는 리터럴 값, 값 비교, 출력 글자 */
class ValuesTest {

    @Test
    void literalWithoutColumnType() {
        assertEquals(20L, Values.of(literal("20")));
        assertEquals(Long.MIN_VALUE, Values.of(literal("-9223372036854775808")));
        assertEquals(new BigDecimal("9223372036854775808"), Values.of(literal("9223372036854775808")));
        assertEquals(new BigDecimal("0.1"), Values.of(literal("0.1"))); // double 아님
        assertEquals("kim", Values.of(literal("'kim'")));
        assertEquals(true, Values.of(literal("TRUE")));
        assertEquals(false, Values.of(literal("false")));
        assertNull(Values.of(literal("NULL")));
    }

    @Test
    void numbersCompareAcrossNumericTypes() {
        assertEquals(0, Values.compare(20, 20L));
        assertEquals(0, Values.compare(new BigDecimal("1.0"), new BigDecimal("1.00"))); // equals는 false
        assertEquals(0, Values.compare(2.5, new BigDecimal("2.50")));
        assertTrue(Values.compare(1, 1.5) < 0);
        assertTrue(Values.compare(Long.MAX_VALUE, Long.MAX_VALUE - 1) > 0); // double로 바꾸면 같아짐
        // DOUBLE 칸 0.1과 WHERE의 0.1(BigDecimal)은 같아야 한다: 한쪽이 DOUBLE이면 double로 비교
        assertEquals(0, Values.compare(0.1, Values.of(literal("0.1"))));
        assertEquals(0, Values.compare(Values.of(literal("0.1")), 0.1));
        assertTrue(Values.comparable(1, new BigDecimal("1")));
    }

    @Test
    void sameTypeComparisons() {
        assertTrue(Values.compare("B", "a") < 0); // 대소문자 구분 (바이트 순서)
        assertEquals(0, Values.compare("", ""));
        assertTrue(Values.compare(false, true) < 0);
        assertTrue(Values.compare(LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31)) > 0);
        assertTrue(Values.compare(Instant.EPOCH, Instant.EPOCH.plusNanos(1000)) < 0);
    }

    @Test
    void differentTypesAreNotComparable() {
        assertFalse(Values.comparable("1", 1));
        assertFalse(Values.comparable(LocalDate.of(2026, 1, 1), LocalDateTime.of(2026, 1, 1, 0, 0)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Values.compare("1", 1));
        assertEquals("비교할 수 없는 타입: VARCHAR, INT", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Values.compare(true, 1));
    }

    @Test
    void formatForOutput() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        assertEquals("NULL", Values.format(null, seoul));
        assertEquals("TRUE", Values.format(true, seoul));
        assertEquals("1.50", Values.format(new BigDecimal("1.50"), seoul));
        assertEquals("2026-11-17", Values.format(LocalDate.of(2026, 11, 17), seoul));
        assertEquals("2026-11-17 09:00:00", Values.format(LocalDateTime.of(2026, 11, 17, 9, 0), seoul));
        assertEquals("2026-11-17 09:00:00.000001",
                Values.format(LocalDateTime.of(2026, 11, 17, 9, 0, 0, 1000), seoul));
        // TIMESTAMP: UTC 순간 → 서버 시간대 시각
        assertEquals("2026-11-17 09:00:00", Values.format(Instant.parse("2026-11-17T00:00:00Z"), seoul));
        Instant t = Values.parseTimestamp("2026-11-17 09:00:00.5", seoul);
        assertEquals("2026-11-17 09:00:00.500000", Values.format(t, seoul));
    }
}
