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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 테이블 하나를 바이너리 파일 하나로 관리한다.
 * 삽입은 기존 양수 ID 형식을 사용한다. ID 자리가 0이면 그 뒤에 수정·삭제 연산과 대상 ID가 온다.
 * 정수 필드는 빅엔디언이며 파일은 한 프로세스가 사용한다고 가정한다.
 */
public final class TableFile {
    // 읽을 때도 같은 제한을 적용해 손상된 길이 필드로 거대한 버퍼를 만들지 않도록 한다.
    private static final int MAX_RECORD_BYTES = 1024 * 1024;

    private static final byte UPDATE_TAG = 1;
    private static final byte DELETE_TAG = 2;

    private enum Operation { INSERT, UPDATE, DELETE }

    private record Entry(Operation operation, long id, List<String> values) {
        Row row() {
            return new Row(id, values);
        }
    }

    @FunctionalInterface
    private interface EntryVisitor {
        boolean visit(Entry entry, long offset) throws IOException;
    }

    private static final class State {
        long lastId;
        final Map<Long, Entry> changes = new HashMap<>();

        boolean contains(long id) {
            Entry change = changes.get(id);
            return id > 0 && id <= lastId && (change == null || change.operation() != Operation.DELETE);
        }
    }

    public record Row(long id, List<String> textValues) {
        public Row {
            textValues = List.copyOf(textValues);
        }
    }

    private final Path path;
    private final int textColumnCount;
    // 최초 접근 시 파일을 재생해 마지막 삽입 ID와 변경된 ID의 최신 기록을 복원한다.
    private State state;

    public TableFile(Path path, int textColumnCount) {
        this.path = path.toAbsolutePath();
        this.textColumnCount = textColumnCount;
    }

    public long append(List<String> textValues) throws IOException {
        State current = loadState();
        if (current.lastId == Long.MAX_VALUE) {
            throw new IOException("No IDs left in " + path);
        }
        long id = current.lastId + 1;
        write(new Entry(Operation.INSERT, id, textValues));
        current.lastId = id;
        return id;
    }

    public boolean update(long id, int column, String value) throws IOException {
        State current = loadState();
        if (!current.contains(id)) {
            return false;
        }
        Entry previous = current.changes.get(id);
        if (previous == null) {
            previous = scan((entry, offset) -> entry.operation() != Operation.INSERT || entry.id() != id);
            if (previous == null) {
                throw new IOException("Cannot find stored row " + id + " in " + path);
            }
        }

        var values = new ArrayList<>(previous.values());
        values.set(column, value);
        Entry replacement = new Entry(Operation.UPDATE, id, List.copyOf(values));
        write(replacement);
        current.changes.put(id, replacement);
        return true;
    }

    public boolean delete(long id) throws IOException {
        State current = loadState();
        if (!current.contains(id)) {
            return false;
        }
        Entry removal = new Entry(Operation.DELETE, id, List.of());
        write(removal);
        current.changes.put(id, removal);
        return true;
    }

    // append-only 파일에서 성공한 변경은 길이를 늘린다. 파일을 교체하는 경우도 속성으로 구분한다.
    public String revision() throws IOException {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.fileKey() + ":" + attributes.creationTime() + ":"
                    + attributes.lastModifiedTime() + ":" + attributes.size();
        } catch (NoSuchFileException exception) {
            return "missing";
        }
    }

    private void write(Entry entry) throws IOException {
        byte[] record = encode(entry);
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
            // 쓰기 실패 뒤에는 다음 접근에서 파일을 다시 검증한다.
            state = null;
            throw exception;
        }
    }

    public void forEachRow(Consumer<Row> visitor) throws IOException {
        State current = loadState();
        // 원래 삽입 순서로 읽으며 수정된 행은 최신 버전으로 바꾸고 삭제된 행은 건너뛴다.
        scan((entry, offset) -> {
            if (entry.operation() == Operation.INSERT) {
                Entry visible = current.changes.getOrDefault(entry.id(), entry);
                if (visible.operation() != Operation.DELETE) {
                    visitor.accept(visible.row());
                }
            }
            return true;
        });
    }

    private State loadState() throws IOException {
        if (state == null) {
            State loaded = new State();
            scan((entry, offset) -> {
                if (entry.operation() == Operation.INSERT) {
                    if (entry.id() != loaded.lastId + 1) {
                        throw corrupt(offset, "unexpected row ID " + entry.id());
                    }
                    loaded.lastId = entry.id();
                } else {
                    if (!loaded.contains(entry.id())) {
                        throw corrupt(offset, "change targets missing row " + entry.id());
                    }
                    loaded.changes.put(entry.id(), entry);
                }
                return true;
            });
            state = loaded;
        }
        return state;
    }

    // 방문자가 false를 반환하면 해당 레코드에서 읽기를 멈춘다.
    private Entry scan(EntryVisitor visitor) throws IOException {
        long size;
        try {
            size = Files.size(path);
        } catch (NoSuchFileException exception) {
            return null;
        }

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            for (long offset = 0; offset < size;) {
                int length;
                try {
                    length = input.readInt();
                } catch (EOFException exception) {
                    throw corrupt(offset, "incomplete record length");
                }
                if (length < Long.BYTES || length > MAX_RECORD_BYTES) {
                    throw corrupt(offset, "invalid record length");
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    throw corrupt(offset, "incomplete record");
                }

                Entry entry = decode(ByteBuffer.wrap(payload), decoder, offset);
                if (!visitor.visit(entry, offset)) {
                    return entry;
                }
                // 저장된 길이는 본문만 세므로 길이 필드 4바이트를 더해야 다음 레코드가 시작된다.
                offset += Integer.BYTES + length;
            }
        }
        return null;
    }

    private byte[] encode(Entry entry) throws IOException {
        List<String> textValues = entry.values();
        int expectedColumns = entry.operation() == Operation.DELETE ? 0 : textColumnCount;
        if (textValues.size() != expectedColumns) {
            throw new IllegalArgumentException("Unexpected text column count");
        }
        var fields = new ArrayList<byte[]>(expectedColumns);
        long length = entry.operation() == Operation.INSERT ? Long.BYTES : 2 * Long.BYTES + Byte.BYTES;
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
        buffer.putInt((int) length);
        if (entry.operation() == Operation.INSERT) {
            buffer.putLong(entry.id());
        } else {
            byte tag = entry.operation() == Operation.UPDATE ? UPDATE_TAG : DELETE_TAG;
            buffer.putLong(0).put(tag).putLong(entry.id());
        }
        for (byte[] field : fields) {
            buffer.putInt(field.length).put(field);
        }
        return buffer.array();
    }

    private Entry decode(ByteBuffer payload, CharsetDecoder decoder, long offset) throws IOException {
        long id = payload.getLong();
        Operation operation = Operation.INSERT;
        if (id == 0) {
            if (payload.remaining() < Byte.BYTES + Long.BYTES) {
                throw corrupt(offset, "incomplete change record");
            }
            operation = switch (payload.get()) {
                case UPDATE_TAG -> Operation.UPDATE;
                case DELETE_TAG -> Operation.DELETE;
                default -> throw corrupt(offset, "invalid change type");
            };
            id = payload.getLong();
        }
        if (id <= 0) {
            throw corrupt(offset, "invalid row ID " + id);
        }

        int columns = operation == Operation.DELETE ? 0 : textColumnCount;
        var textValues = new ArrayList<String>(columns);
        for (int column = 0; column < columns; column++) {
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
        return new Entry(operation, id, List.copyOf(textValues));
    }

    private IOException corrupt(long offset, String message) {
        return new IOException("Corrupt table file " + path + " at byte " + offset + ": " + message);
    }
}
