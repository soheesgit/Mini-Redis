package minidb.storage;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 0번 메타 페이지 (설계 1.4): 0 매직 "MDB1" 4B | 4 형식 버전 2B | 6 페이지 크기 2B | 8~ 스키마 바이트.
 * 스키마 바이트(칸 개수 2B + 칸마다 …)의 해석은 minidb.type 몫이라 여기서는 byte[]로만 다룬다.
 */
public final class MetaPage {

    public static final byte[] MAGIC = "MDB1".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int SCHEMA_START = 8;
    public static final int MAX_SCHEMA = DataPage.SIZE - SCHEMA_START;

    private MetaPage() {}

    /** 스키마 바이트를 담은 8192B 메타 페이지. 남는 자리는 0 */
    public static byte[] build(byte[] schema) {
        if (schema.length > MAX_SCHEMA) {
            throw new IllegalArgumentException("스키마가 " + schema.length + "B입니다 (최대 " + MAX_SCHEMA + "B)");
        }
        ByteBuffer b = ByteBuffer.allocate(DataPage.SIZE);
        b.put(MAGIC).putShort((short) VERSION).putShort((short) DataPage.SIZE).put(schema);
        return b.array();
    }

    /**
     * 매직·버전·페이지 크기를 확인하고 8번 위치부터 페이지 끝까지(8184B)를 돌려준다.
     * 스키마 길이를 따로 적지 않으므로(설계 1.4 그대로) 뒤에 0이 붙어 있다. 칸 개수로 끝을 안다.
     */
    public static byte[] schema(byte[] page) {
        ByteBuffer b = ByteBuffer.wrap(page);
        if (page.length != DataPage.SIZE || !Arrays.equals(page, 0, 4, MAGIC, 0, 4)) {
            throw new StorageException(0, "매직이 MDB1이 아닙니다 (미니 DBMS 파일이 아님)");
        }
        int version = b.getShort(4) & 0xFFFF;
        if (version != VERSION) {
            throw new StorageException(0, "형식 버전 " + version + "은 모릅니다 (" + VERSION + "만 읽음)");
        }
        int pageSize = b.getShort(6) & 0xFFFF;
        if (pageSize != DataPage.SIZE) {
            throw new StorageException(0, "페이지 크기가 " + pageSize + "입니다 (" + DataPage.SIZE + "여야 함)");
        }
        return Arrays.copyOfRange(page, SCHEMA_START, DataPage.SIZE);
    }
}
