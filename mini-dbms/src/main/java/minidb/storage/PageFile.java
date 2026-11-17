package minidb.storage;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * 테이블 파일 하나 (설계 1.4). N번 페이지의 파일 위치 = N × 8192.
 * 페이지는 검사 없이 byte[8192] 그대로 읽고 쓴다: WAL 복구(#42)가 찢어진 페이지도 읽고 덮어써야 해서.
 * 데이터 페이지로 쓰려면 DataPage.fromBytes(n, readPage(n)) (checksum 검사).
 */
public final class PageFile implements AutoCloseable {

    private static final int SIZE = DataPage.SIZE;

    private final FileChannel ch;
    private final byte[] schema;

    private PageFile(FileChannel ch, byte[] schema) {
        this.ch = ch;
        this.schema = schema;
    }

    /** 메타 페이지만 있는 새 파일. 이미 있으면 FileAlreadyExistsException */
    public static PageFile create(Path path, byte[] schema) throws IOException {
        byte[] meta = MetaPage.build(schema);
        try (FileChannel c = FileChannel.open(path, CREATE_NEW, WRITE)) {
            writeFully(c, meta, 0);
            c.force(true);
        }
        return open(path);
    }

    /** 메타 페이지(매직·버전·페이지 크기)를 확인하고 연다 */
    public static PageFile open(Path path) throws IOException {
        FileChannel c = FileChannel.open(path, READ, WRITE);
        try {
            if (c.size() < SIZE) {
                throw new StorageException(0, "파일이 " + c.size() + "B라 메타 페이지가 없습니다");
            }
            byte[] meta = new byte[SIZE];
            readFully(c, meta, 0);
            return new PageFile(c, MetaPage.schema(meta));
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /** 메타 페이지 8번 위치부터 끝까지 (MetaPage.schema 참고) */
    public byte[] schema() {
        return schema.clone();
    }

    /**
     * 메타 페이지를 포함한 페이지 수. 데이터 페이지는 1 ~ pageCount()−1.
     * 파일 끝이 8192로 나누어떨어지지 않으면(쓰다 끊긴 페이지) 그 페이지도 센다: 읽으면 checksum에서 걸린다.
     */
    public int pageCount() throws IOException {
        return Math.toIntExact((ch.size() + SIZE - 1) / SIZE);
    }

    /** n번 페이지 8192B. 파일 끝을 넘는 부분은 0 */
    public byte[] readPage(int n) throws IOException {
        int count = pageCount();
        if (n < 1 || n >= count) {
            throw new IllegalArgumentException("데이터 페이지 " + n + "이 없습니다 (1 ~ " + (count - 1) + ")");
        }
        byte[] page = new byte[SIZE];
        readFully(ch, page, (long) n * SIZE);
        return page;
    }

    /** n번 위치에 이미지를 쓴다 (fsync 안 함). n = pageCount()이면 파일 끝에 붙인다. 중간을 비우는 쓰기는 거부 */
    public void writePage(int n, byte[] image) throws IOException {
        if (image.length != SIZE) {
            throw new IllegalArgumentException("페이지 이미지가 " + image.length + "B입니다 (8192B여야 함)");
        }
        int count = pageCount();
        if (n < 1 || n > count) {
            throw new IllegalArgumentException("데이터 페이지 " + n + "에 쓸 수 없습니다 (1 ~ " + count + ")");
        }
        writeFully(ch, image, (long) n * SIZE);
    }

    /** 데이터 파일 fsync (체크포인트·복구 때) */
    public void force() throws IOException {
        ch.force(false);
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }

    // 위치 지정 read/write는 한 번에 다 안 옮길 수 있어 반복한다
    private static void readFully(FileChannel c, byte[] dst, long pos) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(dst);
        while (b.hasRemaining()) {
            if (c.read(b, pos + b.position()) < 0) {
                return; // 파일 끝: 나머지는 0으로 둔다 (끊긴 페이지)
            }
        }
    }

    private static void writeFully(FileChannel c, byte[] src, long pos) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(src);
        while (b.hasRemaining()) {
            c.write(b, pos + b.position());
        }
    }
}
