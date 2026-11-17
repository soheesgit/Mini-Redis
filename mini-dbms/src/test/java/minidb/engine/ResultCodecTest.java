package minidb.engine;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResultCodecTest {
    @Test void preservesTypesNullUnicodeAndDecimalScale() throws Exception {
        Object[] row = {null, 42, Long.MAX_VALUE, -1.25, new BigDecimal("12.3400"), true,
                "한글😀|:\u0000", LocalDate.of(2026, 11, 17), LocalDateTime.parse("2026-11-17T12:30:00.123456789"),
                Instant.parse("2026-11-17T03:30:00.123456789Z")};
        var names = java.util.stream.IntStream.range(0, row.length).mapToObj(i -> "c" + i).toList();
        Result actual = ResultCodec.decode(ResultCodec.encode(new Result(names, Collections.singletonList(row), 1, null)));
        assertEquals(names, actual.columns());
        assertArrayEquals(row, actual.rows().getFirst());
        assertEquals(1, actual.affected());
        assertNull(actual.changedTable());
    }

    @Test void emptyRowsAndLongText() throws Exception {
        assertTrue(ResultCodec.decode(ResultCodec.encode(new Result(List.of("v"), List.of(), 0, null))).rows().isEmpty());
        String text = "가".repeat(30000);
        var result = new Result(List.of("v"), Collections.singletonList(new Object[]{text}), 1, null);
        assertEquals(text, ResultCodec.decode(ResultCodec.encode(result)).rows().getFirst()[0]);
    }

    @Test void rejectsTruncationTrailingBytesInvalidCountsAndTags() throws Exception {
        byte[] good = ResultCodec.encode(new Result(List.of("v"), Collections.singletonList(new Object[]{"hello"}), 1, null));
        for (int length = 0; length < good.length; length++) {
            byte[] truncated = Arrays.copyOf(good, length);
            assertThrows(IOException.class, () -> ResultCodec.decode(truncated));
        }
        assertThrows(IOException.class, () -> ResultCodec.decode(Arrays.copyOf(good, good.length + 1)));
        for (int offset : new int[]{4, 8, 13, 18}) {
            byte[] bad = good.clone();
            Arrays.fill(bad, offset, offset + 4, (byte) 0xff);
            assertThrows(IOException.class, () -> ResultCodec.decode(bad));
        }
        byte[] bad = good.clone(); bad[17] = 99;
        assertThrows(IOException.class, () -> ResultCodec.decode(bad));
    }
}
