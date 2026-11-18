package minidb.engine;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.LongStream;
import minidb.cache.RedisCache;
import minidb.index.BPlusTree;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Ast.Delete;
import minidb.sql.Ast.Insert;
import minidb.sql.Ast.Name;
import minidb.sql.Ast.OrderBy;
import minidb.sql.Ast.Select;
import minidb.sql.Ast.Statement;
import minidb.sql.Ast.Update;
import minidb.sql.SqlException;
import minidb.storage.DataPage;
import minidb.storage.Faults;
import minidb.storage.Table;
import minidb.type.Schema;
import minidb.type.Values;

/**
 * 데이터 폴더 하나 = 데이터베이스 하나. 문장을 받아 실행한다 (설계 1.9).
 * 문장 하나 = 커밋 하나: 바뀐 페이지를 메모리 복사본에 모아 두었다가 {@link Table#commit}으로 한 번에 반영하므로
 * 문장은 전부 적용되거나 전혀 적용되지 않는다. 테이블은 처음 쓸 때 열어 캐시한다 (닫으면 체크포인트).
 * 같은 테이블의 SELECT는 읽기 락을 공유하고, 생성·복구·쓰기는 쓰기 락으로 직렬화한다.
 */
public final class Database implements AutoCloseable {

    // 같은 JVM에서는 이미 잠근 파일의 두 번째 채널을 열지 않는다.
    private static final Set<Path> OPEN_DIRECTORIES = ConcurrentHashMap.newKeySet();

    private final QueryCache cache;
    private final Path dir;
    private final boolean fsyncLog;
    private final Faults faults;
    private final FileChannel directoryLock;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Map<String, TableSlot> tables = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private boolean closed;

    private static final class TableSlot {
        final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
        Table table;
    }

    private Database(Path dir, boolean fsyncLog, Faults faults, FileChannel directoryLock, RedisCache redis) {
        this.dir = dir;
        this.fsyncLog = fsyncLog;
        this.faults = faults;
        this.directoryLock = directoryLock;
        this.cache = redis == null ? null : new QueryCache(redis, dir, zone);
    }

    public static Database open(Path dir, boolean fsyncLog) throws IOException {
        return open(dir, fsyncLog, Faults.NONE);
    }

    /** Redis는 선택 사항이다. 기존 open은 환경변수와 무관하게 캐시 없이 동작한다. */
    public static Database openCached(Path dir, boolean fsyncLog, RedisCache redis) throws IOException {
        return open(dir, fsyncLog, Faults.NONE, redis);
    }

    public String cacheSummary() {
        return cache == null ? null : cache.summary();
    }

    /** 테스트가 쓰기 실패를 흉내 낼 때 */
    public static Database open(Path dir, boolean fsyncLog, Faults faults) throws IOException {
        return open(dir, fsyncLog, faults, null);
    }

    private static Database open(Path dir, boolean fsyncLog, Faults faults, RedisCache redis) throws IOException {
        Files.createDirectories(dir);
        Path realDir = dir.toRealPath();
        String busy = "이미 사용 중인 데이터 폴더: " + realDir;
        if (!OPEN_DIRECTORIES.add(realDir)) throw new IOException(busy);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(realDir.resolve(".minidb.lock"), CREATE, WRITE);
            try {
                if (channel.tryLock() == null) throw new IOException(busy);
            } catch (OverlappingFileLockException e) {
                throw new IOException(busy, e);
            }
            return new Database(realDir, fsyncLog, faults, channel, redis);
        } catch (IOException | RuntimeException | Error e) {
            try {
                if (channel != null) channel.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            } finally {
                OPEN_DIRECTORIES.remove(realDir);
            }
            throw e;
        }
    }

    public ZoneId zone() {
        return zone;
    }

    public Result execute(Statement st) throws IOException {
        Name name = switch (st) {
            case CreateTable s -> s.table();
            case Insert s -> s.table();
            case Select s -> s.table();
            case Update s -> s.table();
            case Delete s -> s.table();
        };
        var active = lifecycle.readLock();
        active.lock();
        try {
            if (closed) throw new IOException("데이터베이스가 닫혔습니다");
            TableSlot slot = tables.computeIfAbsent(name.key(), key -> new TableSlot());
            if (st instanceof Select s) return selectLocked(s, slot);
            return writeLocked(st, name, slot);
        } finally {
            active.unlock();
        }
    }

    private Result selectLocked(Select st, TableSlot slot) throws IOException {
        var read = slot.lock.readLock();
        read.lock();
        if (slot.table == null) {
            read.unlock();
            var write = slot.lock.writeLock();
            write.lock();
            try {
                openTable(st.table(), slot);
                read.lock();
            } finally {
                write.unlock();
            }
        }
        try {
            Table table = slot.table;
            return cache == null ? select(st, table)
                    : cache.select(st, table.contentRevision(), () -> select(st, table));
        } finally {
            read.unlock();
        }
    }

    private Result writeLocked(Statement st, Name name, TableSlot slot) throws IOException {
        var write = slot.lock.writeLock();
        write.lock();
        try {
            return switch (st) {
                case CreateTable s -> create(s, slot);
                case Insert s -> insert(s, openTable(name, slot));
                case Update s -> update(s, openTable(name, slot));
                case Delete s -> delete(s, openTable(name, slot));
                case Select s -> throw new IllegalArgumentException("SELECT는 읽기 경로에서 실행해야 합니다");
            };
        } catch (IOException | RuntimeException e) {
            // 대기 중인 요청도 같은 락을 사용해 다음에 테이블을 열고 복구한다.
            if (slot.table != null && slot.table.isBroken()) {
                slot.table.abandon();
                slot.table = null;
            }
            throw e;
        } finally {
            write.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        var closing = lifecycle.writeLock();
        closing.lock();
        try {
            if (closed) return;
            closed = true;
            try (var channel = directoryLock) {
                closeTables();
            } finally {
                OPEN_DIRECTORIES.remove(dir);
            }
        } finally {
            closing.unlock();
        }
    }

    private void closeTables() throws IOException {
        IOException first = null;
        for (TableSlot slot : tables.values()) {
            if (slot.table == null) continue;
            try {
                slot.table.close();
            } catch (IOException e) {
                if (first == null) first = e;
                else first.addSuppressed(e);
            }
        }
        tables.clear();
        if (first != null) throw first;
    }

    // ───── 테이블 찾기 ─────

    /** 파일 이름: 키에서 [a-z0-9_] 밖의 바이트를 %XX로. 대소문자·유니코드 정규화가 다른 파일시스템에서도 이름이 안 겹치고 경로 문자도 못 들어온다 */
    static String fileBase(String key) {
        StringBuilder sb = new StringBuilder();
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') sb.append(c);
            else sb.append('%').append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /** 테이블 복구와 최초 공개는 같은 테이블의 쓰기 락 안에서 수행한다. */
    private Table openTable(Name name, TableSlot slot) throws IOException {
        if (slot.table != null) return slot.table;
        String base = fileBase(name.key());
        if (!Files.exists(dir.resolve(base + ".db"))) {
            throw new SqlException(name.line(), name.col(), "없는 테이블: " + name.text());
        }
        slot.table = Table.open(dir, base, fsyncLog, faults);
        return slot.table;
    }

    // ───── 문장 ─────

    private Result create(CreateTable ct, TableSlot slot) throws IOException {
        Name name = ct.table();
        Schema schema = Schema.define(ct);
        SqlException exists = new SqlException(name.line(), name.col(), "이미 있는 테이블: " + name.text());
        if (slot.table != null) throw exists;
        try {
            slot.table = Table.create(dir, fileBase(name.key()), schema.toBytes(), fsyncLog, faults);
        } catch (FileAlreadyExistsException e) {
            throw exists;
        }
        return new Result(null, null, 0, name.key());
    }

    private Result insert(Insert ins, Table t) throws IOException {
        Schema schema = t.schema();
        List<Object[]> rows = new ArrayList<>();
        for (var values : ins.rows()) rows.add(schema.checkInsert(values)); // 하나라도 틀리면 아무것도 안 씀
        long id = t.nextId();
        if (id + rows.size() - 1 > Integer.MAX_VALUE) {
            throw new SqlException(ins.table().line(), ins.table().col(), "id가 INT 범위를 넘어 더 넣을 수 없음");
        }
        Pages pages = new Pages(t);
        for (Object[] row : rows) {
            row[0] = (int) id++;
            pages.append((Integer) row[0], schema.encode(row));
        }
        pages.commit();
        t.nextId(id);
        return new Result(null, null, rows.size(), ins.table().key());
    }

    private Result select(Select sel, Table t) throws IOException {
        Schema schema = t.schema();
        Where where = Where.compile(sel.where(), schema, zone);
        int[] cols = sel.columns().isEmpty()
                ? java.util.stream.IntStream.range(0, schema.columns().size()).toArray()
                : sel.columns().stream().mapToInt(n -> column(schema, n)).toArray();
        List<Comparator<Object[]>> keys = new ArrayList<>();
        for (OrderBy o : sel.orderBy()) keys.add(order(column(schema, o.column()), o.desc()));

        boolean limitById = sel.limit() != null && sel.orderBy().size() == 1
                && sel.orderBy().getFirst().column().key().equals("id")
                && !sel.orderBy().getFirst().desc();
        List<Object[]> out = new ArrayList<>();
        Pages pages = new Pages(t);
        if (limitById) {
            pages.firstById(where, sel.limit(), out);
        } else {
            pages.matching(where, (no, slot, row) -> out.add(row));
            if (!keys.isEmpty()) {
                Comparator<Object[]> cmp = keys.get(0);
                for (int i = 1; i < keys.size(); i++) cmp = cmp.thenComparing(keys.get(i));
                out.sort(cmp); // 안정 정렬: 같은 키는 저장 순서 유지
            }
        }
        List<Object[]> limited = sel.limit() == null || out.size() <= sel.limit()
                ? out : out.subList(0, sel.limit().intValue());
        List<Object[]> rows = new ArrayList<>(limited.size());
        for (Object[] r : limited) {
            Object[] p = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) p[i] = r[cols[i]];
            rows.add(p);
        }
        List<String> names = new ArrayList<>();
        for (int c : cols) names.add(schema.columns().get(c).name());
        return new Result(names, rows, rows.size(), null);
    }

    private static int column(Schema schema, Name n) {
        int i = schema.indexOf(n.text());
        if (i < 0) throw new SqlException(n.line(), n.col(), "없는 칸: " + n.text());
        return i;
    }

    /** NULL이 가장 작다: ASC면 맨 앞, DESC(전체를 뒤집음)면 맨 뒤 */
    private static Comparator<Object[]> order(int col, boolean desc) {
        Comparator<Object[]> c = (a, b) -> {
            Object x = a[col], y = b[col];
            if (x == null || y == null) return x == null ? (y == null ? 0 : -1) : 1;
            return Values.compare(x, y);
        };
        return desc ? c.reversed() : c;
    }

    private record Target(int page, int slot, Object[] row) {}

    private Result update(Update up, Table t) throws IOException {
        Schema schema = t.schema();
        Map<Integer, Object> sets = schema.checkUpdate(up.sets());
        Where where = Where.compile(up.where(), schema, zone);
        Pages pages = new Pages(t);
        List<Target> targets = new ArrayList<>();
        pages.matching(where, (no, slot, row) -> targets.add(new Target(no, slot, row)));
        // 대상을 먼저 다 모았으니 옮겨 간 행을 다시 고칠 일이 없다
        for (Target g : targets) {
            Object[] row = g.row().clone();
            sets.forEach((i, v) -> row[i] = v);
            byte[] bytes = schema.encode(row);
            if (pages.page(g.page()).update(g.slot(), bytes)) {
                pages.touch(g.page());
            } else {
                pages.delete(g);
                pages.append((Integer) row[0], bytes);
            }
        }
        pages.commit();
        return new Result(null, null, targets.size(), up.table().key());
    }

    private Result delete(Delete del, Table t) throws IOException {
        Where where = Where.compile(del.where(), t.schema(), zone);
        Pages pages = new Pages(t);
        List<Target> targets = new ArrayList<>();
        pages.matching(where, (no, slot, row) -> targets.add(new Target(no, slot, row)));
        for (Target g : targets) {
            pages.delete(g);
        }
        pages.commit();
        return new Result(null, null, targets.size(), del.table().key());
    }

    /** 한 문장이 읽고 고치는 페이지의 메모리 복사본. 고친 것만 모아 한 번에 커밋한다 */
    private static final class Pages {
        interface Visitor {
            void row(int page, int slot, Object[] row);
        }

        private final Table table;
        private final Map<Integer, DataPage> loaded = new TreeMap<>();
        private final Set<Integer> touched = new HashSet<>();
        private final Map<Integer, Long> indexChanges = new HashMap<>();
        private int pageCount;

        Pages(Table table) throws IOException {
            this.table = table;
            this.pageCount = table.pageCount();
        }

        DataPage page(int n) throws IOException {
            DataPage p = loaded.get(n);
            if (p == null) {
                p = DataPage.fromBytes(n, table.readPage(n));
                loaded.put(n, p);
            }
            return p;
        }

        void touch(int n) {
            touched.add(n);
        }

        void firstById(Where where, long limit, List<Object[]> rows) throws IOException {
            if (limit == 0) return;
            Where.IdRange range = where.idRange();
            if (range == null) range = new Where.IdRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (range.lo() > range.hi()) return;

            Visitor collect = (page, slot, row) -> rows.add(row);
            table.scanIdsWhile((int) range.lo(), (int) range.hi(), location -> {
                visit(location, where, collect);
                return rows.size() < limit;
            });
        }

        void matching(Where where, Visitor visitor) throws IOException {
            Where.IdRange range = where.idRange();
            if (range == null) {
                for (int no = 1; no < pageCount; no++) {
                    DataPage p = page(no);
                    for (int slot = 0; slot < p.slotCount(); slot++) {
                        visit(p, slot, where, visitor);
                    }
                }
            } else if (range.lo() <= range.hi()) {
                int lo = (int) range.lo(), hi = (int) range.hi();
                if (lo == hi) {
                    long location = table.findId(lo);
                    if (location != BPlusTree.NONE) visit(location, where, visitor);
                } else {
                    var locations = LongStream.builder();
                    table.scanIds(lo, hi, locations);
                    // 이동된 행도 선형 탐색과 같은 페이지·슬롯 순서로 방문한다.
                    for (long location : locations.build().sorted().toArray()) visit(location, where, visitor);
                }
            }
        }

        private void visit(long location, Where where, Visitor visitor) throws IOException {
            visit(page(BPlusTree.page(location)), BPlusTree.slot(location), where, visitor);
        }

        private void visit(DataPage p, int slot, Where where, Visitor visitor) {
            byte[] raw = p.read(slot);
            if (raw == null) return;
            Object[] row = table.schema().decode(raw);
            if (Boolean.TRUE.equals(where.test(row))) visitor.row(p.pageId(), slot, row);
        }

        /** 마지막 페이지에, 안 들어가면 새 페이지(번호 = 지금 페이지 수)에 넣는다 */
        void append(int id, byte[] row) throws IOException {
            int no = pageCount - 1;
            int slot = no >= 1 ? page(no).insert(row) : -1;
            if (slot < 0) {
                no = pageCount++;
                DataPage p = DataPage.empty(no);
                loaded.put(no, p);
                slot = p.insert(row);
            }
            touch(no);
            indexChanges.put(id, BPlusTree.loc(no, slot));
        }

        void delete(Target target) throws IOException {
            page(target.page()).delete(target.slot());
            touch(target.page());
            indexChanges.put((Integer) target.row()[0], BPlusTree.NONE);
        }

        void commit() throws IOException {
            if (touched.isEmpty()) return;
            TreeMap<Integer, byte[]> images = new TreeMap<>();
            for (int n : touched) images.put(n, page(n).toBytes());
            table.commit(images, indexChanges);
        }
    }
}
