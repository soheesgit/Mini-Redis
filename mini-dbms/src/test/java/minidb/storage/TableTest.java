package minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Parser;
import minidb.type.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TableTest {

    @TempDir
    Path dir;

    static final Schema S = Schema.define((CreateTable) Parser.parse("CREATE TABLE t (name VARCHAR(10));").get(0));

    /** 번호 no 페이지에 (id, 이름) 행들을 담은 이미지 */
    static byte[] page(int no, int... ids) {
        DataPage p = DataPage.empty(no);
        for (int id : ids) p.insert(S.encode(new Object[] {id, "n" + id}));
        return p.toBytes();
    }

    static SortedMap<Integer, byte[]> imgs(byte[]... pages) {
        return imgsFrom(1, pages);
    }

    static SortedMap<Integer, byte[]> imgsFrom(int first, byte[]... pages) {
        SortedMap<Integer, byte[]> m = new TreeMap<>();
        for (int i = 0; i < pages.length; i++) m.put(first + i, pages[i]);
        return m;
    }

    Table create() throws IOException {
        return Table.create(dir, "t", S.toBytes(), true);
    }

    Table reopen() throws IOException {
        return Table.open(dir, "t", true, Faults.NONE);
    }

    static List<Integer> ids(Table t) throws IOException {
        List<Integer> out = new ArrayList<>();
        for (int n = 1; n < t.pageCount(); n++) {
            DataPage p = DataPage.fromBytes(n, t.readPage(n));
            for (int s = 0; s < p.slotCount(); s++) {
                if (p.read(s) != null) out.add((Integer) S.decode(p.read(s))[0]);
            }
        }
        return out;
    }

    @Test
    void createThenReopenKeepsSchemaAndData() throws IOException {
        try (Table t = create()) {
            assertEquals(S, t.schema());
            assertEquals(1, t.nextId());
            assertEquals(1, t.pageCount());
            t.commit(imgs(page(1, 1, 2)));
            assertEquals(2, t.pageCount());
        }
        assertEquals(0, Files.size(dir.resolve("t.wal"))); // close = 체크포인트
        try (Table t = reopen()) {
            assertEquals(List.of(1, 2), ids(t));
            assertEquals(3, t.nextId());
        }
    }

    @Test
    void createRefusesExistingAndCleansTmp() throws IOException {
        create().close();
        assertThrows(FileAlreadyExistsException.class, this::create);
        Files.write(dir.resolve("u.db.tmp"), new byte[5]);
        Table.create(dir, "u", S.toBytes(), true).close();
        assertFalse(Files.exists(dir.resolve("u.db.tmp")));
        Files.write(dir.resolve("t.db.tmp"), new byte[5]);
        reopen().close();
        assertFalse(Files.exists(dir.resolve("t.db.tmp")));
    }

    @Test
    void failedLogWriteLeavesNoTraceAndTableStaysUsable() throws IOException {
        create().close();
        boolean[] armed = {true};
        Faults f = (p, i) -> {
            if (armed[0] && p.equals("wal.half")) throw new IOException("로그 쓰기 실패");
        };
        Table t = Table.open(dir, "t", true, f);
        assertThrows(IOException.class, () -> t.commit(imgs(page(1, 1))));
        assertFalse(t.isBroken());
        armed[0] = false;
        t.commit(imgs(page(1, 2)));
        t.abandon();
        try (Table r = reopen()) {
            assertEquals(List.of(2), ids(r)); // 둘째 커밋만 있고 첫째는 없다
        }
    }

    @Test
    void crashBetweenLogHalvesLosesTheChange() throws IOException {
        create().close();
        Faults die = (p, i) -> {
            if (p.equals("wal.half")) throw new IOException("죽음");
        };
        try (Wal w = new Wal(dir.resolve("t.wal"), die)) { // 정리(truncate) 없이 죽은 상황
            assertThrows(IOException.class, () -> w.append(1, imgs(page(1, 1))));
        }
        assertTrue(Files.size(dir.resolve("t.wal")) > 0);
        try (Table t = reopen()) {
            assertEquals(List.of(), ids(t));
            assertEquals(1, t.nextId());
        }
    }

    @Test
    void crashDuringDataWriteIsRolledForward() throws IOException {
        create().close();
        Faults f = (p, i) -> {
            if (p.equals("data.write") && i == 1) throw new IOException("두 번째 페이지에서 죽음");
        };
        Table t = Table.open(dir, "t", true, f);
        IOException e = assertThrows(IOException.class, () -> t.commit(imgs(page(1, 1), page(2, 2))));
        assertTrue(e.getMessage().contains("복구"));
        assertTrue(t.isBroken());
        assertThrows(IOException.class, () -> t.commit(imgs(page(1, 9))));
        t.abandon();
        try (Table r = reopen()) {
            assertEquals(List.of(1, 2), ids(r));
            assertEquals(3, r.nextId());
        }
    }

    @Test
    void tornTailIsIgnored() throws IOException {
        create().close();
        Path wal = dir.resolve("t.wal");
        try (Wal w = new Wal(wal, Faults.NONE)) {
            w.append(1, imgs(page(1, 1)));
            w.append(2, imgsFrom(2, page(2, 2)));
            w.force();
        }
        try (FileChannel c = FileChannel.open(wal, StandardOpenOption.WRITE)) {
            c.truncate(c.size() - 10);
        }
        try (Table t = reopen()) {
            assertEquals(List.of(1), ids(t));
        }
    }

    @Test
    void logRepairsCorruptDataPage() throws IOException {
        create().close();
        Table t = Table.open(dir, "t", true, Faults.NONE);
        t.commit(imgs(page(1, 1, 2)));
        t.abandon(); // 체크포인트 없음: 로그에 이미지가 남아 있다
        corrupt(dir.resolve("t.db"), 8192 + 500);
        try (Table r = reopen()) {
            assertEquals(List.of(1, 2), ids(r));
        }
    }

    @Test
    void corruptPageWithEmptyLogRefusesOpenNamingThePage() throws IOException {
        try (Table t = create()) {
            t.commit(imgs(page(1, 1), page(2, 2)));
        }
        corrupt(dir.resolve("t.db"), 2 * 8192 + 500);
        StorageException e = assertThrows(StorageException.class, this::reopen);
        assertEquals(2, e.page());
    }

    @Test
    void recoveringTwiceGivesIdenticalFile() throws IOException {
        create().close();
        Path wal = dir.resolve("t.wal");
        try (Wal w = new Wal(wal, Faults.NONE)) {
            w.append(1, imgs(page(1, 1)));
            w.append(2, imgsFrom(1, page(1, 1, 2)));
            w.append(3, imgsFrom(2, page(2, 3)));
            w.force();
        }
        byte[] log = Files.readAllBytes(wal);
        reopen().close();
        byte[] first = Files.readAllBytes(dir.resolve("t.db"));
        Files.write(wal, log); // 복구하다 또 죽은 상황: 같은 로그로 다시 복구
        reopen().close();
        assertArrayEquals(first, Files.readAllBytes(dir.resolve("t.db")));
        try (Table t = reopen()) {
            assertEquals(List.of(1, 2, 3), ids(t));
        }
    }

    @Test
    void garbageLengthInLogOpensFine() throws IOException {
        create().close();
        byte[] junk = new byte[100];
        junk[0] = 0x7F;
        junk[1] = junk[2] = junk[3] = (byte) 0xFF;
        Files.write(dir.resolve("t.wal"), junk);
        try (Table t = reopen()) {
            assertEquals(List.of(), ids(t));
        }
        assertEquals(0, Files.size(dir.resolve("t.wal")));
    }

    @Test
    void fsyncOffStillCommitsAndRecovers() throws IOException {
        Table t = Table.create(dir, "t", S.toBytes(), false);
        t.commit(imgs(page(1, 1)));
        t.abandon();
        try (Table r = Table.open(dir, "t", false, Faults.NONE)) {
            assertEquals(List.of(1), ids(r));
        }
    }

    private static void corrupt(Path f, int pos) throws IOException {
        byte[] b = Files.readAllBytes(f);
        b[pos] ^= 0x55;
        Files.write(f, b);
    }
}
