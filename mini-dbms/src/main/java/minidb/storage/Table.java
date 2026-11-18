package minidb.storage;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.function.LongConsumer;
import minidb.index.BPlusTree;
import minidb.type.Schema;

/**
 * 테이블 하나 = <base>.db(데이터) + <base>.wal(로그) (설계 1.8). 생명주기: create → open(복구) → commit… → close(체크포인트).
 * commit은 "로그에 먼저 → 데이터 파일에" 순서만 지키면, 어디서 끊겨도 다음 open이 로그로 문장 단위 전부-아니면-없음을 되살린다.
 * fsyncLog=false는 커밋마다의 로그 fsync만 건너뛴다 (프로세스가 죽어도 OS 캐시는 남아 안전, 전원이 나가면 최근 커밋을 잃을 수 있음).
 */
public final class Table implements AutoCloseable {

    /** 로그가 이만큼 커지면 데이터 파일을 fsync하고 로그를 비운다 */
    static final long CHECKPOINT_BYTES = 16L << 20;

    private final PageFile pageFile;
    private final Wal wal;
    private final Schema schema;
    private final boolean fsyncLog;
    private final Faults faults;
    private BPlusTree idIndex;
    private long nextId;
    private long txid;
    private boolean broken;

    private Table(PageFile pageFile, Wal wal, Schema schema, boolean fsyncLog, Faults faults) {
        this.pageFile = pageFile;
        this.wal = wal;
        this.schema = schema;
        this.fsyncLog = fsyncLog;
        this.faults = faults;
    }

    /**
     * 새 테이블. 메타 페이지를 임시 파일에 다 쓴 뒤 이름을 바꿔서, 도중에 죽어도 반쯤 만든 .db가 남지 않는다.
     * 이미 있으면 FileAlreadyExistsException
     */
    public static Table create(Path dir, String base, byte[] schemaBytes, boolean fsyncLog) throws IOException {
        return create(dir, base, schemaBytes, fsyncLog, Faults.NONE);
    }

    public static Table create(Path dir, String base, byte[] schemaBytes, boolean fsyncLog, Faults faults)
            throws IOException {
        Path db = dir.resolve(base + ".db"), tmp = dir.resolve(base + ".db.tmp");
        Files.deleteIfExists(tmp);
        if (Files.exists(db)) throw new FileAlreadyExistsException(db.toString());
        PageFile.create(tmp, schemaBytes).close();
        Files.move(tmp, db, ATOMIC_MOVE);
        try (FileChannel c = FileChannel.open(dir.resolve(base + ".wal"), CREATE, WRITE, TRUNCATE_EXISTING)) {
            c.force(true);
        }
        syncDir(dir);
        return open(dir, base, fsyncLog, faults);
    }

    /** 연다: 로그의 유효한 레코드를 다시 쓰고(복구), 모든 데이터 페이지를 검사하며 nextId를 구한다. 깨진 페이지가 남으면 StorageException */
    public static Table open(Path dir, String base, boolean fsyncLog, Faults faults) throws IOException {
        Files.deleteIfExists(dir.resolve(base + ".db.tmp"));
        PageFile pf = PageFile.open(dir.resolve(base + ".db"));
        Wal wal = null;
        try {
            Schema schema = Schema.fromBytes(pf.schema());
            wal = new Wal(dir.resolve(base + ".wal"), faults);
            wal.replay((txid, pages) -> {
                try {
                    for (var e : pages.entrySet()) pf.writePage(e.getKey(), e.getValue());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            pf.force();
            wal.reset();
            Table t = new Table(pf, wal, schema, fsyncLog, faults);
            t.nextId = t.rebuildIdIndex() + 1L;
            return t;
        } catch (java.io.UncheckedIOException e) {
            closeQuietly(pf, wal);
            throw e.getCause();
        } catch (IOException | RuntimeException e) {
            closeQuietly(pf, wal);
            throw e;
        }
    }

    /** WAL 복구 후 nextId와 인덱스를 같은 데이터 페이지 순회에서 구한다. */
    private int rebuildIdIndex() throws IOException {
        BPlusTree rebuilt = new BPlusTree();
        int maxId = 0;
        int pageCount = pageFile.pageCount();
        for (int no = 1; no < pageCount; no++) {
            DataPage page = DataPage.fromBytes(no, pageFile.readPage(no));
            for (int slot = 0; slot < page.slotCount(); slot++) {
                byte[] row = page.read(slot);
                if (row == null) continue;
                if (!(schema.decode(row)[0] instanceof Integer id) || id <= 0) {
                    throw new StorageException(no, "올바르지 않은 행 ID (슬롯 " + slot + ")");
                }
                int size = rebuilt.size();
                rebuilt.put(id, BPlusTree.loc(no, slot));
                if (rebuilt.size() == size) throw new StorageException(no, "중복 행 ID: " + id);
                maxId = Math.max(maxId, id);
            }
        }
        idIndex = rebuilt;
        return maxId;
    }

    public long findId(int id) throws IOException {
        return idIndex().get(id);
    }

    public void scanIds(int lo, int hi, LongConsumer visitor) throws IOException {
        idIndex().range(lo, hi, visitor);
    }

    public void scanIdsWhile(int lo, int hi, BPlusTree.RangeVisitor<IOException> visitor) throws IOException {
        idIndex().scanRange(lo, hi, visitor);
    }

    private BPlusTree idIndex() throws IOException {
        if (idIndex == null) rebuildIdIndex();
        return idIndex;
    }

    /** 복구가 끝난 페이지 내용의 버전. 파일 시각·크기에 의존하지 않아 같은 크기의 UPDATE도 구분한다.
     * 캐시 hit에서도 페이지 I/O는 발생한다. 동시 다중 프로세스 접근은 기존 엔진과 같이 지원하지 않는다. */
    public String contentRevision() throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            int count = pageFile.pageCount();
            byte[] metadata = pageFile.schema();
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(metadata.length).array());
            digest.update(metadata);
            for (int n = 1; n < count; n++) digest.update(pageFile.readPage(n));
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Schema schema() {
        return schema;
    }

    public long nextId() {
        return nextId;
    }

    /** INSERT가 커밋에 성공한 뒤 올린다 */
    public void nextId(long next) {
        nextId = next;
    }

    /** 메타 페이지 포함 페이지 수 */
    public int pageCount() throws IOException {
        return pageFile.pageCount();
    }

    public byte[] readPage(int n) throws IOException {
        faults.at("data.read", n);
        return pageFile.readPage(n);
    }

    /** 이 테이블 인스턴스를 더 쓰면 안 되나 (끊긴 쓰기를 못 치웠음). 닫고 다시 열면 복구된다 */
    public boolean isBroken() {
        return broken;
    }

    /**
     * 페이지 이미지만 받는 호출은 다음 조회에서 새 데이터로 인덱스를 재구축한다.
     */
    public void commit(SortedMap<Integer, byte[]> images) throws IOException {
        commitPages(images);
        idIndex = null;
    }

    /** 페이지 커밋 성공 후 행 위치를 갱신한다. 삭제는 BPlusTree.NONE으로 표시한다. */
    public void commit(SortedMap<Integer, byte[]> images, Map<Integer, Long> positions) throws IOException {
        commitPages(images);
        if (idIndex == null) return;
        positions.forEach((id, position) -> {
            if (position == BPlusTree.NONE) idIndex.remove(id);
            else idIndex.put(id, position);
        });
    }

    /**
     * 바뀐 페이지들을 한 트랜잭션으로 반영한다 (키 = 페이지 번호, 오름차순 = 파일 끝 붙이기 규칙).
     * 로그에 쓰다 실패하면 반쯤 쓴 레코드를 잘라내고 던진다 (아무 일도 없었던 것). 데이터 쓰기에서 실패하면
     * 커밋은 로그에 있으니 broken으로 표시하고, 다시 열 때 복구된다.
     */
    private void commitPages(SortedMap<Integer, byte[]> images) throws IOException {
        if (broken) throw new IOException("깨진 테이블입니다: 닫고 다시 열어 복구하세요");
        long start = wal.size();
        try {
            wal.append(++txid, images);
            if (fsyncLog) wal.force();
        } catch (IOException | RuntimeException e) {
            try {
                wal.truncate(start);
                wal.force();
            } catch (IOException e2) {
                broken = true;
                e.addSuppressed(e2);
            }
            throw e;
        }
        try {
            int i = 0;
            for (var e : images.entrySet()) {
                faults.at("data.write", i++);
                pageFile.writePage(e.getKey(), e.getValue());
            }
            if (wal.size() > CHECKPOINT_BYTES) checkpoint();
        } catch (IOException | RuntimeException e) {
            broken = true;
            throw new IOException("커밋은 로그에 기록됐고 다시 열 때 복구됩니다: " + e.getMessage(), e);
        }
    }

    private void checkpoint() throws IOException {
        pageFile.force();
        wal.reset();
    }

    /** 체크포인트(데이터 fsync + 로그 비우기) 후 닫는다. 깨진 테이블은 로그를 남겨 둔다 (복구용) */
    @Override
    public void close() throws IOException {
        try {
            if (!broken) checkpoint();
        } finally {
            closeQuietly(pageFile, wal);
        }
    }

    /** 체크포인트 없이 닫는다: 죽은 프로세스를 흉내 내는 테스트, 깨진 테이블을 버리는 실행기(다시 열면 복구)가 쓴다 */
    public void abandon() {
        closeQuietly(pageFile, wal);
    }

    private static void closeQuietly(AutoCloseable... cs) {
        for (AutoCloseable c : cs) {
            try {
                if (c != null) c.close();
            } catch (Exception ignored) {
                // 이미 실패 처리 중
            }
        }
    }

    private static void syncDir(Path dir) throws IOException {
        try (FileChannel c = FileChannel.open(dir, READ)) {
            c.force(true);
        }
    }
}
