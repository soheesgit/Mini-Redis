package miniredis.persist;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * AOF 레코드 형식: [4B 본문 길이 N][본문 N바이트][4B CRC32(본문)]
 * 본문 = [1B 인자 수][인자마다 4B 길이 + 바이트]. 정수는 빅엔디언 (ByteBuffer 기본값).
 * 길이가 앞에 있어 레코드 경계를 알 수 있고, CRC로 "잘린 꼬리"와 "중간 손상"을 구분한다.
 */
public final class Records {
    private Records() { }

    public static byte[] encode(byte[][] argv) {
        if (argv.length > 255) throw new IllegalArgumentException("인자는 최대 255개");
        int body = 1;
        for (byte[] a : argv) body += 4 + a.length;
        ByteBuffer b = ByteBuffer.allocate(4 + body + 4);
        b.putInt(body);
        b.put((byte) argv.length);
        for (byte[] a : argv) b.putInt(a.length).put(a);
        b.putInt((int) crc(b.array(), 4, body));
        return b.array();
    }

    static long crc(byte[] a, int off, int len) {
        CRC32 c = new CRC32();
        c.update(a, off, len);
        return c.getValue();
    }

    public enum Problem { NONE, TRUNCATED_TAIL, CORRUPT }

    /** goodEnd: 마지막으로 온전한 레코드가 끝나는 위치. 잘린 꼬리면 여기까지 자른다. */
    public record Parsed(List<byte[][]> records, int goodEnd, Problem problem) { }

    public static Parsed parse(byte[] f) {
        List<byte[][]> out = new ArrayList<>();
        ByteBuffer b = ByteBuffer.wrap(f);
        while (b.hasRemaining()) {
            int start = b.position();
            if (b.remaining() < 4) return new Parsed(out, start, Problem.TRUNCATED_TAIL);
            int n = b.getInt();
            if (n < 1) return new Parsed(out, start, Problem.CORRUPT);
            if (b.remaining() < (long) n + 4) return new Parsed(out, start, Problem.TRUNCATED_TAIL); // 길이 부족
            int bodyAt = b.position();
            b.position(bodyAt + n);
            int stored = b.getInt();
            if (stored != (int) crc(f, bodyAt, n)) {
                // 파일 맨 끝 레코드의 CRC 불일치 = 쓰다가 죽은 꼬리. 그 밖의 위치면 손상
                return new Parsed(out, start, b.hasRemaining() ? Problem.CORRUPT : Problem.TRUNCATED_TAIL);
            }
            byte[][] argv = decodeBody(ByteBuffer.wrap(f, bodyAt, n));
            if (argv == null) return new Parsed(out, start, Problem.CORRUPT);
            out.add(argv);
        }
        return new Parsed(out, b.position(), Problem.NONE);
    }

    /** CRC는 맞는데 본문 구조가 틀리면 null (손상으로 취급). */
    static byte[][] decodeBody(ByteBuffer b) {
        int end = b.limit();
        int argc = b.get() & 0xFF;
        byte[][] argv = new byte[argc][];
        for (int i = 0; i < argc; i++) {
            if (end - b.position() < 4) return null;
            int len = b.getInt();
            if (len < 0 || end - b.position() < len) return null;
            argv[i] = new byte[len];
            b.get(argv[i]);
        }
        return b.position() == end ? argv : null;
    }
}
