package minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalTest {

    @TempDir
    Path dir;

    static byte[] img(int fill) {
        byte[] b = new byte[DataPage.SIZE];
        java.util.Arrays.fill(b, (byte) fill);
        return b;
    }

    static SortedMap<Integer, byte[]> pages(int... nos) {
        SortedMap<Integer, byte[]> m = new TreeMap<>();
        for (int n : nos) m.put(n, img(n));
        return m;
    }

    private record Rec(long txid, SortedMap<Integer, byte[]> pages) {}

    private static List<Rec> read(Wal w) throws IOException {
        List<Rec> out = new ArrayList<>();
        w.replay((t, p) -> out.add(new Rec(t, p)));
        return out;
    }

    @Test
    void recordsComeBackInOrder() throws IOException {
        Path f = dir.resolve("t.wal");
        try (Wal w = new Wal(f, Faults.NONE)) {
            w.append(1, pages(1, 2));
            w.append(2, pages(3));
            w.force();
            assertEquals(2 * (18 + 8196) + 8196, w.size());
            List<Rec> r = read(w);
            assertEquals(2, r.size());
            assertEquals(1, r.get(0).txid());
            assertEquals(List.of(1, 2), List.copyOf(r.get(0).pages().keySet()));
            assertArrayEquals(img(2), r.get(0).pages().get(2));
            assertEquals(List.of(3), List.copyOf(r.get(1).pages().keySet()));
        }
        assertEquals(Files.size(f), 2 * 18 + 3 * 8196);
    }

    @Test
    void halfWrittenRecordIsIgnored() throws IOException {
        Path f = dir.resolve("t.wal");
        Faults half = (p, i) -> {
            if (p.equals("wal.half")) throw new IOException("죽음");
        };
        try (Wal w = new Wal(f, Faults.NONE)) {
            w.append(1, pages(1));
        }
        try (Wal w = new Wal(f, half)) {
            assertThrows(IOException.class, () -> w.append(2, pages(2, 3)));
        }
        assertTrue(Files.size(f) > 18 + 8196); // 반쪽이 파일에 남아 있다
        try (Wal w = new Wal(f, Faults.NONE)) {
            assertEquals(1, read(w).size());
        }
    }

    @Test
    void nextAppendOverwritesLeftoverOfFailedOne() throws IOException {
        Path f = dir.resolve("t.wal");
        boolean[] armed = {true};
        Faults half = (p, i) -> {
            if (armed[0] && p.equals("wal.half")) throw new IOException("실패");
        };
        try (Wal w = new Wal(f, half)) {
            assertThrows(IOException.class, () -> w.append(1, pages(1, 2, 3)));
            assertEquals(0, w.size());
            armed[0] = false;
            w.append(2, pages(4));
            List<Rec> r = read(w);
            assertEquals(1, r.size());
            assertEquals(2, r.get(0).txid());
        }
    }

    @Test
    void crcMismatchStopsReplay() throws IOException {
        Path f = dir.resolve("t.wal");
        try (Wal w = new Wal(f, Faults.NONE)) {
            w.append(1, pages(1));
            w.append(2, pages(2));
        }
        byte[] all = Files.readAllBytes(f);
        all[18 + 8196 + 100] ^= 1; // 두 번째 레코드 안쪽
        Files.write(f, all);
        try (Wal w = new Wal(f, Faults.NONE)) {
            assertEquals(1, read(w).size());
        }
    }

    @Test
    void garbageHeadersAreInvalidWithoutHugeAllocation() throws IOException {
        Path f = dir.resolve("t.wal");
        for (int len : new int[] {0x7FFFFFFF, -1, 0, 17, 18 + 100, 18 + 8196 /* 남은 길이보다 큼 */}) {
            ByteBuffer b = ByteBuffer.allocate(64);
            b.putInt(len);
            Files.write(f, b.array());
            try (Wal w = new Wal(f, Faults.NONE)) {
                assertEquals(0, read(w).size(), "len=" + len);
            }
        }
        Files.write(f, new byte[] {1, 2, 3}); // 4B도 안 되는 꼬리
        try (Wal w = new Wal(f, Faults.NONE)) {
            assertEquals(0, read(w).size());
        }
    }

    @Test
    void pageNumberZeroIsInvalid() throws IOException {
        Path f = dir.resolve("t.wal");
        try (Wal w = new Wal(f, Faults.NONE)) {
            w.append(1, pages(0));
            assertEquals(0, read(w).size());
        }
    }

    @Test
    void resetEmptiesLog() throws IOException {
        try (Wal w = new Wal(dir.resolve("t.wal"), Faults.NONE)) {
            w.append(1, pages(1));
            w.reset();
            assertEquals(0, w.size());
            assertEquals(0, read(w).size());
        }
    }
}
