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

public final class TableFile {
    private static final int MAX_RECORD_BYTES = 1024 * 1024;

    public record Row(long id, List<String> textValues) {
        public Row {
            textValues = List.copyOf(textValues);
        }
    }

    private final Path path;
    private final int textColumnCount;
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
        byte[] record = encode(id, textValues);

        Files.createDirectories(path.getParent());
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            long offset = file.length();
            file.seek(offset);
            try {
                file.write(record);
            } catch (IOException exception) {
                try {
                    file.setLength(offset);
                } catch (IOException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (IOException exception) {
            lastId = null;
            throw exception;
        }

        lastId = id;
        return id;
    }

    public void forEachRow(Consumer<Row> visitor) throws IOException {
        scan(visitor);
    }

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
                if (row.id() != lastReadId + 1) {
                    throw corrupt(offset, "unexpected row ID " + row.id());
                }
                visitor.accept(row);
                lastReadId = row.id();
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
