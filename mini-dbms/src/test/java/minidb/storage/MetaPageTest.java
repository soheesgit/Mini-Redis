package minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MetaPageTest {

    @Test
    void roundTrip() {
        byte[] schema = {0, 1, 2, 'i', 'd', 1, 0, 0, 0};
        byte[] page = MetaPage.build(schema);
        assertEquals(8192, page.length);
        assertArrayEquals(schema, Arrays.copyOf(MetaPage.schema(page), schema.length));
    }

    @Test
    void emptyAndMaxSchema() {
        assertArrayEquals(new byte[8184], MetaPage.schema(MetaPage.build(new byte[0])));
        byte[] max = new byte[8184];
        Arrays.fill(max, (byte) 7);
        assertArrayEquals(max, MetaPage.schema(MetaPage.build(max)));
        assertEquals("스키마가 8185B입니다 (최대 8184B)",
                assertThrows(IllegalArgumentException.class, () -> MetaPage.build(new byte[8185])).getMessage());
    }

    @Test
    void badVersion() {
        byte[] page = MetaPage.build(new byte[0]);
        ByteBuffer.wrap(page).putShort(4, (short) 2);
        var e = assertThrows(StorageException.class, () -> MetaPage.schema(page));
        assertEquals("0번 페이지: 형식 버전 2은 모릅니다 (1만 읽음)", e.getMessage());
        assertEquals(0, e.page());
    }

    @Test
    void badPageSize() {
        byte[] page = MetaPage.build(new byte[0]);
        ByteBuffer.wrap(page).putShort(6, (short) 4096);
        assertEquals("0번 페이지: 페이지 크기가 4096입니다 (8192여야 함)",
                assertThrows(StorageException.class, () -> MetaPage.schema(page)).getMessage());
    }

    @Test
    void notOurFile() {
        assertEquals("0번 페이지: 매직이 MDB1이 아닙니다 (미니 DBMS 파일이 아님)",
                assertThrows(StorageException.class, () -> MetaPage.schema(new byte[8192])).getMessage());
        assertThrows(StorageException.class, () -> MetaPage.schema(new byte[10]));
    }
}
