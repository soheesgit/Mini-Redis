package miniredis.persist;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RecordsTest {
    static byte[][] argv(String... s) {
        return Arrays.stream(s).map(String::getBytes).toArray(byte[][]::new);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    @Test
    void roundTrip() {
        byte[] f = concat(Records.encode(argv("SET", "k", "v\n\0")), Records.encode(argv("DEL", "k")));
        Records.Parsed p = Records.parse(f);
        assertEquals(Records.Problem.NONE, p.problem());
        assertEquals(2, p.records().size());
        assertArrayEquals("v\n\0".getBytes(), p.records().get(0)[2]);
    }

    @Test
    void truncatedTailIsDetected() {
        byte[] a = Records.encode(argv("SET", "a", "1"));
        byte[] b = Records.encode(argv("SET", "b", "2"));
        for (int cut = 1; cut < b.length; cut++) { // 마지막 레코드를 어디서 잘라도 꼬리로 본다
            Records.Parsed p = Records.parse(concat(a, Arrays.copyOf(b, cut)));
            assertEquals(Records.Problem.TRUNCATED_TAIL, p.problem());
            assertEquals(a.length, p.goodEnd());
            assertEquals(1, p.records().size());
        }
    }

    @Test
    void lastRecordCrcMismatchIsTail_middleIsCorrupt() {
        byte[] a = Records.encode(argv("SET", "a", "1"));
        byte[] b = Records.encode(argv("SET", "b", "2"));
        byte[] f = concat(a, b);
        f[f.length - 6] ^= 1; // 마지막 레코드 본문 손상
        assertEquals(Records.Problem.TRUNCATED_TAIL, Records.parse(f).problem());
        byte[] g = concat(a, b);
        g[6] ^= 1;            // 첫 레코드 본문 손상 (뒤에 레코드가 더 있음)
        assertEquals(Records.Problem.CORRUPT, Records.parse(g).problem());
    }
}
