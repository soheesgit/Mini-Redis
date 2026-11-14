package miniredis.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import miniredis.persist.Records;
import miniredis.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 3·5·6단계: 엔진을 실제로 띄우고 닫았다가 다시 띄워서 데이터가 남는지 본다. */
class PersistenceTest {
    @TempDir
    Path dir;

    Engine start() throws IOException {
        return Engine.start(new Engine.Config(dir, 0, Store.Policy.NOEVICTION));
    }

    static String get(Engine e, String k) throws InterruptedException {
        Object v = e.call("GET", k);
        return v == null ? null : new String((byte[]) v, StandardCharsets.UTF_8);
    }

    @Test
    void restartRecoversData() throws Exception {
        try (Engine e = start()) {
            e.call("SET", "a", "1");
            e.call("SET", "b", "x", "EX", "100");
            e.call("INCR", "c");
            e.call("INCR", "c");
            e.call("SET", "d", "gone");
            e.call("DEL", "d");
        }
        try (Engine e = start()) {
            assertEquals("1", get(e, "a"));
            assertEquals("2", get(e, "c"));
            assertNull(get(e, "d"));
            long ttl = (Long) e.call("TTL", "b");
            assertTrue(ttl > 90 && ttl <= 100, "TTL이 재시작 시점부터 다시 세지면 안 된다: " + ttl);
        }
    }

    @Test
    void truncatedTailIsCutAndServerStarts() throws Exception {
        try (Engine e = start()) {
            e.call("SET", "a", "1");
        }
        Path incr = dir.resolve("incr.1.aof");
        long good = Files.size(incr);
        byte[] partial = Records.encode(new byte[][] {"SET".getBytes(), "b".getBytes(), "2".getBytes()});
        Files.write(incr, java.util.Arrays.copyOf(partial, 7), StandardOpenOption.APPEND); // 쓰다가 죽은 흉내
        try (Engine e = start()) {
            assertEquals("1", get(e, "a"));
            assertNull(get(e, "b"));
        }
        assertEquals(good, Files.size(incr)); // 잘린 꼬리를 잘라냈다
    }

    @Test
    void corruptionInMiddleRefusesStart() throws Exception {
        try (Engine e = start()) {
            e.call("SET", "a", "1");
            e.call("SET", "b", "2");
        }
        Path incr = dir.resolve("incr.1.aof");
        byte[] f = Files.readAllBytes(incr);
        f[6] ^= 1; // 첫 레코드 본문
        Files.write(incr, f);
        assertThrows(IOException.class, this::start);
    }

    @Test
    void expiredEntriesAreSkippedOnRecovery() throws Exception {
        try (Engine e = start()) {
            e.call("SET", "short", "v", "PXAT", Long.toString(System.currentTimeMillis() + 300));
            e.call("SET", "long", "v", "EX", "100");
        }
        Thread.sleep(400);
        try (Engine e = start()) {
            assertNull(get(e, "short"));
            assertEquals(1L, ((Map<?, ?>) e.call("STATS")).get("keys"));
        }
    }

    String lastKey, lastPrev; // 마지막 쓰기 되돌리기용 (BGSAVE 비교)
    int opsDuringJob;

    /** 압축·BGSAVE가 여러 틱에 걸쳐 도는 동안 쓰기가 섞여도 최종 상태가 맞아야 한다. */
    Map<String, String> writeWhileBackground(Engine e, String startCmd) throws Exception {
        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            e.call("SET", "k" + i, "v" + i);
            expected.put("k" + i, "v" + i);
        }
        e.call(startCmd);
        Random r = new Random(3);
        int ops = 0;
        while (!"none".equals(((Map<?, ?>) e.call("STATS")).get("background"))) {
            String k = "k" + r.nextInt(25_000);
            lastKey = k;
            lastPrev = expected.get(k);
            if (r.nextBoolean()) {
                e.call("SET", k, "w" + ops);
                expected.put(k, "w" + ops);
            } else {
                e.call("DEL", k);
                expected.remove(k);
            }
            ops++;
        }
        opsDuringJob = ops;
        return expected;
    }

    static void assertState(Engine e, Map<String, String> expected) throws Exception {
        assertEquals((long) expected.size(), ((Map<?, ?>) e.call("STATS")).get("keys"));
        for (var en : expected.entrySet()) assertEquals(en.getValue(), get(e, en.getKey()));
    }

    @Test
    void rewriteWithInterleavedWrites() throws Exception {
        Map<String, String> expected;
        try (Engine e = start()) {
            expected = writeWhileBackground(e, "REWRITEAOF");
            assertTrue(opsDuringJob > 10, "압축이 여러 틱에 걸쳐 돌아야 쓰기가 섞인다: " + opsDuringJob);
            assertState(e, expected);
        }
        assertEquals(List.of("base.2.rdb", "incr.2.aof"), Files.readAllLines(dir.resolve("manifest")));
        assertFalse(Files.exists(dir.resolve("incr.1.aof"))); // ④ 옛 파일 삭제
        try (Engine e = start()) {
            assertState(e, expected);
        }
    }

    @Test
    void bgsaveWithInterleavedWrites() throws Exception {
        Map<String, String> expected;
        try (Engine e = start()) {
            expected = writeWhileBackground(e, "BGSAVE");
            assertTrue(opsDuringJob > 10, "BGSAVE가 여러 틱에 걸쳐 돌아야 쓰기가 섞인다: " + opsDuringJob);
            assertState(e, expected);
        }
        // AOF를 지우고 dump.rdb만으로 시작
        for (String f : List.of("manifest", "incr.1.aof")) Files.delete(dir.resolve(f));
        Map<String, String> fromDump;
        try (Engine e = start()) {
            fromDump = new HashMap<>();
            for (int i = 0; i < 25_000; i++) {
                String v = get(e, "k" + i);
                if (v != null) fromDump.put("k" + i, v);
            }
        }
        // dump는 BGSAVE가 끝난 순간의 상태와 정확히 같아야 한다 (변경 로그 덕분).
        // 마지막 쓰기 1개는 끝나기 직전일 수도, 직후일 수도 있으므로 두 경우 중 하나와 같아야 한다
        Map<String, String> beforeLast = new HashMap<>(expected);
        if (lastPrev == null) beforeLast.remove(lastKey); else beforeLast.put(lastKey, lastPrev);
        assertTrue(fromDump.equals(expected) || fromDump.equals(beforeLast), "dump 상태가 BGSAVE 종료 시점과 다르다");
    }

    @Test
    void saveThenStartFromDumpOnly() throws Exception {
        try (Engine e = start()) {
            e.call("SET", "a", "1");
            e.call("SAVE");
        }
        for (String f : List.of("manifest", "incr.1.aof")) Files.delete(dir.resolve(f));
        try (Engine e = start()) {
            assertEquals("1", get(e, "a"));
        }
        // dump로 시작하면 바로 base를 만들어 둔다. 다음 재시작은 manifest만 읽어도 데이터가 있다
        Files.delete(dir.resolve("dump.rdb"));
        try (Engine e = start()) {
            assertEquals("1", get(e, "a"));
        }
    }

    @Test
    void evictedKeysStayGoneAfterRestart() throws Exception {
        long cost = 2 + 1 + Store.ENTRY_OVERHEAD; // "kN" 2바이트 + 값 1바이트
        try (Engine e = Engine.start(new Engine.Config(dir, 5 * cost, Store.Policy.ALLKEYS_LRU))) {
            for (int i = 0; i < 9; i++) e.call("SET", "k" + i, "v");
            assertTrue((Long) ((Map<?, ?>) e.call("STATS")).get("evicted") > 0);
            assertNull(get(e, "k0"));
        }
        try (Engine e = start()) {
            assertNull(get(e, "k0")); // DEL로 기록했으므로 되살아나지 않는다
            assertEquals("v", get(e, "k8"));
        }
    }
}
