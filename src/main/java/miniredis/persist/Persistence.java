package miniredis.persist;

import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import miniredis.store.Store;
import miniredis.store.StoreException;

/**
 * 데이터 폴더 전체를 맡는다: 잠금, manifest, AOF 쓰기·fsync, 복구, AOF 압축, RDB(SAVE·BGSAVE).
 * fsync 스레드 말고는 전부 executor 스레드에서 호출된다.
 *
 * data/
 *   manifest      읽을 파일 목록과 순서 (한 줄에 파일 이름 하나)
 *   base.N.rdb    압축 시점의 전체 상태
 *   incr.N.aof    그 이후 변경 레코드
 *   dump.rdb      SAVE·BGSAVE 결과
 *   lock          이중 기동 방지
 */
public final class Persistence implements AutoCloseable {
    static final long AUTO_REWRITE_MIN = 64L << 20;
    static final int AUTO_SAVE_CHANGES = 1000;
    static final long AUTO_SAVE_INTERVAL_NS = 60_000_000_000L;

    private final Path dir;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private List<String> manifest = new ArrayList<>();
    private int seq;
    private volatile FileChannel current; // fsync 스레드가 읽는다. 압축 ①에서 executor가 교체
    private volatile boolean fsyncFailed;
    private volatile boolean closed;
    private Thread fsyncThread;

    private boolean rejected; // 쓰기 거부 상태 (디스크 기록 실패)
    private final List<ByteBuffer> retry = new ArrayList<>();

    private Job job; // 진행 중인 압축 또는 BGSAVE (한 번에 하나)
    private long sizeAfterRewrite;
    private long changesSinceSave;
    private long lastSaveNs = System.nanoTime();
    private long lastSaveUnixMs;

    /** 잠금을 먼저 잡는다. 다른 프로세스가 잡고 있으면 시작을 거부한다. */
    public Persistence(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        lockChannel = FileChannel.open(dir.resolve("lock"), CREATE, WRITE);
        FileLock l = lockChannel.tryLock(); // 같은 JVM에서 두 번 잡으면 null이 아니라 OverlappingFileLockException
        if (l == null) {
            lockChannel.close();
            throw new IOException("데이터 폴더를 다른 프로세스가 쓰는 중: " + dir);
        }
        lock = l;
    }

    // ---------------- 복구 ----------------

    /** 서버 시작 시 executor 스레드에서 한 번. store의 로그 연결은 이 다음에 한다. */
    public void recover(Store store) throws IOException {
        store.setLoading(true);
        try {
            Path mf = dir.resolve("manifest");
            if (Files.exists(mf)) {
                manifest = readManifest(mf);
                for (int i = 0; i < manifest.size(); i++) {
                    String name = manifest.get(i);
                    Path p = dir.resolve(name);
                    if (!Files.exists(p)) throw new IOException("manifest에 있는 파일이 없다: " + name);
                    if (name.endsWith(".rdb")) {
                        Rdb.load(Files.readAllBytes(p), store::execute);
                    } else {
                        replayAof(p, i == manifest.size() - 1, store);
                    }
                }
                String last = manifest.get(manifest.size() - 1);
                seq = seqOf(last);
                current = FileChannel.open(dir.resolve(last), CREATE, WRITE, APPEND);
            } else {
                Path dump = dir.resolve("dump.rdb");
                boolean loaded = Files.exists(dump);
                if (loaded) Rdb.load(Files.readAllBytes(dump), store::execute);
                seq = 1;
                List<String> m = new ArrayList<>();
                if (loaded) { // dump 내용을 AOF 쪽에도 남겨야 다음 재시작에 manifest만 읽어도 데이터가 있다
                    String base = "base.1.rdb";
                    Path tmp = dir.resolve(base + ".tmp");
                    try (Rdb.Writer w = new Rdb.Writer(tmp)) {
                        writeAll(store, w);
                        w.finish();
                    }
                    replaceAtomically(tmp, dir.resolve(base));
                    m.add(base);
                }
                String incr = "incr.1.aof";
                current = FileChannel.open(dir.resolve(incr), CREATE, WRITE, APPEND);
                m.add(incr);
                writeManifest(m);
            }
            sizeAfterRewrite = aofSize();
        } finally {
            store.setLoading(false);
        }
        startFsyncThread();
    }

    private void replayAof(Path p, boolean lastFile, Store store) throws IOException {
        Records.Parsed parsed = Records.parse(Files.readAllBytes(p));
        if (parsed.problem() == Records.Problem.CORRUPT
                || (parsed.problem() == Records.Problem.TRUNCATED_TAIL && !lastFile)) {
            throw new IOException("AOF 손상 (" + p.getFileName() + " 위치 " + parsed.goodEnd() + "). 시작을 거부한다");
        }
        parsed.records().forEach(store::execute);
        if (parsed.problem() == Records.Problem.TRUNCATED_TAIL) {
            try (FileChannel c = FileChannel.open(p, WRITE)) {
                c.truncate(parsed.goodEnd());
                c.force(true);
            }
            System.err.println("경고: " + p.getFileName() + "의 잘린 꼬리를 " + parsed.goodEnd() + "바이트로 잘랐다");
        }
    }

    // ---------------- 쓰기 ----------------

    public boolean isRejected() {
        return rejected;
    }

    /**
     * 배치의 레코드를 한 번의 write로 쓴다. 응답보다 먼저 호출된다 (응답을 받았다면 이미 OS에 넘어간 상태).
     * 실패하면 쓰기 거부 상태가 되고 false. 못 쓴 바이트는 보관했다가 관리 틱에 다시 쓴다.
     */
    public boolean append(List<byte[]> records) {
        if (records.isEmpty()) return true;
        changesSinceSave += records.size();
        if (job != null) job.onChange(records);
        int n = 0;
        for (byte[] r : records) n += r.length;
        ByteBuffer buf = ByteBuffer.allocate(n);
        for (byte[] r : records) buf.put(r);
        buf.flip();
        retry.add(buf);
        return flushRetry();
    }

    private boolean flushRetry() {
        try {
            while (!retry.isEmpty()) {
                ByteBuffer b = retry.get(0);
                while (b.hasRemaining()) current.write(b);
                retry.remove(0);
            }
            return true;
        } catch (IOException e) {
            if (!rejected) System.err.println("AOF 쓰기 실패, 쓰기 거부 상태로 전환: " + e);
            rejected = true;
            return false;
        }
    }

    private void startFsyncThread() {
        fsyncThread = Thread.ofPlatform().daemon().name("aof-fsync").start(() -> {
            while (!closed) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                FileChannel c = current;
                try {
                    c.force(false);
                } catch (ClosedChannelException e) {
                    // 압축 ①이 막 교체한 옛 채널. executor가 닫기 전에 force했으므로 무시한다
                } catch (IOException e) {
                    fsyncFailed = true;
                }
            }
        });
    }

    // ---------------- 타이머 ----------------

    /** 관리 틱(1초): 쓰기 거부면 디스크 재시도, 자동 압축·자동 BGSAVE 조건 검사. */
    public void adminTick(Store store) {
        if (fsyncFailed) rejected = true;
        if (rejected) {
            try {
                if (flushRetry()) {
                    current.force(false);
                    fsyncFailed = false;
                    rejected = false;
                    System.err.println("디스크 기록 복구, 쓰기 재개");
                }
            } catch (IOException e) {
                return;
            }
        }
        if (job != null) return;
        long size = aofSize();
        if (size >= AUTO_REWRITE_MIN && size >= 2 * sizeAfterRewrite) {
            startRewrite(store);
        } else if (changesSinceSave >= AUTO_SAVE_CHANGES && System.nanoTime() - lastSaveNs >= AUTO_SAVE_INTERVAL_NS) {
            startBgsave(store);
        }
    }

    /** 작업 틱: 진행 중인 압축·BGSAVE를 budget 동안 진행한다. */
    public void workTick(long budgetNs) {
        if (job == null) return;
        long deadline = System.nanoTime() + budgetNs;
        try {
            while (System.nanoTime() < deadline) {
                if (!job.step()) {
                    Job done = job;
                    job = null;
                    done.finish();
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("백그라운드 저장 실패: " + e);
            if (job != null) job.abort(); // finish()에서 실패했다면 job은 이미 null이고 스스로 정리했다
            job = null;
        }
    }

    // ---------------- 명령 ----------------

    /** SAVE: executor 안에서 한 번에 저장한다. 그동안 다른 요청은 기다린다. */
    public void save(Store store) {
        busyCheck();
        try {
            Path tmp = dir.resolve("dump.rdb.tmp");
            try (Rdb.Writer w = new Rdb.Writer(tmp)) {
                writeAll(store, w);
                w.finish();
            }
            replaceAtomically(tmp, dir.resolve("dump.rdb"));
            markSaved();
        } catch (IOException e) {
            throw new StoreException(500, "SAVE 실패: " + e.getMessage());
        }
    }

    public void startBgsave(Store store) {
        busyCheck();
        try {
            job = new Job(store, dir.resolve("dump.rdb.tmp"), false);
        } catch (IOException e) {
            throw new StoreException(500, "BGSAVE 시작 실패: " + e.getMessage());
        }
    }

    /** AOF 압축 ①: 새 incr을 만들고 manifest에 붙인 뒤, 이후 레코드는 새 incr로 쓴다. */
    public void startRewrite(Store store) {
        busyCheck();
        try {
            int next = seq + 1;
            String incr = "incr." + next + ".aof";
            FileChannel ch = FileChannel.open(dir.resolve(incr), CREATE, WRITE, APPEND);
            List<String> m = new ArrayList<>(manifest);
            m.add(incr);
            writeManifest(m); // 압축 도중 죽어도 옛 파일 + 새 incr로 복구된다
            if (!flushRetry()) throw new IOException("이전 레코드를 못 썼다");
            FileChannel old = current;
            current = ch;
            old.force(false);
            old.close();
            seq = next;
            job = new Job(store, dir.resolve("base." + next + ".rdb.tmp"), true);
        } catch (IOException e) {
            throw new StoreException(500, "AOF 압축 시작 실패: " + e.getMessage());
        }
    }

    private void busyCheck() {
        if (job != null) throw new StoreException(409, (job.rewrite ? "AOF 압축" : "BGSAVE") + "이 이미 진행 중");
    }

    private void markSaved() {
        changesSinceSave = 0;
        lastSaveNs = System.nanoTime();
        lastSaveUnixMs = System.currentTimeMillis();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("aofBytes", aofSize());
        m.put("writeRejected", rejected);
        m.put("background", job == null ? "none" : job.rewrite ? "rewrite" : "bgsave");
        m.put("lastSaveUnixMs", lastSaveUnixMs);
        m.put("lastBackgroundMs", lastJobMs);
        return m;
    }

    private long lastJobMs;

    /**
     * 조금씩 나눠 하는 스냅샷. 버킷 커서로 작업 틱마다 조금씩 순회하며 파일에 쓴다.
     * 커서가 열려 있는 동안 리해싱이 멈추므로 버킷 번호가 바뀌지 않는다.
     */
    private final class Job {
        final boolean rewrite;
        final Store store;
        final Store.Cursor cursor;
        final Path tmp;
        final Rdb.Writer writer;
        final long startNs = System.nanoTime();
        // shortcut: BGSAVE 중 변경 로그를 메모리에 모은다. 변경이 아주 많으면 메모리를 쓰므로 그때는 임시 파일로 바꾼다
        final List<byte[]> changes = new ArrayList<>();
        IOException failure;

        Job(Store store, Path tmp, boolean rewrite) throws IOException {
            this.store = store;
            this.tmp = tmp;
            this.rewrite = rewrite;
            this.writer = new Rdb.Writer(tmp);
            this.cursor = store.cursor();
        }

        void onChange(List<byte[]> records) {
            // 압축은 ① 이후 변경이 전부 새 incr에 있으므로 따로 모을 필요가 없다
            if (!rewrite) changes.addAll(records);
        }

        boolean step() throws IOException {
            boolean more = cursor.step(e -> {
                try {
                    writer.entry(e.key, e.value(), e.expireAt() == 0 ? -1 : store.toUnixMs(e.expireAt()));
                } catch (IOException x) {
                    failure = x;
                }
            });
            if (failure != null) throw failure;
            return more;
        }

        void finish() throws IOException {
            try {
                for (byte[] r : changes) writer.change(r);
                writer.finish();
                if (rewrite) {
                    // ③ 새 base를 내리고 manifest를 [새 base + ① 이후 incr]로 교체 → ④ 옛 파일 삭제
                    String base = "base." + seq + ".rdb";
                    replaceAtomically(tmp, dir.resolve(base));
                    List<String> old = new ArrayList<>(manifest.subList(0, manifest.size() - 1));
                    writeManifest(List.of(base, "incr." + seq + ".aof"));
                    for (String f : old) Files.deleteIfExists(dir.resolve(f));
                    sizeAfterRewrite = aofSize();
                } else {
                    replaceAtomically(tmp, dir.resolve("dump.rdb"));
                    markSaved();
                }
                lastJobMs = (System.nanoTime() - startNs) / 1_000_000;
            } catch (IOException e) {
                abort();
                throw e;
            }
        }

        void abort() {
            cursor.close();
            try {
                writer.close();
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 임시 파일이 남아도 다음 시도 때 덮어쓴다
            }
        }
    }

    private static void writeAll(Store store, Rdb.Writer w) throws IOException {
        Store.Cursor c = store.cursor();
        IOException[] fail = new IOException[1];
        while (c.step(e -> {
            try {
                w.entry(e.key, e.value(), e.expireAt() == 0 ? -1 : store.toUnixMs(e.expireAt()));
            } catch (IOException x) {
                fail[0] = x;
            }
        })) {
            if (fail[0] != null) {
                c.close();
                throw fail[0];
            }
        }
        if (fail[0] != null) throw fail[0];
    }

    // ---------------- 파일 도우미 ----------------

    private void writeManifest(List<String> m) throws IOException {
        Path tmp = dir.resolve("manifest.tmp");
        Files.write(tmp, (String.join("\n", m) + "\n").getBytes(StandardCharsets.UTF_8));
        replaceAtomically(tmp, dir.resolve("manifest"));
        manifest = new ArrayList<>(m);
    }

    private static List<String> readManifest(Path p) throws IOException {
        List<String> m = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!line.isBlank()) m.add(line.trim());
        if (m.isEmpty() || !m.get(m.size() - 1).endsWith(".aof")) throw new IOException("manifest 형식 오류");
        return m;
    }

    private static int seqOf(String incrName) {
        return Integer.parseInt(incrName.substring("incr.".length(), incrName.length() - ".aof".length()));
    }

    private long aofSize() {
        long s = 0;
        for (String f : manifest) {
            try {
                s += Files.size(dir.resolve(f));
            } catch (IOException ignored) {
                // 삭제 직후 등. 크기 계산에서만 빠진다
            }
        }
        return s;
    }

    /**
     * 임시 파일 force → 원자적 이름 변경 → 디렉터리 force.
     * 중간에 죽어도 옛 파일과 새 파일 중 하나는 온전히 남는다.
     */
    static void replaceAtomically(Path tmp, Path target) throws IOException {
        try (FileChannel c = FileChannel.open(tmp, WRITE)) {
            c.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        try (FileChannel d = FileChannel.open(target.getParent(), READ)) {
            d.force(true); // 이름 변경 자체(디렉터리 항목)를 디스크에 내린다. macOS·Linux에서 동작 확인
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (fsyncThread != null) fsyncThread.interrupt();
        if (job != null) {
            job.abort();
            job = null;
        }
        FileChannel c = current;
        if (c != null && c.isOpen()) {
            flushRetry();
            c.force(false);
            c.close();
        }
        lock.release();
        lockChannel.close();
    }
}
