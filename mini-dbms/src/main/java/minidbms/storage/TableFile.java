package minidbms.storage;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 테이블 하나를 바이너리 파일 하나로 관리한다.
 * 레코드는 4바이트 본문 길이, 8바이트 ID, 각 TEXT의 4바이트 길이와 UTF-8 바이트 순서로 저장한다.
 * 정수 필드는 빅엔디언이며 파일은 한 프로세스가 사용한다고 가정한다.
 */
public final class TableFile {
    // 읽을 때도 같은 제한을 적용해 손상된 길이 필드로 거대한 버퍼를 만들지 않도록 한다.
    private static final int MAX_RECORD_BYTES = 1024 * 1024;

    public record Row(long id, List<String> textValues) {
        public Row {
            textValues = List.copyOf(textValues);
        }
    }

    private final Path path;
    private final int textColumnCount;
    // null이면 아직 마지막 ID를 모른다. 처음 삽입할 때 기존 파일을 읽어 ID를 이어받는다.
    private Long lastId;

    public TableFile(Path path, int textColumnCount) {
        this.path = path.toAbsolutePath();
        this.textColumnCount = textColumnCount;
    }

    public long append(List<String> textValues) throws IOException {
        if (lastId == null) {
            lastId = scan(row -> {});
        }
        if (lastId == Long.MAX_VALUE) {
            throw new IOException("No IDs left in " + path);
        }
        long id = lastId + 1;
        // 레코드 전체를 인코딩하고 크기를 검사한 뒤 파일을 연다.
        byte[] record = encode(id, textValues);

        Files.createDirectories(path.getParent());
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            long offset = file.length();
            file.seek(offset);
            try {
                file.write(record);
            } catch (IOException exception) {
                try {
                    // 쓰는 중 일부 바이트만 기록됐다면 기존 파일 길이로 되돌리기를 시도한다.
                    file.setLength(offset);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (IOException exception) {
            // 파일 상태가 확실하지 않으므로 다음 삽입 전에 디스크에서 마지막 ID를 다시 확인한다.
            lastId = null;
            throw exception;
        }

        lastId = id;
        return id;
    }

    public void forEachRow(Consumer<Row> visitor) throws IOException {
        scan(visitor);
    }

    // 레코드를 순서대로 전달하고 마지막 ID를 반환한다. 파일이 없으면 아직 삽입한 행이 없는 테이블이다.
    private long scan(Consumer<Row> visitor) throws IOException {
        long size;
        try {
            size = Files.size(path);
        } catch (NoSuchFileException exception) {
            return 0;
        }

        long lastReadId = 0;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (long offset = 0; offset < size;) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException exception) {
                    throw corrupt(offset, "incomplete record length");
                }
                if (length < Long.BYTES + textColumnCount * Integer.BYTES || length > MAX_RECORD_BYTES) {
                    throw corrupt(offset, "invalid record length");
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    throw corrupt(offset, "incomplete record");
                }

                Row row = decode(ByteBuffer.wrap(payload), decoder, offset);
                // 자동 ID는 1부터 연속이어야 하므로 중간 누락이나 순서가 어긋난 행도 손상으로 처리한다.
                if (row.id() != lastReadId + 1) {
                    throw corrupt(offset, "unexpected row ID " + row.id());
                }
                visitor.accept(row);
                lastReadId = row.id();
                // 저장된 길이는 본문만 세므로 길이 필드 4바이트를 더해야 다음 레코드가 시작된다.
                offset += Integer.BYTES + length;
            }
        }
        return lastReadId;
    }

    private byte[] encode(long id, List<String> textValues) throws IOException {
        if (textValues.size() != textColumnCount) {
            throw new IllegalArgumentException("Unexpected text column count");
        }
        var fields = new ArrayList<byte[]>(textColumnCount);
        long length = Long.BYTES;
        for (String value : textValues) {
            // 한글처럼 여러 바이트를 쓰는 문자가 있으므로 문자 수 대신 UTF-8 바이트 수를 기록한다.
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            length += Integer.BYTES + (long) bytes.length;
            if (length > MAX_RECORD_BYTES) {
                throw new IOException("Record exceeds " + MAX_RECORD_BYTES + " bytes");
            }
            fields.add(bytes);
        }

        var buffer = ByteBuffer.allocate(Integer.BYTES + (int) length);
        buffer.putInt((int) length).putLong(id);
        for (byte[] field : fields) {
            buffer.putInt(field.length).put(field);
        }
        return buffer.array();
    }

    private Row decode(ByteBuffer payload, CharsetDecoder decoder, long offset) throws IOException {
        long id = payload.getLong();
        var textValues = new ArrayList<String>(textColumnCount);
        for (int column = 0; column < textColumnCount; column++) {
            if (payload.remaining() < Integer.BYTES) {
                throw corrupt(offset, "missing text length");
            }
            int length = payload.getInt();
            if (length < 0 || length > payload.remaining()) {
                throw corrupt(offset, "invalid text length");
            }
            try {
                // 이 열의 범위만 잘라 디코딩한다. slice는 원본 위치를 옮기지 않으므로 아래에서 직접 이동한다.
                textValues.add(decoder.decode(payload.slice(payload.position(), length)).toString());
            } catch (CharacterCodingException exception) {
                throw corrupt(offset, "invalid UTF-8 text");
            }
            payload.position(payload.position() + length);
        }
        if (payload.hasRemaining()) {
            throw corrupt(offset, "unexpected bytes after the last column");
        }
        return new Row(id, textValues);
    }

    private IOException corrupt(long offset, String message) {
        return new IOException("Corrupt table file " + path + " at byte " + offset + ": " + message);
    }
}
