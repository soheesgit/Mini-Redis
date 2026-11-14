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
