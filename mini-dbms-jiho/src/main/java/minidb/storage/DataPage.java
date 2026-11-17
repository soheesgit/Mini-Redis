package minidb.storage;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * 데이터 페이지 8192B (설계 1.5). 헤더 16B, 슬롯 4B는 앞(16)부터, 행은 뒤(8192)부터 채운다.
 * 행 내용은 모른다: byte[] 그대로 넣고 꺼낸다 (행 인코딩은 minidb.type).
 *
 * <pre>
 * 헤더: 0 page_id 4B | 4 slot_count 2B | 6 free_start 2B | 8 free_end 2B | 10 flags 2B | 12 checksum 4B
 * 슬롯 i (위치 16 + i*4): offset 2B (0 = 삭제됨) | length 2B
 * </pre>
 *
 * 바이트 순서는 빅엔디언 (ByteBuffer 기본값). 2B 필드는 부호 없는 값으로 읽는다 (8192 = 0x2000).
 */
public final class DataPage {

    public static final int SIZE = 8192;
    public static final int HEADER = 16;
    public static final int SLOT = 4;
    /** 빈 페이지에 슬롯 하나와 같이 들어가는 최대 행 = 8192 − 16 − 4 */
    public static final int MAX_ROW = SIZE - HEADER - SLOT;

    private static final int SLOT_COUNT = 4, FREE_START = 6, FREE_END = 8, CHECKSUM = 12;

    private final ByteBuffer buf;

    private DataPage(byte[] image) {
        this.buf = ByteBuffer.wrap(image);
    }

    /** 새 빈 페이지. 0번은 메타 페이지라 데이터 페이지는 1부터 */
    public static DataPage empty(int pageId) {
        if (pageId < 1) {
            throw new IllegalArgumentException("데이터 페이지 번호는 1 이상이어야 합니다: " + pageId);
        }
        DataPage p = new DataPage(new byte[SIZE]);
        p.buf.putInt(0, pageId);
        p.putU16(FREE_START, HEADER);
        p.putU16(FREE_END, SIZE);
        return p;
    }

    /** 파일이나 로그에서 꺼낸 이미지를 검사한 뒤 복사본으로 연다 */
    public static DataPage fromBytes(int pageId, byte[] image) {
        verify(pageId, image);
        return new DataPage(image.clone());
    }

    /** checksum과 page_id를 확인한다. 틀리면 StorageException (페이지 번호 포함) */
    public static void verify(int pageId, byte[] image) {
        if (image.length != SIZE) {
            throw new StorageException(pageId, "페이지 크기가 " + image.length + "B입니다 (8192B여야 함)");
        }
        ByteBuffer b = ByteBuffer.wrap(image);
        if (b.getInt(CHECKSUM) != crc(image)) {
            throw new StorageException(pageId, "checksum이 맞지 않습니다 (찢어졌거나 깨진 페이지)");
        }
        if (b.getInt(0) != pageId) {
            throw new StorageException(pageId, "page_id가 " + b.getInt(0) + "입니다 (엉뚱한 위치를 읽음)");
        }
    }

    /** checksum을 다시 계산해 넣은 8192B 복사본. 파일·로그에는 이것을 쓴다 */
    public byte[] toBytes() {
        buf.putInt(CHECKSUM, crc(buf.array()));
        return buf.array().clone();
    }

    public int pageId() {
        return buf.getInt(0);
    }

    /** 삭제 표시된 슬롯도 센다 (슬롯 번호는 줄지 않음) */
    public int slotCount() {
        return u16(SLOT_COUNT);
    }

    /** 이어진 빈 공간 = free_end − free_start */
    public int freeSpace() {
        return u16(FREE_END) - u16(FREE_START);
    }

    /**
     * 행을 free_end 앞에, 슬롯을 free_start에 붙인다. 삭제된 슬롯은 다시 쓰지 않는다 (설계 1.10).
     * @return 새 슬롯 번호. 이어진 빈 공간이 행 + 슬롯 4B보다 작으면 -1 (호출자가 새 페이지로)
     */
    public int insert(byte[] row) {
        checkRow(row);
        if (freeSpace() < row.length + SLOT) {
            return -1;
        }
        int slot = slotCount();
        int off = u16(FREE_END) - row.length;
        buf.put(off, row);
        putU16(FREE_END, off);
        setSlot(slot, off, row.length);
        putU16(FREE_START, u16(FREE_START) + SLOT);
        putU16(SLOT_COUNT, slot + 1);
        return slot;
    }

    /** 행 바이트 복사본. 삭제된 슬롯이면 null */
    public byte[] read(int slot) {
        checkSlot(slot);
        int off = offset(slot);
        if (off == 0) {
            return null;
        }
        byte[] row = new byte[length(slot)];
        buf.get(off, row);
        return row;
    }

    /** 삭제 표시: offset = 0. 슬롯 번호와 행 바이트는 그대로 둔다 (죽은 공간) */
    public void delete(int slot) {
        checkLive(slot);
        putU16(slotPos(slot), 0);
    }

    /**
     * 슬롯 번호를 유지한 채 행을 바꾼다 (설계 1.9 ④).
     * 1) 새 길이 ≤ 예전 길이: 제자리 덮어쓰기
     * 2) 이어진 빈 공간 ≥ 새 길이: free_end 앞에 새로 쓰고 offset만 바꿈
     * 3) 죽은 공간까지 합치면 충분: 이 행을 뺀 나머지를 뒤로 당겨 모은 뒤 2)
     * @return 이 페이지에 못 넣으면 false (페이지는 그대로. 호출자가 다른 페이지로 옮김)
     */
    public boolean update(int slot, byte[] row) {
        checkRow(row);
        checkLive(slot);
        if (row.length <= length(slot)) {
            buf.put(offset(slot), row);
            putU16(slotPos(slot) + 2, row.length);
            return true;
        }
        if (freeSpace() < row.length) {
            int live = 0;
            for (int i = 0; i < slotCount(); i++) {
                if (i != slot && offset(i) != 0) {
                    live += length(i);
                }
            }
            if (SIZE - u16(FREE_START) - live < row.length) {
                return false;
            }
            putU16(slotPos(slot), 0); // 이 행의 예전 바이트도 버릴 공간으로 친다
            compact();
        }
        int off = u16(FREE_END) - row.length;
        buf.put(off, row);
        putU16(FREE_END, off);
        setSlot(slot, off, row.length);
        return true;
    }

    /** 살아 있는 행을 페이지 끝으로 다시 붙이고 offset만 고친다. 슬롯 번호는 그대로 */
    private void compact() {
        byte[] old = buf.array().clone();
        int end = SIZE;
        for (int i = 0; i < slotCount(); i++) {
            int off = offset(i);
            if (off != 0) {
                end -= length(i);
                System.arraycopy(old, off, buf.array(), end, length(i));
                putU16(slotPos(i), end);
            }
        }
        putU16(FREE_END, end);
    }

    private static int crc(byte[] image) {
        CRC32 c = new CRC32();
        c.update(image, 0, CHECKSUM);
        c.update(new byte[4]); // checksum 칸은 0으로 두고 계산
        c.update(image, CHECKSUM + 4, SIZE - CHECKSUM - 4);
        return (int) c.getValue();
    }

    private void checkRow(byte[] row) {
        if (row == null || row.length == 0) {
            throw new IllegalArgumentException(pageId() + "번 페이지: 빈 행은 넣을 수 없습니다");
        }
        if (row.length > MAX_ROW) {
            throw new IllegalArgumentException(pageId() + "번 페이지: 행이 " + row.length
                    + "B입니다 (최대 " + MAX_ROW + "B)");
        }
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slotCount()) {
            throw new IllegalArgumentException(pageId() + "번 페이지: 슬롯 " + slot
                    + "이 없습니다 (슬롯 수 " + slotCount() + ")");
        }
    }

    private void checkLive(int slot) {
        checkSlot(slot);
        if (offset(slot) == 0) {
            throw new IllegalStateException(pageId() + "번 페이지: 슬롯 " + slot + "은 이미 삭제됐습니다");
        }
    }

    private static int slotPos(int slot) {
        return HEADER + slot * SLOT;
    }

    private int offset(int slot) {
        return u16(slotPos(slot));
    }

    private int length(int slot) {
        return u16(slotPos(slot) + 2);
    }

    private void setSlot(int slot, int off, int len) {
        putU16(slotPos(slot), off);
        putU16(slotPos(slot) + 2, len);
    }

    private int u16(int pos) {
        return buf.getShort(pos) & 0xFFFF;
    }

    private void putU16(int pos, int v) {
        buf.putShort(pos, (short) v);
    }
}
