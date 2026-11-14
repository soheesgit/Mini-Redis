package miniredis.store;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StoreTest {
    static final long SEC = 1_000_000_000L;
    final long[] mono = {1_000 * SEC};          // 가짜 단조 시계 (ns). 테스트에서 직접 움직인다
    final long[] unix = {1_700_000_000_000L};   // 가짜 유닉스 시각 (ms)
    final List<String> records = new ArrayList<>();

    Store store(long maxmemory, Store.Policy policy) {
        Store s = new Store(new byte[16], new Random(1), () -> mono[0], () -> unix[0], maxmemory, policy);
        s.setLog(r -> {
            List<String> parts = new ArrayList<>();
            for (byte[] b : r) parts.add(new String(b, StandardCharsets.UTF_8));
            records.add(String.join(" ", parts));
        });
        return s;
    }

    Store store() {
        return store(0, Store.Policy.NOEVICTION);
    }

    static Object run(Store s, String... args) {
        byte[][] argv = new byte[args.length][];
        for (int i = 0; i < args.length; i++) argv[i] = args[i].getBytes(StandardCharsets.UTF_8);
        return s.execute(argv);
    }

    static String str(Object v) {
        return v == null ? null : new String((byte[]) v, StandardCharsets.UTF_8);
    }

    void advance(long ns) {
        mono[0] += ns;
        unix[0] += ns / 1_000_000;
    }

    @Test
    void setGetDel() {
        Store s = store();
        assertEquals("OK", run(s, "SET", "k", "v"));
        assertEquals("v", str(run(s, "GET", "k")));
        assertEquals(true, run(s, "EXISTS", "k"));
        assertEquals(1L, run(s, "DEL", "k"));
        assertEquals(0L, run(s, "DEL", "k"));
        assertNull(run(s, "GET", "k"));
    }

    @Test
    void ttlJustBeforeAndAfter() {
        Store s = store();
        run(s, "SET", "k", "v", "EX", "10");
        assertEquals(10L, run(s, "TTL", "k"));
        advance(10 * SEC - 1);
        assertEquals("v", str(run(s, "GET", "k"))); // 1ns 전: 아직 있다
        advance(1);
        assertNull(run(s, "GET", "k"));             // 정각: 없다. 만료된 값은 절대 반환하지 않는다
        assertEquals(1L, s.stats().get("expired"));
        assertEquals("DEL k", records.get(records.size() - 1)); // 조회 시 삭제도 AOF에 DEL로 남는다
    }

    @Test
    void ttlReturnValues() {
        Store s = store();
        assertEquals(-2L, run(s, "TTL", "없음"));
        run(s, "SET", "k", "v");
        assertEquals(-1L, run(s, "TTL", "k"));
        assertEquals(true, run(s, "EXPIRE", "k", "5"));
        assertEquals(5L, run(s, "TTL", "k"));
        assertEquals(false, run(s, "EXPIRE", "없음", "5"));
        assertEquals(true, run(s, "PERSIST", "k"));
        assertEquals(-1L, run(s, "TTL", "k"));
    }

    @Test
    void putWithoutTtlRemovesTtl() {
        Store s = store();
        run(s, "SET", "k", "v", "EX", "10");
        run(s, "SET", "k", "v2");
        assertEquals(-1L, run(s, "TTL", "k"));
        advance(20 * SEC);
        assertEquals("v2", str(run(s, "GET", "k")));
    }

    @Test
    void periodicCleanupRemovesExpiredWithoutLookup() {
        Store s = store();
        for (int i = 0; i < 200; i++) run(s, "SET", "t" + i, "v", "EX", "1");
        for (int i = 0; i < 50; i++) run(s, "SET", "p" + i, "v");
        advance(2 * SEC);
        s.activeExpireCycle();
        assertEquals(50L, s.stats().get("keys")); // 만료 키는 조회하지 않아도 청소된다
        assertEquals(200L, s.stats().get("expired"));
    }

    @Test
    void incr() {
        Store s = store();
        assertEquals(1L, run(s, "INCR", "c"));
        assertEquals(2L, run(s, "INCR", "c"));
        run(s, "SET", "x", "abc");
        assertEquals(400, assertThrows(StoreException.class, () -> run(s, "INCR", "x")).code);
        run(s, "SET", "max", Long.toString(Long.MAX_VALUE));
        assertEquals(400, assertThrows(StoreException.class, () -> run(s, "INCR", "max")).code);
        run(s, "SET", "t", "5", "EX", "10");
        assertEquals(6L, run(s, "INCR", "t"));
        assertEquals(10L, run(s, "TTL", "t")); // INCR은 TTL을 유지한다
    }

    @Test
    void idempotentRecords() {
        Store s = store();
        run(s, "SET", "a", "1", "EX", "10");
        run(s, "SET", "b", "5");
        run(s, "INCR", "b");
        run(s, "EXPIRE", "b", "20");
        run(s, "PERSIST", "b");
        run(s, "DEL", "a");
        run(s, "FLUSHALL");
        long u = unix[0];
        assertEquals(List.of(
            "SET a 1 PXAT " + (u + 10_000),   // 상대 TTL → 절대 시각
            "SET b 5",
            "SET b 6",                        // INCR → 결과값 SET
            "PEXPIREAT b " + (u + 20_000),
            "PERSIST b",
            "DEL a",
            "FLUSHALL"), records);
    }

    @Test
    void replayingPastPxatDoesNotStore() {
        Store s = store();
        run(s, "SET", "k", "v", "PXAT", Long.toString(unix[0] - 1));
        assertNull(run(s, "GET", "k"));
        assertEquals(0L, s.stats().get("keys"));
    }

    @Test
    void otherThreadIsRejected() throws Exception {
        Store s = store();
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            for (Runnable r : new Runnable[] {
                () -> run(s, "GET", "k"), s::activeExpireCycle, () -> s.rehashFor(1), s::cursor, s::stats}) {
                try {
                    r.run();
                    err.set(new AssertionError("예외가 안 났다"));
                } catch (IllegalStateException expected) {
                    // 정상
                } catch (Throwable x) {
                    err.set(x);
                }
            }
        });
        t.start();
        t.join();
        assertNull(err.get());
    }

    // ---- 5단계: 메모리 상한과 LRU ----

    static final long COST = 1 + 1 + Store.ENTRY_OVERHEAD; // 키 1바이트 + 값 1바이트 + C

    @Test
    void allkeysLruEvictsLeastRecentlyUsed() {
        Store s = store(3 * COST, Store.Policy.ALLKEYS_LRU);
        for (String k : new String[] {"a", "b", "c", "d"}) run(s, "SET", k, "v"); // d를 넣은 뒤 상한 초과
        run(s, "GET", "a");          // a를 최근으로
        run(s, "SET", "e", "v");     // 쓰기 전에 검사 → 가장 오래 안 쓴 b 제거
        assertNull(run(s, "GET", "b"));
        assertEquals("v", str(run(s, "GET", "a")));
        assertEquals(1L, s.stats().get("evicted"));
        assertTrue(records.contains("DEL b")); // 기록하지 않으면 재시작할 때 되살아난다
    }

    @Test
    void volatileLruEvictsOnlyTtlKeys() {
        Store s = store(3 * COST, Store.Policy.VOLATILE_LRU);
        run(s, "SET", "p", "v");
        run(s, "SET", "t", "v", "EX", "100");
        run(s, "SET", "q", "v");     // 여기서 상한 초과
        run(s, "SET", "r", "v");     // 쓰기 전에 검사 → TTL 키 t만 대상이 된다
        run(s, "SET", "s", "v");
        assertNull(run(s, "GET", "t"));
        assertEquals("v", str(run(s, "GET", "p")));
        // 남은 키에 TTL이 없으므로 더 지울 수 없다 → 507
        assertEquals(507, assertThrows(StoreException.class, () -> run(s, "SET", "u", "v")).code);
    }

    @Test
    void noevictionRejectsWrites() {
        Store s = store(COST, Store.Policy.NOEVICTION);
        run(s, "SET", "a", "v");
        run(s, "SET", "b", "v");
        assertEquals(507, assertThrows(StoreException.class, () -> run(s, "SET", "c", "v")).code);
        assertEquals(1L, run(s, "DEL", "a")); // 삭제는 거부하지 않는다
    }

    @Test
    void memoryAccounting() {
        Store s = store();
        run(s, "SET", "한", "abc");                   // 키 3바이트 + 값 3바이트
        assertEquals(3 + 3 + Store.ENTRY_OVERHEAD, s.stats().get("memory"));
        run(s, "EXPIRE", "한", "10");
        assertEquals(3 + 3 + Store.ENTRY_OVERHEAD + Store.TTL_OVERHEAD, s.stats().get("memory"));
        run(s, "DEL", "한");
        assertEquals(0L, s.stats().get("memory"));
    }

    @Test
    void keysPattern() {
        Store s = store();
        for (String k : new String[] {"user:1", "user:22", "item:1"}) run(s, "SET", k, "v");
        run(s, "SET", "user:9", "v", "EX", "1");
        advance(2 * SEC);
        @SuppressWarnings("unchecked")
        List<String> keys = (List<String>) run(s, "KEYS", "user:*");
        assertEquals(2, keys.size()); // 만료된 user:9는 빠진다
        assertTrue(Store.glob("user:?", "user:1"));
        assertFalse(Store.glob("user:?", "user:22"));
        assertTrue(Store.glob("*:*1", "item:1"));
        assertTrue(Store.glob("*", ""));
    }
}
