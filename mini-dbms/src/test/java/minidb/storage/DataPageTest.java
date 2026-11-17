package minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DataPageTest {

    /** 설계 1.6 예시 users(id INT, name VARCHAR(20), age INT)의 (1, 'kim', 20) */
    static final byte[] KIM = {0, 0, 0, 0, 1, 0, 3, 0x6B, 0x69, 0x6D, 0, 0, 0, 0x14};

    static byte[] row(int len, int fill) {
        byte[] r = new byte[len];
        Arrays.fill(r, (byte) fill);
        return r;
    }

    private static int u16(byte[] img, int pos) {
        return ByteBuffer.wrap(img).getShort(pos) & 0xFFFF;
    }

    @Test
    void emptyPageHeader() {
        byte[] img = DataPage.empty(3).toBytes();
        assertEquals(8192, img.length);
        assertEquals(3, ByteBuffer.wrap(img).getInt(0));
        assertEquals(0, u16(img, 4));
        assertEquals(16, u16(img, 6));
        assertEquals(8192, u16(img, 8));
        assertEquals(0, u16(img, 10));
        assertEquals(8176, DataPage.empty(1).freeSpace());
    }

    @Test
    void pageZeroIsMeta() {
        var e = assertThrows(IllegalArgumentException.class, () -> DataPage.empty(0));
        assertEquals("데이터 페이지 번호는 1 이상이어야 합니다: 0", e.getMessage());
    }

    @Test
    void designExampleKim() {
        assertEquals(14, KIM.length);
        DataPage p = DataPage.empty(1);
        assertEquals(0, p.insert(KIM));
        byte[] img = p.toBytes();
        assertEquals(1, u16(img, 4)); // slot_count
        assertEquals(20, u16(img, 6)); // free_start 16 → 20
        assertEquals(8178, u16(img, 8)); // free_end 8192 → 8178
        assertEquals(8178, u16(img, 16)); // 슬롯0 offset (위치 16~19)
        assertEquals(14, u16(img, 18)); // 슬롯0 length
        assertArrayEquals(KIM, Arrays.copyOfRange(img, 8178, 8192));
        assertEquals(8158, p.freeSpace());
        assertArrayEquals(KIM, p.read(0));
    }

    @Test
    void slotsFromFrontRowsFromBack() {
        DataPage p = DataPage.empty(1);
        assertEquals(0, p.insert(row(10, 1)));
        assertEquals(1, p.insert(row(20, 2)));
        byte[] img = p.toBytes();
        assertEquals(8182, u16(img, 16));
        assertEquals(8162, u16(img, 20)); // 슬롯1 위치 = 16 + 1×4
        assertEquals(20, u16(img, 22));
        assertEquals(24, u16(img, 6));
        assertEquals(8162, u16(img, 8));
        assertArrayEquals(row(20, 2), p.read(1));
    }

    @Test
    void maxRowFillsEmptyPageExactly() {
        DataPage p = DataPage.empty(1);
        assertEquals(8172, DataPage.MAX_ROW);
        assertEquals(0, p.insert(row(8172, 7)));
        assertEquals(0, p.freeSpace());
        assertEquals(-1, p.insert(row(1, 1)));
        assertEquals(1, p.slotCount());
        assertArrayEquals(row(8172, 7), p.read(0));
    }

    @Test
    void insertNeedsRowPlusSlot() {
        DataPage p = DataPage.empty(1);
        p.insert(row(8172 - 10, 1)); // 남은 공간 10
        assertEquals(10, p.freeSpace());
        assertEquals(-1, p.insert(row(7, 2))); // 7 + 4 > 10
        assertEquals(1, p.insert(row(6, 2))); // 6 + 4 = 10
        assertEquals(0, p.freeSpace());
    }

    @Test
    void rejectsBadRows() {
        DataPage p = DataPage.empty(2);
        assertEquals("2번 페이지: 행이 8173B입니다 (최대 8172B)",
                assertThrows(IllegalArgumentException.class, () -> p.insert(row(8173, 0))).getMessage());
        assertEquals("2번 페이지: 빈 행은 넣을 수 없습니다",
                assertThrows(IllegalArgumentException.class, () -> p.insert(new byte[0])).getMessage());
        assertEquals("2번 페이지: 빈 행은 넣을 수 없습니다",
                assertThrows(IllegalArgumentException.class, () -> p.insert(null)).getMessage());
        assertEquals(0, p.slotCount());
    }

    @Test
    void missingSlot() {
        DataPage p = DataPage.empty(4);
        p.insert(KIM);
        assertEquals("4번 페이지: 슬롯 1이 없습니다 (슬롯 수 1)",
                assertThrows(IllegalArgumentException.class, () -> p.read(1)).getMessage());
        assertEquals("4번 페이지: 슬롯 -1이 없습니다 (슬롯 수 1)",
                assertThrows(IllegalArgumentException.class, () -> p.delete(-1)).getMessage());
    }

    @Test
    void deleteMarksOffsetZeroAndKeepsSlotNumbers() {
        DataPage p = DataPage.empty(1);
        p.insert(row(10, 1));
        p.insert(row(10, 2));
        p.insert(row(10, 3));
        p.delete(1);
        byte[] img = p.toBytes();
        assertEquals(0, u16(img, 20)); // 슬롯1 offset = 0
        assertEquals(3, p.slotCount());
        assertNull(p.read(1));
        assertArrayEquals(row(10, 1), p.read(0));
        assertArrayEquals(row(10, 3), p.read(2));
        assertEquals(3, p.insert(row(10, 4))); // 삭제된 1번을 다시 쓰지 않음
        assertEquals("1번 페이지: 슬롯 1은 이미 삭제됐습니다",
                assertThrows(IllegalStateException.class, () -> p.delete(1)).getMessage());
        assertEquals("1번 페이지: 슬롯 1은 이미 삭제됐습니다",
                assertThrows(IllegalStateException.class, () -> p.update(1, KIM)).getMessage());
    }

    @Test
    void updateShorterOrSameInPlace() {
        DataPage p = DataPage.empty(1);
        p.insert(row(20, 1));
        int freeBefore = p.freeSpace();
        assertTrue(p.update(0, row(20, 2)));
        assertTrue(p.update(0, row(5, 3)));
        byte[] img = p.toBytes();
        assertEquals(8172, u16(img, 16)); // offset 그대로
        assertEquals(5, u16(img, 18));
        assertEquals(freeBefore, p.freeSpace()); // 줄어든 15B는 죽은 공간
        assertArrayEquals(row(5, 3), p.read(0));
    }

    @Test
    void updateLongerMovesWithinPage() {
        DataPage p = DataPage.empty(1);
        p.insert(row(10, 1));
        p.insert(row(10, 2));
        assertTrue(p.update(0, row(30, 9)));
        byte[] img = p.toBytes();
        assertEquals(8192 - 20 - 30, u16(img, 16)); // 새 자리 = 예전 free_end − 30
        assertEquals(30, u16(img, 18));
        assertEquals(2, p.slotCount()); // 슬롯 늘지 않음
        assertEquals(24, u16(img, 6));
        assertArrayEquals(row(30, 9), p.read(0));
        assertArrayEquals(row(10, 2), p.read(1));
    }

    @Test
    void updateCompactsWhenOnlyDeadSpaceIsEnough() {
        DataPage p = DataPage.empty(1);
        p.insert(row(4000, 1)); // 슬롯0
        p.insert(row(4000, 2)); // 슬롯1
        p.insert(row(100, 3)); // 슬롯2. 남은 이어진 공간 8176 − 8112 = 64
        p.delete(1); // 죽은 공간 4000
        assertEquals(64, p.freeSpace());
        assertTrue(p.update(2, row(3000, 4)));
        assertArrayEquals(row(4000, 1), p.read(0));
        assertNull(p.read(1));
        assertArrayEquals(row(3000, 4), p.read(2));
        byte[] img = p.toBytes();
        assertEquals(0, u16(img, 20)); // 삭제 표시 유지
        assertEquals(4192, u16(img, 16)); // 슬롯0은 끝으로 당겨짐
        assertEquals(1192, u16(img, 24)); // 슬롯2 새 자리
        assertEquals(1192 - 28, p.freeSpace());
        assertEquals(3, p.slotCount());
    }

    @Test
    void updateCountsOwnOldBytesAsReusable() {
        DataPage p = DataPage.empty(1);
        p.insert(row(8172 - 4 - 100, 1)); // 슬롯0
        p.insert(row(100, 2)); // 슬롯1, 남은 공간 0
        assertEquals(0, p.freeSpace());
        p.delete(0);
        // 다른 살아 있는 행 없음 → 쓸 수 있는 공간 = 8192 − 24 = 8168
        assertTrue(p.update(1, row(8168, 3)));
        assertArrayEquals(row(8168, 3), p.read(1));
        assertEquals(0, p.freeSpace());
    }

    @Test
    void updateTooBigLeavesPageUntouched() {
        DataPage p = DataPage.empty(1);
        p.insert(row(4000, 1));
        p.insert(row(4000, 2));
        p.insert(row(100, 3));
        byte[] before = p.toBytes();
        // 다른 살아 있는 행 8000B를 빼면 쓸 수 있는 공간 8192 − 28 − 8000 = 164
        assertFalse(p.update(2, row(165, 4)));
        assertArrayEquals(before, p.toBytes());
        assertTrue(p.update(2, row(164, 4)));
        assertArrayEquals(row(164, 4), p.read(2));
        assertArrayEquals(row(4000, 1), p.read(0));
    }

    @Test
    void rejectsTooLongUpdate() {
        DataPage p = DataPage.empty(1);
        p.insert(KIM);
        assertEquals("1번 페이지: 행이 8173B입니다 (최대 8172B)",
                assertThrows(IllegalArgumentException.class, () -> p.update(0, row(8173, 0))).getMessage());
    }

    @Test
    void roundTripThroughBytes() {
        DataPage p = DataPage.empty(5);
        p.insert(KIM);
        p.insert(row(3, 1));
        p.delete(1);
        DataPage q = DataPage.fromBytes(5, p.toBytes());
        assertEquals(5, q.pageId());
        assertEquals(2, q.slotCount());
        assertArrayEquals(KIM, q.read(0));
        assertNull(q.read(1));
        assertArrayEquals(p.toBytes(), q.toBytes());
    }

    @Test
    void checksumDetectsAnyFlippedByte() {
        byte[] img = DataPage.empty(7).toBytes();
        for (int pos : new int[] {0, 4, 12, 15, 4096, 8191}) {
            byte[] bad = img.clone();
            bad[pos] ^= 1;
            var e = assertThrows(StorageException.class, () -> DataPage.verify(7, bad));
            assertEquals("7번 페이지: checksum이 맞지 않습니다 (찢어졌거나 깨진 페이지)", e.getMessage());
            assertEquals(7, e.page());
        }
        DataPage.verify(7, img);
    }

    @Test
    void tornPageHalfNewHalfOld() {
        DataPage p = DataPage.empty(1);
        p.insert(KIM);
        byte[] oldImg = p.toBytes();
        p.update(0, row(14, 9));
        byte[] torn = p.toBytes();
        System.arraycopy(oldImg, 4096, torn, 4096, 4096); // 뒤 4KB만 옛 내용
        assertThrows(StorageException.class, () -> DataPage.fromBytes(1, torn));
    }

    @Test
    void wrongPageIdAndSize() {
        byte[] img = DataPage.empty(2).toBytes();
        var e = assertThrows(StorageException.class, () -> DataPage.verify(3, img));
        assertEquals("3번 페이지: page_id가 2입니다 (엉뚱한 위치를 읽음)", e.getMessage());
        assertEquals("1번 페이지: 페이지 크기가 100B입니다 (8192B여야 함)",
                assertThrows(StorageException.class, () -> DataPage.verify(1, new byte[100])).getMessage());
        assertThrows(StorageException.class, () -> DataPage.verify(1, new byte[8192])); // 전부 0
    }

    @Test
    void fromBytesCopies() {
        byte[] img = DataPage.empty(1).toBytes();
        DataPage p = DataPage.fromBytes(1, img);
        p.insert(KIM);
        assertEquals(0, u16(img, 4)); // 원본 배열은 안 바뀜
        byte[] out = p.toBytes();
        out[100] = 1;
        assertEquals(0, p.toBytes()[100]); // 꺼낸 배열을 바꿔도 페이지는 그대로
    }
}
