package minidb.storage;

import static minidb.storage.DataPageTest.KIM;
import static minidb.storage.DataPageTest.row;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageFileTest {

    @TempDir
    Path dir;

    private static final byte[] SCHEMA = {0, 3, 2, 'i', 'd', 1, 0, 0, 0};

    @Test
    void metaPageLayout() throws IOException {
        Path f = dir.resolve("users.db");
        PageFile.create(f, SCHEMA).close();
        byte[] all = Files.readAllBytes(f);
        assertEquals(8192, all.length);
        assertArrayEquals("MDB1".getBytes(), Arrays.copyOfRange(all, 0, 4));
        ByteBuffer b = ByteBuffer.wrap(all);
        assertEquals(1, b.getShort(4));
        assertEquals(8192, b.getShort(6));
        assertArrayEquals(SCHEMA, Arrays.copyOfRange(all, 8, 8 + SCHEMA.length));
    }

    @Test
    void schemaSurvivesReopen() throws IOException {
        Path f = dir.resolve("t.db");
        PageFile.create(f, SCHEMA).close();
        try (PageFile pf = PageFile.open(f)) {
            byte[] s = pf.schema();
            assertEquals(8184, s.length); // 8번부터 페이지 끝까지
            assertArrayEquals(SCHEMA, Arrays.copyOf(s, SCHEMA.length));
            assertTrue(Arrays.stream(new int[] {s[SCHEMA.length], s[8183]}).allMatch(x -> x == 0));
            assertEquals(1, pf.pageCount());
        }
    }

    @Test
    void createRefusesExistingFile() throws IOException {
        Path f = dir.resolve("t.db");
        PageFile.create(f, SCHEMA).close();
        assertThrows(FileAlreadyExistsException.class, () -> PageFile.create(f, SCHEMA));
    }

    @Test
    void pageNAtNTimes8192() throws IOException {
        Path f = dir.resolve("t.db");
        try (PageFile pf = PageFile.create(f, SCHEMA)) {
            DataPage p1 = DataPage.empty(1);
            p1.insert(row(5, 1));
            DataPage p2 = DataPage.empty(2);
            p2.insert(KIM);
            pf.writePage(1, p1.toBytes());
            pf.writePage(2, p2.toBytes());
            assertEquals(3, pf.pageCount());
        }
        byte[] all = Files.readAllBytes(f);
        assertEquals(3 * 8192, all.length);
        assertEquals(2, ByteBuffer.wrap(all).getInt(2 * 8192)); // page_id at 16384
        assertArrayEquals(KIM, Arrays.copyOfRange(all, 3 * 8192 - 14, 3 * 8192));
    }

    @Test
    void writeReopenReadSameRows() throws IOException {
        Path f = dir.resolve("t.db");
        try (PageFile pf = PageFile.create(f, SCHEMA)) {
            DataPage p = DataPage.empty(1);
            p.insert(KIM);
            p.insert(row(100, 2));
            p.insert(row(30, 3));
            pf.writePage(1, p.toBytes());
        }
        try (PageFile pf = PageFile.open(f)) {
            DataPage p = DataPage.fromBytes(1, pf.readPage(1));
            assertEquals(3, p.slotCount());
            assertArrayEquals(KIM, p.read(0));
            assertArrayEquals(row(100, 2), p.read(1));
            // 삭제·수정 뒤 같은 자리에 다시 씀
            p.delete(1);
            assertTrue(p.update(2, row(200, 4))); // 길어져서 페이지 안 다른 자리로
            assertTrue(p.update(0, row(3, 5))); // 짧아져서 제자리
            pf.writePage(1, p.toBytes());
        }
        try (PageFile pf = PageFile.open(f)) {
            DataPage p = DataPage.fromBytes(1, pf.readPage(1));
            assertEquals(3, p.slotCount()); // 슬롯 번호 그대로
            assertArrayEquals(row(3, 5), p.read(0));
            assertNull(p.read(1));
            assertArrayEquals(row(200, 4), p.read(2));
        }
    }

    @Test
    void overwriteExistingPage() throws IOException {
        try (PageFile pf = PageFile.create(dir.resolve("t.db"), SCHEMA)) {
            pf.writePage(1, DataPage.empty(1).toBytes());
            DataPage p = DataPage.empty(1);
            p.insert(KIM);
            pf.writePage(1, p.toBytes());
            assertEquals(2, pf.pageCount());
            assertArrayEquals(p.toBytes(), pf.readPage(1));
        }
    }

    @Test
    void pageRangeChecks() throws IOException {
        try (PageFile pf = PageFile.create(dir.resolve("t.db"), SCHEMA)) {
            byte[] img = DataPage.empty(1).toBytes();
            assertEquals("데이터 페이지 1이 없습니다 (1 ~ 0)",
                    assertThrows(IllegalArgumentException.class, () -> pf.readPage(1)).getMessage());
            assertEquals("데이터 페이지 0이 없습니다 (1 ~ 0)",
                    assertThrows(IllegalArgumentException.class, () -> pf.readPage(0)).getMessage());
            assertEquals("데이터 페이지 0에 쓸 수 없습니다 (1 ~ 1)",
                    assertThrows(IllegalArgumentException.class, () -> pf.writePage(0, img)).getMessage());
            assertEquals("데이터 페이지 2에 쓸 수 없습니다 (1 ~ 1)",
                    assertThrows(IllegalArgumentException.class, () -> pf.writePage(2, img)).getMessage());
            assertEquals("페이지 이미지가 8191B입니다 (8192B여야 함)",
                    assertThrows(IllegalArgumentException.class, () -> pf.writePage(1, new byte[8191])).getMessage());
            assertEquals(1, pf.pageCount()); // 거부된 쓰기는 파일을 안 늘림
        }
    }

    @Test
    void corruptedPageOnDiskDetected() throws IOException {
        Path f = dir.resolve("t.db");
        try (PageFile pf = PageFile.create(f, SCHEMA)) {
            DataPage p = DataPage.empty(1);
            p.insert(KIM);
            pf.writePage(1, p.toBytes());
        }
        byte[] all = Files.readAllBytes(f);
        all[8192 + 8180] ^= 0x10; // 1번 페이지 행 안 한 바이트
        Files.write(f, all);
        try (PageFile pf = PageFile.open(f)) {
            byte[] raw = pf.readPage(1); // 원본 그대로는 읽힘 (WAL 복구용)
            var e = assertThrows(StorageException.class, () -> DataPage.fromBytes(1, raw));
            assertEquals(1, e.page());
            assertEquals("1번 페이지: checksum이 맞지 않습니다 (찢어졌거나 깨진 페이지)", e.getMessage());
        }
    }

    @Test
    void tornTailPageCountedAndRejected() throws IOException {
        Path f = dir.resolve("t.db");
        try (PageFile pf = PageFile.create(f, SCHEMA)) {
            DataPage p = DataPage.empty(1);
            p.insert(KIM);
            pf.writePage(1, p.toBytes());
        }
        byte[] all = Files.readAllBytes(f);
        Files.write(f, Arrays.copyOf(all, 8192 + 4096)); // 1번 페이지 앞 4KB만 남음
        try (PageFile pf = PageFile.open(f)) {
            assertEquals(2, pf.pageCount());
            byte[] raw = pf.readPage(1);
            assertEquals(0, raw[8191]); // 파일 끝 넘는 부분은 0
            assertEquals(1, assertThrows(StorageException.class, () -> DataPage.fromBytes(1, raw)).page());
            // 복구는 같은 자리에 온전한 이미지를 덮어쓸 수 있다
            pf.writePage(1, p(1));
            assertArrayEquals(KIM, DataPage.fromBytes(1, pf.readPage(1)).read(0));
            assertEquals(2, pf.pageCount());
        }
    }

    private static byte[] p(int id) {
        DataPage p = DataPage.empty(id);
        p.insert(KIM);
        return p.toBytes();
    }

    @Test
    void openRejectsBadMeta() throws IOException {
        Path f = dir.resolve("bad.db");
        Files.write(f, new byte[100]);
        var e = assertThrows(StorageException.class, () -> PageFile.open(f));
        assertEquals("0번 페이지: 파일이 100B라 메타 페이지가 없습니다", e.getMessage());

        Files.write(f, new byte[0]);
        assertEquals("0번 페이지: 파일이 0B라 메타 페이지가 없습니다",
                assertThrows(StorageException.class, () -> PageFile.open(f)).getMessage());

        byte[] meta = MetaPage.build(SCHEMA);
        meta[3] = '2';
        Files.write(f, meta);
        assertEquals("0번 페이지: 매직이 MDB1이 아닙니다 (미니 DBMS 파일이 아님)",
                assertThrows(StorageException.class, () -> PageFile.open(f)).getMessage());
    }

    @Test
    void openMissingFile() {
        assertThrows(java.nio.file.NoSuchFileException.class, () -> PageFile.open(dir.resolve("none.db")));
    }
}
