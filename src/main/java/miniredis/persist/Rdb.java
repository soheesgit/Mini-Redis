package miniredis.persist;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * RDB 형식
 * "MINIRDB1"
 * 엔트리:   0x01 [4B 키 길이][키][4B 값 길이][값][8B 만료 유닉스ms, 없으면 -1]
 * 변경 로그: 0x02 [AOF 레코드]   (BGSAVE 진행 중 생긴 변경)
 * 끝:       0xFF [4B CRC32(앞의 전체)]
 */
public final class Rdb {
    static final byte[] MAGIC = "MINIRDB1".getBytes(StandardCharsets.US_ASCII);
    private static final int ENTRY = 0x01, CHANGE = 0x02, END = 0xFF;

    private Rdb() { }

    /** 조금씩 나눠 쓰는 작성기. 임시 파일에 쓰고, 끝나면 호출한 쪽이 원자적으로 교체한다. */
    public static final class Writer implements AutoCloseable {
        private final CRC32 crc = new CRC32();
        private final DataOutputStream out;

        public Writer(Path tmp) throws IOException {
            out = new DataOutputStream(new CheckedOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)), crc));
            out.write(MAGIC);
        }

        public void entry(String key, byte[] value, long expireUnixMs) throws IOException {
            byte[] k = key.getBytes(StandardCharsets.UTF_8);
            out.writeByte(ENTRY);
            out.writeInt(k.length);
            out.write(k);
            out.writeInt(value.length);
            out.write(value);
            out.writeLong(expireUnixMs);
        }

        public void change(byte[] encodedRecord) throws IOException {
            out.writeByte(CHANGE);
            out.write(encodedRecord);
        }

        public void finish() throws IOException {
            out.writeByte(END);
            out.flush();
            int sum = (int) crc.getValue(); // 0xFF까지 포함한 앞부분 전체의 CRC
            out.writeInt(sum);
            out.close();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    /** 엔트리를 SET 명령으로 넘기고, 그 다음 변경 로그를 순서대로 넘긴다. 손상이면 IOException. */
    public static void load(byte[] f, Consumer<byte[][]> apply) throws IOException {
        if (f.length < MAGIC.length + 5) throw new IOException("RDB가 너무 짧다");
        for (int i = 0; i < MAGIC.length; i++) if (f[i] != MAGIC[i]) throw new IOException("RDB 머리말이 다르다");
        int crcAt = f.length - 4;
        if (f[crcAt - 1] != (byte) END) throw new IOException("RDB 끝 표시가 없다 (잘림)");
        if ((int) Records.crc(f, 0, crcAt) != ByteBuffer.wrap(f, crcAt, 4).getInt()) throw new IOException("RDB CRC 불일치");

        ByteBuffer b = ByteBuffer.wrap(f, MAGIC.length, crcAt - 1 - MAGIC.length);
        List<byte[][]> changes = new ArrayList<>();
        try {
            while (b.hasRemaining()) {
                int type = b.get() & 0xFF;
                if (type == ENTRY) {
                    byte[] k = bytes(b, b.getInt());
                    byte[] v = bytes(b, b.getInt());
                    long exp = b.getLong();
                    apply.accept(exp == -1
                        ? new byte[][] {"SET".getBytes(), k, v}
                        : new byte[][] {"SET".getBytes(), k, v, "PXAT".getBytes(), Long.toString(exp).getBytes()});
                } else if (type == CHANGE) {
                    int n = b.getInt();
                    byte[] rec = new byte[4 + n + 4];
                    b.position(b.position() - 4);
                    b.get(rec);
                    Records.Parsed p = Records.parse(rec);
                    if (p.problem() != Records.Problem.NONE) throw new IOException("RDB 변경 로그 손상");
                    changes.addAll(p.records());
                } else {
                    throw new IOException("RDB 알 수 없는 구간: " + type);
                }
            }
        } catch (BufferUnderflowException | IllegalArgumentException | NegativeArraySizeException e) {
            throw new IOException("RDB 구조 손상", e);
        }
        changes.forEach(apply); // 엔트리를 모두 넣은 뒤에 변경을 적용해야 최종 상태가 맞다
    }

    private static byte[] bytes(ByteBuffer b, int n) {
        byte[] a = new byte[n];
        b.get(a);
        return a;
    }
}
