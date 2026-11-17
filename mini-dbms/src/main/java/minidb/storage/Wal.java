package minidb.storage;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

/**
 * 쓰기 전 로그 (설계 1.8): 테이블마다 <이름>.wal 하나. 커밋 하나 = 레코드 하나 = 바뀐 페이지들의 통째 이미지.
 * 레코드 = [len 4B][txid 8B][n 2B][(pageNo 4B + 이미지 8192B) × n][CRC32 4B], len = 레코드 전체 길이 (14 + n × 8196 + 4).
 * 데이터 파일에 쓰기 전에 이 레코드가 디스크에 있으면, 데이터 쓰기가 중간에 끊겨도 로그로 다시 쓸 수 있다.
 */
final class Wal implements AutoCloseable {

    static final int PAGE = DataPage.SIZE;
    private static final int ENTRY = 4 + PAGE;
    private static final int OVERHEAD = 14 + 4;

    private final FileChannel ch;
    private final Faults faults;
    /** 유효한 레코드가 끝나는 곳. 쓰다 끊긴 찌꺼기가 뒤에 있어도 다음 append가 그 위에 덮어쓴다 */
    private long end;

    Wal(Path path, Faults faults) throws IOException {
        this.ch = FileChannel.open(path, CREATE, READ, WRITE);
        this.faults = faults;
        this.end = ch.size();
    }

    /** 레코드를 쓴다: 한 번에 쓰지 않고 앞 절반 → 나머지 두 번에 쓴다 (사이에서 죽는 상황을 테스트하려고). fsync는 force()로 따로 */
    void append(long txid, SortedMap<Integer, byte[]> pages) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(OVERHEAD + pages.size() * ENTRY);
        b.putInt(b.capacity()).putLong(txid).putShort((short) pages.size());
        for (var e : pages.entrySet()) {
            if (e.getValue().length != PAGE) throw new IllegalArgumentException("페이지 이미지가 8192B가 아님");
            b.putInt(e.getKey()).put(e.getValue());
        }
        CRC32 crc = new CRC32();
        crc.update(b.array(), 0, b.position());
        b.putInt((int) crc.getValue());
        int half = b.capacity() / 2;
        write(b.array(), 0, half, end);
        faults.at("wal.half", 0);
        write(b.array(), half, b.capacity() - half, end + half);
        faults.at("wal.write", 0);
        end += b.capacity();
    }

    private void write(byte[] a, int off, int len, long pos) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(a, off, len);
        while (b.hasRemaining()) pos += ch.write(b, pos);
    }

    void force() throws IOException {
        ch.force(false);
    }

    long size() {
        return end;
    }

    void truncate(long size) throws IOException {
        ch.truncate(size);
        end = Math.min(end, size);
    }

    /** 체크포인트 뒤 비우기 */
    void reset() throws IOException {
        truncate(0);
        force();
    }

    /**
     * 처음부터 유효한 레코드를 차례로 넘긴다. 처음 만난 무효 레코드(찢어진 꼬리)에서 멈춘다.
     * len은 남은 길이로 먼저 검사한 뒤에야 버퍼를 만든다: 깨진 len(0x7FFFFFFF 등)으로 메모리를 잡지 않으려고
     */
    void replay(BiConsumer<Long, SortedMap<Integer, byte[]>> sink) throws IOException {
        long size = ch.size(), pos = 0;
        ByteBuffer head = ByteBuffer.allocate(4);
        while (size - pos >= 4) {
            head.clear();
            readFully(head, pos);
            int len = head.getInt(0);
            if (len < OVERHEAD || (len - OVERHEAD) % ENTRY != 0 || len > size - pos) break;
            ByteBuffer rec = ByteBuffer.allocate(len);
            readFully(rec, pos);
            CRC32 crc = new CRC32();
            crc.update(rec.array(), 0, len - 4);
            int n = (len - OVERHEAD) / ENTRY;
            if (rec.getInt(len - 4) != (int) crc.getValue() || (rec.getShort(12) & 0xFFFF) != n) break;
            SortedMap<Integer, byte[]> pages = new TreeMap<>();
            boolean ok = true;
            rec.position(14);
            for (int i = 0; i < n; i++) {
                int no = rec.getInt();
                byte[] img = new byte[PAGE];
                rec.get(img);
                ok &= no >= 1 && (pages.isEmpty() || no > pages.lastKey());
                pages.put(no, img);
            }
            if (!ok) break;
            sink.accept(rec.getLong(4), pages);
            pos += len;
        }
    }

    private void readFully(ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            if (ch.read(b, pos + b.position()) < 0) throw new IOException("로그가 읽는 도중 줄어듦");
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
