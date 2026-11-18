package miniredis.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import miniredis.debug.Trace;
import miniredis.hashtable.HashTable;
import miniredis.hashtable.SipHash;

/**
 * 저장소 로직 전부. HTTP 요청, AOF 재실행, 단위 테스트가 모두 execute() 하나를 부른다.
 * 스레드 안전하지 않다. 대신 만든 스레드(owner) 말고 다른 스레드가 부르면 바로 예외를 던진다 (기획서 5.1).
 */
public final class Store {
    public enum Policy {
        NOEVICTION, ALLKEYS_LRU, VOLATILE_LRU;

        public static Policy parse(String s) {
            return valueOf(s.toUpperCase().replace('-', '_'));
        }
    }

    /**
     * 엔트리 1개의 JVM 고정 비용(바이트). 초깃값 112 → 5단계 측정값 139
     * (키 100만 개, JDK 24, example.bench.MeasureEntryCost). 체인 Node와 버킷 배열 몫이 더해졌다.
     */
    public static final long ENTRY_OVERHEAD = 139;
    static final long TTL_OVERHEAD = 32;
    private static final long NS_PER_MS = 1_000_000L;
    private static final Set<String> WRITES = Set.of("SET", "DEL", "INCR", "EXPIRE", "PEXPIREAT", "PERSIST", "FLUSHALL");
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]{0,18})");

    private final Thread owner = Thread.currentThread();
    private final byte[] seed;
    private final RandomGenerator rng;
    private final LongSupplier mono;   // 단조 시계 ns
    private final LongSupplier unixMs; // 유닉스 시각 ms (파일에 쓸 때만)
    private final long maxmemory;
    private final Policy policy;

    private HashTable<Entry> keys;
    private HashTable<Entry> expires; // TTL 키만. 주기적 청소의 무작위 샘플용
    private final LruList all = new LruList(false);
    private final LruList ttl = new LruList(true);
    private long used;
    private long expiredCount, evictedCount;
    private boolean loading; // 복구 중에는 메모리 상한으로 지우지 않는다
    private Consumer<byte[][]> log = r -> { };

    public Store(byte[] seed, RandomGenerator rng, LongSupplier mono, LongSupplier unixMs, long maxmemory, Policy policy) {
        this.seed = seed;
        this.rng = rng;
        this.mono = mono;
        this.unixMs = unixMs;
        this.maxmemory = maxmemory;
        this.policy = policy;
        newTables();
    }

    private void newTables() {
        keys = new HashTable<>(new SipHash(seed), rng);
        expires = new HashTable<>(new SipHash(seed), rng);
        if (trace != null) listen();
    }

    // ---------------- 관찰용 (데모, 기본 꺼짐) ----------------

    private Trace trace;

    /** setLog와 같은 방식으로 붙인다. null이면 기록 지점은 null 비교 1번뿐이다. */
    public void setTrace(Trace trace) {
        this.trace = trace;
        if (trace != null) listen();
    }

    private void listen() {
        for (String name : List.of("keys", "expires")) {
            HashTable<Entry> t = name.equals("keys") ? keys : expires;
            t.setListener(new HashTable.Listener() {
                public void rehashStart(int from, int to) { trace.emit("rehash.start", "table", name, "from", from, "to", to, "snap", snap()); }
                public void rehashStep(int bucket, List<String> moved) { trace.emit("rehash.step", "table", name, "bucket", bucket, "moved", moved, "snap", snap()); }
                public void rehashDone(int size) { trace.emit("rehash.done", "table", name, "size", size, "snap", snap()); }
            });
        }
    }

    private Trace.Raw snap() {
        return new Trace.Raw(Trace.json(snapshot()));
    }

    /** 데모 화면의 상태 사진. 버킷이 많으면(256칸 초과) 요약만. */
    public Map<String, Object> snapshot() {
        check();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", tableView(keys, 256));
        m.put("expires", tableView(expires, 256));
        m.put("lru", names(all.head(64)));
        m.put("ttl", names(ttl.head(64)));
        m.put("memory", used);
        m.put("maxmemory", maxmemory);
        m.put("policy", policy.name().toLowerCase().replace('_', '-'));
        m.put("count", (long) keys.size());
        m.put("expired", expiredCount);
        m.put("evicted", evictedCount);
        return m;
    }

    private Map<String, Object> tableView(HashTable<Entry> t, int limit) {
        int[] s = t.debugState();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size0", s[0]);
        m.put("size1", s[1]);
        m.put("used0", s[2]);
        m.put("used1", s[3]);
        m.put("rehashIndex", s[4]);
        m.put("openCursors", s[5]);
        if (s[0] + s[1] <= limit) {
            m.put("t0", chains(t.debugBuckets(0, 0, s[0])));
            m.put("t1", chains(t.debugBuckets(1, 0, s[1])));
        }
        return m;
    }

    private List<List<Map<String, Object>>> chains(List<List<Entry>> buckets) {
        List<List<Map<String, Object>>> out = new ArrayList<>();
        for (List<Entry> chain : buckets) {
            List<Map<String, Object>> c = new ArrayList<>();
            for (Entry e : chain) c.add(node(e));
            out.add(c);
        }
        return out;
    }

    private Map<String, Object> node(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("k", e.key);
        m.put("v", preview(e.value, 24));
        m.put("vlen", e.value.length);
        m.put("ttl", e.expireAt == 0 ? -1 : Math.max(0, (e.expireAt - mono.getAsLong()) / NS_PER_MS));
        m.put("expired", expired(e));
        return m;
    }

    private static List<String> names(List<Entry> es) {
        List<String> out = new ArrayList<>();
        for (Entry e : es) out.add(e.key);
        return out;
    }

    private static String preview(byte[] v, int max) {
        return new String(v, 0, Math.min(v.length, max), StandardCharsets.UTF_8);
    }

    /** DEBUG 하위 명령 (Engine이 debug가 켜졌을 때만 넘긴다). 읽기 전용: 만료 삭제·touch·리해싱 스텝을 일으키지 않는다. */
    public Map<String, Object> debug(byte[][] argv) {
        check();
        String sub = argv.length > 1 ? str(argv[1]).toUpperCase() : "";
        Map<String, Object> m = new LinkedHashMap<>();
        switch (sub) {
            case "TABLE" -> m.putAll(snapshot());
            case "LOCATE" -> {
                arity(argv, 3);
                m.putAll(locate(str(argv[2])));
            }
            case "COLLIDE" -> {
                arity(argv, 4);
                m.putAll(collide(str(argv[2]), (int) Math.min(5, parseLong(argv[3]))));
            }
            case "BUCKETS" -> {
                arity(argv, 6);
                HashTable<Entry> t = str(argv[2]).equals("expires") ? expires : keys;
                int from = (int) parseLong(argv[4]);
                m.put("from", from);
                m.put("buckets", chains(t.debugBuckets((int) parseLong(argv[3]), from, (int) Math.min(256, parseLong(argv[5])))));
            }
            default -> throw bad("DEBUG 하위 명령: TABLE | LOCATE k | COLLIDE k n | BUCKETS keys|expires 0|1 from count | AOF | TICKS | TICK");
        }
        return m;
    }

    private Map<String, Object> locate(String key) {
        long[] r = keys.debugLocate(key);
        Map<String, Object> m = new LinkedHashMap<>();
        int[] s = keys.debugState();
        m.put("key", key);
        m.put("hash", String.format("%016x", r[0]));
        m.put("size0", s[0]);
        m.put("size1", s[1]);
        m.put("idx0", r[1]);
        m.put("idx1", r[2]);
        m.put("rehashing", keys.isRehashing());
        m.put("table", r[3]);
        m.put("pos", r[4]);
        m.put("chainLen", r[5]);
        if (r[3] >= 0) {
            Entry e = keys.debugBuckets((int) r[3], (int) (r[3] == 0 ? r[1] : r[2]), 1).get(0).get((int) r[4]);
            Map<String, Object> en = new LinkedHashMap<>();
            en.put("keyBytes", e.keyBytes);
            en.put("valueLen", e.value.length);
            en.put("value", preview(e.value, 256));
            en.put("valueHex", miniredis.debug.Trace.hex(e.value, 0, Math.min(e.value.length, 64)));
            en.put("expireAtUnixMs", e.expireAt == 0 ? -1 : toUnixMs(e.expireAt));
            en.put("ttlMs", e.expireAt == 0 ? -1 : (e.expireAt - mono.getAsLong()) / NS_PER_MS);
            en.put("expired", expired(e));
            en.put("cost", cost(e));
            en.put("lruRank", names(all.head(Integer.MAX_VALUE)).indexOf(key));
            m.put("entry", en);
        }
        return m;
    }

    /** key와 같은 버킷(지금 새 키가 들어갈 테이블 기준)에 떨어지는, 아직 없는 키 n개를 찾는다. */
    private Map<String, Object> collide(String key, int n) {
        long[] target = keys.debugLocate(key);
        int t = keys.isRehashing() ? 1 : 0;
        long idx = t == 0 ? target[1] : target[2];
        List<String> found = new ArrayList<>();
        for (int i = 0; i < 100_000 && found.size() < n; i++) {
            String c = key + "-" + i;
            long[] r = keys.debugLocate(c);
            if (r[3] < 0 && (t == 0 ? r[1] : r[2]) == idx) found.add(c);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("table", t);
        m.put("bucket", idx);
        m.put("candidates", found);
        return m;
    }

    /** 데이터에 들어가는 모든 입구의 첫 줄. 비용은 참조 비교 1번이다. */
    private void check() {
        // 스레드 소유권 검사
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Store는 " + owner.getName() + " 스레드만 쓸 수 있다");
        }
    }

    /** 변경을 AOF 레코드(멱등 형태)로 받을 곳. 복구 중에는 비워 둔다. */
    public void setLog(Consumer<byte[][]> log) {
        this.log = log;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public static boolean isWrite(String cmd) {
        return WRITES.contains(cmd);
    }

    public Object execute(byte[][] argv) {
        check();
        if (argv.length == 0) throw bad("빈 명령");
        String cmd = str(argv[0]).toUpperCase();
        if (!loading && (cmd.equals("SET") || cmd.equals("INCR"))) evictIfNeeded();
        return switch (cmd) {
            case "PING" -> { arity(argv, 1); yield "PONG"; }
            case "GET" -> { arity(argv, 2); Entry e = lookup(str(argv[1])); yield e == null ? null : touch(e).value; }
            case "EXISTS" -> { arity(argv, 2); Entry e = lookup(str(argv[1])); if (e != null) touch(e); yield e != null; }
            case "SET" -> set(argv);
            case "DEL" -> { arity(argv, 2); Entry e = lookup(str(argv[1])); if (e != null) remove(e); yield e == null ? 0L : 1L; }
            case "INCR" -> { arity(argv, 2); yield incr(str(argv[1])); }
            case "TTL" -> { arity(argv, 2); yield ttlSeconds(str(argv[1])); }
            case "EXPIRE" -> { arity(argv, 3); yield expireAt(str(argv[1]), mono.getAsLong() + parseLong(argv[2]) * 1000 * NS_PER_MS); }
            case "PEXPIREAT" -> { arity(argv, 3); yield expireAt(str(argv[1]), fromUnixMs(parseLong(argv[2]))); }
            case "PERSIST" -> { arity(argv, 2); yield persist(str(argv[1])); }
            case "KEYS" -> { arity(argv, 2); yield keys(str(argv[1])); }
            case "FLUSHALL" -> { arity(argv, 1); flushAll(); yield "OK"; }
            case "STATS" -> stats();
            default -> throw bad("알 수 없는 명령: " + cmd);
        };
    }

    // ---- 명령 ----

    /** SET k v [EX 초 | PXAT 유닉스ms] */
    private Object set(byte[][] argv) {
        if (argv.length != 3 && argv.length != 5) throw bad("SET 인자 수");
        String key = str(argv[1]);
        long at = 0;
        if (argv.length == 5) {
            String opt = str(argv[3]).toUpperCase();
            long n = parseLong(argv[4]);
            if (opt.equals("EX")) {
                if (n <= 0) throw bad("TTL은 1 이상");
                at = mono.getAsLong() + n * 1000 * NS_PER_MS;
            } else if (opt.equals("PXAT")) {
                at = fromUnixMs(n);
            } else {
                throw bad("SET 옵션: " + opt);
            }
            if (at <= mono.getAsLong()) { // 이미 지난 시각 (AOF 재실행 때 생김): 저장하지 않고 지운다
                Entry old = lookup(key);
                if (old != null) remove(old);
                return "OK";
            }
        }
        put(key, argv[2], at);
        return "OK";
    }

    private long incr(String key) {
        Entry e = lookup(key);
        long cur = 0;
        if (e != null) {
            String s = new String(e.value, StandardCharsets.UTF_8);
            if (!INTEGER.matcher(s).matches()) throw bad("값이 정수가 아님");
            try {
                cur = Long.parseLong(s);
            } catch (NumberFormatException x) {
                throw bad("64비트 정수 범위를 넘음");
            }
        }
        long next;
        try {
            next = Math.addExact(cur, 1);
        } catch (ArithmeticException x) {
            throw bad("INCR 결과가 64비트 범위를 넘음");
        }
        // AOF에는 "INCR"이 아니라 결과값 SET으로 남는다. 다시 실행해도 두 번 오르지 않는다
        put(key, Long.toString(next).getBytes(StandardCharsets.UTF_8), e == null ? 0 : e.expireAt);
        return next;
    }

    private long ttlSeconds(String key) {
        Entry e = lookup(key);
        if (e == null) return -2;
        if (e.expireAt == 0) return -1;
        long ms = (e.expireAt - mono.getAsLong()) / NS_PER_MS;
        return (ms + 500) / 1000;
    }

    private boolean expireAt(String key, long at) {
        Entry e = lookup(key);
        if (e == null) return false;
        if (at <= mono.getAsLong()) {
            remove(e);
            return true;
        }
        setExpire(e, at);
        touch(e);
        log("PEXPIREAT", key, Long.toString(toUnixMs(at)));
        return true;
    }

    private boolean persist(String key) {
        Entry e = lookup(key);
        if (e == null) return false;
        if (e.expireAt != 0) {
            setExpire(e, 0);
            log("PERSIST", key);
        }
        touch(e);
        return true;
    }

    private List<String> keys(String pattern) {
        List<String> out = new ArrayList<>();
        HashTable<Entry>.Cursor c = keys.cursor();
        while (c.step((k, e) -> { if (!expired(e) && glob(pattern, k)) out.add(k); })) { }
        return out;
    }

    private void flushAll() {
        newTables(); // 열려 있던 커서는 옛 테이블을 계속 돈다. 그 뒤 변경은 변경 로그(FLUSHALL)로 반영된다
        all.clear();
        ttl.clear();
        used = 0;
        log("FLUSHALL");
    }

    public Map<String, Object> stats() {
        check();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keys", (long) keys.size());
        m.put("memory", used);
        m.put("maxmemory", maxmemory);
        m.put("buckets", (long) keys.bucketCount());
        m.put("rehashing", keys.isRehashing());
        m.put("maxChain", (long) keys.maxChainLength());
        m.put("expired", expiredCount);
        m.put("evicted", evictedCount);
        return m;
    }

    // ---- 내부 공통 ----

    /** 키를 찾는다. 만료됐으면 여기서 지우고 "없음"으로 처리한다 (조회 시 검사). */
    private Entry lookup(String key) {
        Entry e = keys.get(key);
        if (e != null && expired(e)) {
            if (trace != null) trace.emit("expire.lazy", "key", key);
            remove(e);
            expiredCount++;
            return null;
        }
        return e;
    }

    private boolean expired(Entry e) {
        return e.expireAt != 0 && e.expireAt <= mono.getAsLong();
    }

    private Entry touch(Entry e) {
        all.moveToFront(e);
        if (e.expireAt != 0) ttl.moveToFront(e);
        return e;
    }

    private void put(String key, byte[] value, long at) {
        Entry e = lookup(key);
        if (e == null) {
            e = new Entry(key, key.getBytes(StandardCharsets.UTF_8).length, value);
            keys.set(key, e);
            all.pushFront(e);
            used += cost(e);
        } else {
            used -= cost(e);
            e.value = value;
            used += cost(e);
        }
        setExpire(e, at);
        touch(e);
        if (at == 0) log(bytes("SET"), bytes(key), value);
        else log(bytes("SET"), bytes(key), value, bytes("PXAT"), bytes(Long.toString(toUnixMs(at))));
    }

    private void setExpire(Entry e, long at) {
        used -= cost(e);
        if (e.expireAt == 0 && at != 0) {
            expires.set(e.key, e);
            ttl.pushFront(e);
        } else if (e.expireAt != 0 && at == 0) {
            expires.delete(e.key);
            ttl.remove(e);
        }
        e.expireAt = at;
        used += cost(e);
    }

    /** 삭제는 전부 여기 한 곳에서. 테이블 2개, LRU 리스트 2개, 사용량을 함께 정리하고 AOF에 DEL을 남긴다. */
    private void remove(Entry e) {
        keys.delete(e.key);
        if (e.expireAt != 0) {
            expires.delete(e.key);
            ttl.remove(e);
        }
        all.remove(e);
        used -= cost(e);
        log("DEL", e.key); // 만료·LRU로 지운 것도 남겨야 재시작 때 되살아나지 않는다
    }

    private static long cost(Entry e) {
        return e.keyBytes + e.value.length + ENTRY_OVERHEAD + (e.expireAt != 0 ? TTL_OVERHEAD : 0);
    }

    private void evictIfNeeded() {
        if (maxmemory <= 0) return;
        while (used > maxmemory) {
            Entry victim = switch (policy) {
                case ALLKEYS_LRU -> all.tail();
                case VOLATILE_LRU -> ttl.tail();
                case NOEVICTION -> null;
            };
            if (victim == null) throw new StoreException(507, "메모리 상한 초과");
            if (trace != null) trace.emit("evict", "key", victim.key, "memory", used, "maxmemory", maxmemory, "policy", policy.name());
            remove(victim);
            evictedCount++;
        }
    }

    // ---- 타이머 작업 (executor 반복문에서 호출) ----

    /** 만료 틱: TTL 키 20개를 무작위로 검사, 25% 넘게 만료됐으면 반복. 1회 최대 25ms. */
    public void activeExpireCycle() {
        check();
        long start = System.nanoTime();
        while (expires.size() > 0) {
            int n = Math.min(20, expires.size());
            int hit = 0;
            for (int i = 0; i < n; i++) {
                Entry e = expires.randomEntry();
                if (e != null && expired(e)) {
                    if (trace != null) trace.emit("expire.active", "key", e.key);
                    remove(e);
                    expiredCount++;
                    hit++;
                }
            }
            if (hit * 4 <= n || System.nanoTime() - start > 25 * NS_PER_MS) return;
        }
    }

    /** 작업 틱: 정해진 시간 동안 리해싱을 진행한다. */
    public void rehashFor(long nanos) {
        check();
        long deadline = System.nanoTime() + nanos;
        while ((keys.rehashStep() | expires.rehashStep()) && System.nanoTime() < deadline) { }
    }

    /** 영속성 코드가 받는 유일한 데이터 통로. 한 칸 옮기는 것 자체가 소유 검사를 하는 입구다. */
    public Cursor cursor() {
        check();
        return new Cursor(keys.cursor());
    }

    public final class Cursor {
        private final HashTable<Entry>.Cursor c;

        private Cursor(HashTable<Entry>.Cursor c) {
            this.c = c;
        }

        /** 버킷 1개. 만료된 엔트리는 건너뛴다. 다 돌았으면 false. */
        public boolean step(Consumer<Entry> visitor) {
            check();
            return c.step((k, e) -> { if (!expired(e)) visitor.accept(e); });
        }

        public void close() {
            check();
            c.close();
        }
    }

    // ---- 시간 변환: 메모리는 단조 시계, 파일은 유닉스 시각 ----

    public long toUnixMs(long expireAt) {
        return unixMs.getAsLong() + (expireAt - mono.getAsLong()) / NS_PER_MS;
    }

    private long fromUnixMs(long ms) {
        return mono.getAsLong() + (ms - unixMs.getAsLong()) * NS_PER_MS;
    }

    // ---- 도우미 ----

    private void log(String... parts) {
        byte[][] r = new byte[parts.length][];
        for (int i = 0; i < parts.length; i++) r[i] = bytes(parts[i]);
        log.accept(r);
    }

    private void log(byte[]... parts) {
        log.accept(parts);
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static long parseLong(byte[] b) {
        try {
            return Long.parseLong(str(b));
        } catch (NumberFormatException e) {
            throw bad("정수가 아님: " + str(b));
        }
    }

    private static void arity(byte[][] argv, int n) {
        if (argv.length != n) throw bad(str(argv[0]) + " 인자 수");
    }

    private static StoreException bad(String msg) {
        return new StoreException(400, msg);
    }

    /** `*`, `?`만 지원하는 패턴 비교. `*`를 만나면 위치를 기억해 두고 실패하면 거기서 한 글자씩 늘려 다시 맞춘다. */
    static boolean glob(String p, String s) {
        int pi = 0, si = 0, star = -1, mark = 0;
        while (si < s.length()) {
            if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
                pi++;
                si++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                star = pi++;
                mark = si;
            } else if (star != -1) {
                pi = star + 1;
                si = ++mark;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') pi++;
        return pi == p.length();
    }
}
