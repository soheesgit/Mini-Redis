package minidb.engine;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

/** 캐시 전용 버전 형식. Java 객체 역직렬화 없이 지원 SQL 타입만 읽는다. */
final class ResultCodec {
    private static final int MAGIC = 0x4d444232;
    private ResultCodec() {}

    static byte[] encode(Result result) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(result.columns().size());
            for (String column : result.columns()) text(out, column);
            out.writeInt(result.rows().size());
            for (Object[] row : result.rows()) {
                for (Object value : row) {
                    switch (value) {
                        case null -> out.writeByte(0);
                        case Integer v -> { out.writeByte(1); out.writeInt(v); }
                        case Long v -> { out.writeByte(2); out.writeLong(v); }
                        case Double v -> { out.writeByte(3); out.writeDouble(v); }
                        case BigDecimal v -> { out.writeByte(4); text(out, v.toString()); }
                        case Boolean v -> { out.writeByte(5); out.writeByte(v ? 1 : 0); }
                        case String v -> { out.writeByte(6); text(out, v); }
                        case LocalDate v -> { out.writeByte(7); text(out, v.toString()); }
                        case LocalDateTime v -> { out.writeByte(8); text(out, v.toString()); }
                        case Instant v -> { out.writeByte(9); text(out, v.toString()); }
                        default -> throw new IOException("Unsupported cached type: " + value.getClass());
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    static Result decode(byte[] bytes) throws IOException {
        if (bytes.length > QueryCache.MAX_VALUE_BYTES) throw corrupt();
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw corrupt();
            int cols = count(in);
            if (cols == 0 || cols > in.available() / 4) throw corrupt();
            List<String> columns = new ArrayList<>(cols);
            for (int i = 0; i < cols; i++) columns.add(text(in));
            int count = count(in);
            if ((long) count * cols > in.available()) throw corrupt();
            List<Object[]> rows = new ArrayList<>(count);
            for (int r = 0; r < count; r++) {
                Object[] row = new Object[cols];
                for (int c = 0; c < cols; c++) {
                    row[c] = switch (in.readUnsignedByte()) {
                        case 0 -> null;
                        case 1 -> in.readInt();
                        case 2 -> in.readLong();
                        case 3 -> in.readDouble();
                        case 4 -> new BigDecimal(text(in));
                        case 5 -> { int b = in.readUnsignedByte(); if (b > 1) throw corrupt(); yield b == 1; }
                        case 6 -> text(in);
                        case 7 -> LocalDate.parse(text(in));
                        case 8 -> LocalDateTime.parse(text(in));
                        case 9 -> Instant.parse(text(in));
                        default -> throw corrupt();
                    };
                }
                rows.add(row);
            }
            if (in.available() != 0) throw corrupt();
            return new Result(List.copyOf(columns), List.copyOf(rows), count, null);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IOException("Corrupt cached result", e);
        }
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > in.available()) throw corrupt();
        return n;
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String text(DataInputStream in) throws IOException {
        int size = count(in);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(size))).toString();
    }

    private static IOException corrupt() { return new IOException("Corrupt cached result"); }
}
