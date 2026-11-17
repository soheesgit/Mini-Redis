package minidb.type;

import static minidb.type.TypeTestSupport.schema;
import static minidb.type.TypeTestSupport.values;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 행 인코딩: 값 행 ↔ 바이트 (설계 1.6) */
class RowCodecTest {

    private static Object[] roundTrip(Schema s, Object[] row) {
        byte[] b = s.encode(row);
        Object[] back = s.decode(b);
        assertArrayEquals(row, back);
        return back;
    }

    @Test
    void designExampleBytes() {
        // users(id INT, name VARCHAR(20), age INT) 에 (1, 'kim', 20) = 14바이트
        Schema s = schema("name VARCHAR(20), age INT");
        Object[] row = s.checkInsert(values("('kim', 20)"));
        row[0] = 1;
        byte[] b = s.encode(row);
        assertArrayEquals(new byte[] {0, 0, 0, 0, 1, 0, 3, 'k', 'i', 'm', 0, 0, 0, 20}, b);
        assertArrayEquals(row, s.decode(b));
    }

    @Test
    void allTypesRoundTrip() {
        Schema s = schema("a INT, b BIGINT, c DOUBLE, d DECIMAL(18,2), e BOOLEAN, f VARCHAR(5), g DATE, h DATETIME, "
                + "i TIMESTAMP");
        roundTrip(s, new Object[] {7, -1, Long.MIN_VALUE, -0.1, new BigDecimal("-9999999999999999.99"), true, "한😀",
                LocalDate.of(2026, 11, 17), LocalDateTime.of(2026, 11, 17, 9, 0, 0, 123_456_000),
                Instant.parse("2026-11-17T00:00:00.000001Z")});
    }

    @Test
    void before1970RoundTrips() {
        Schema s = schema("g DATE, h DATETIME, i TIMESTAMP");
        roundTrip(s, new Object[] {1, LocalDate.of(1969, 12, 31), LocalDateTime.of(1969, 12, 31, 23, 59, 59, 999_999_000),
                Instant.parse("1969-12-31T23:59:59.999999Z")});
        roundTrip(s, new Object[] {2, LocalDate.of(1, 1, 1), LocalDateTime.of(1, 1, 1, 0, 0),
                Instant.parse("0001-01-01T00:00:00Z")});
        roundTrip(s, new Object[] {3, LocalDate.of(9999, 12, 31), LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_000),
                Instant.parse("9999-12-31T23:59:59.999999Z")});
    }

    @Test
    void nullsSetBitmapAndSkipBytes() {
        Schema s = schema("a INT, b VARCHAR(3)");
        byte[] b = s.encode(new Object[] {1, null, null});
        assertArrayEquals(new byte[] {0b110, 0, 0, 0, 1}, b); // 1번·2번 비트, 값 바이트 없음
        roundTrip(s, new Object[] {1, null, null});
        roundTrip(s, new Object[] {1, 0, ""}); // 0과 ''는 NULL이 아님
    }

    @Test
    void nullInSecondBitmapByte() {
        Schema s = schema("a BOOLEAN, b BOOLEAN, c BOOLEAN, d BOOLEAN, e BOOLEAN, f BOOLEAN, g BOOLEAN, h INT");
        byte[] b = s.encode(new Object[] {1, true, false, true, false, true, false, true, null});
        assertEquals(2 + 4 + 7, b.length);
        assertEquals(0, b[0]);
        assertEquals(1, b[1]); // 8번 칸(h) = 둘째 바이트 0번 비트
        roundTrip(s, new Object[] {1, true, false, true, false, true, false, true, null});
    }

    @Test
    void maxSizeRowFillsLimitExactly() {
        Schema s = schema("name VARCHAR(2040), age INT, ok BOOLEAN");
        Object[] row = {Integer.MAX_VALUE, "😀".repeat(2040), Integer.MIN_VALUE, true}; // 글자마다 UTF-8 4바이트
        assertEquals(Schema.MAX_ROW_SIZE, s.encode(row).length);
        roundTrip(s, row);
    }

    @Test
    void decodeReadsSliceOfPage() {
        Schema s = schema("name VARCHAR(20), age INT");
        byte[] row = s.encode(new Object[] {9, "lee", 31});
        byte[] page = new byte[8192];
        Arrays.fill(page, (byte) 0x7F);
        System.arraycopy(row, 0, page, 8192 - row.length, row.length); // 페이지 끝에 둔 행
        assertArrayEquals(new Object[] {9, "lee", 31}, s.decode(page, 8192 - row.length, row.length));
    }

    @Test
    void decodeRejectsWrongLength() {
        Schema s = schema("a INT");
        byte[] row = s.encode(new Object[] {1, 2});
        assertThrows(IllegalArgumentException.class, () -> s.decode(Arrays.copyOf(row, row.length + 1)));
        assertThrows(RuntimeException.class, () -> s.decode(Arrays.copyOf(row, row.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> s.encode(new Object[] {1}));
    }
}
