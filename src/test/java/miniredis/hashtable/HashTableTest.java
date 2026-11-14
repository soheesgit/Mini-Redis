package miniredis.hashtable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HashTableTest {
    private static final byte[] SEED = new byte[16]; // 테스트에서는 시드를 고정 주입

    private static HashTable<String> newTable() {
        return new HashTable<>(new SipHash(SEED), new Random(42));
    }

    private static void finishRehash(HashTable<?> ht) {
        while (ht.rehashStep()) { }
        assertFalse(ht.isRehashing());
    }

    @Test
    void setGetDelete() {
        HashTable<String> ht = newTable();
        assertNull(ht.set("a", "1"));
        assertEquals("1", ht.set("a", "2")); // 덮어쓰면 이전 값
        assertEquals("2", ht.get("a"));
        assertNull(ht.get("없음"));
        assertEquals("2", ht.delete("a"));
        assertNull(ht.delete("a"));
        assertEquals(0, ht.size());
    }

    @Test
    void sameBucketChain() {
        HashTable<String> ht = newTable();
        // 4버킷 기준 0번 버킷에 떨어지는 키 3개를 찾는다 (키 3개면 확장 전이다)
        List<String> keys = new ArrayList<>();
        for (int i = 0; keys.size() < 3; i++) {
            if ((ht.hash("k" + i) & 3) == 0) keys.add("k" + i);
        }
        for (String k : keys) ht.set(k, k);
        assertEquals(4, ht.bucketCount());
        assertEquals(keys.get(1), ht.delete(keys.get(1))); // 체인 가운데 삭제
        assertEquals(keys.get(0), ht.get(keys.get(0)));
        assertEquals(keys.get(2), ht.get(keys.get(2)));
        assertNull(ht.get(keys.get(1)));
    }

    @Test
    void expandsWhenKeysReachBuckets() {
        HashTable<String> ht = newTable();
        for (int i = 0; i < 3; i++) ht.set("k" + i, "v");
        assertFalse(ht.isRehashing());
        ht.set("k3", "v"); // 키 4 >= 버킷 4
        assertTrue(ht.isRehashing());
        finishRehash(ht);
        assertEquals(8, ht.bucketCount());
        for (int i = 0; i < 4; i++) assertEquals("v", ht.get("k" + i));
    }

    @Test
    void shrinksWhenSparse() {
        HashTable<String> ht = newTable();
        for (int i = 0; i < 100; i++) { ht.set("k" + i, "v"); finishRehash(ht); }
        assertEquals(128, ht.bucketCount());
        for (int i = 0; i < 99; i++) {
            ht.delete("k" + i);
            finishRehash(ht);
            if (ht.size() == 15) assertEquals(16, ht.bucketCount()); // 15 < 128/8 → 15보다 큰 가장 작은 2의 거듭제곱
            if (ht.size() == 2) assertEquals(16, ht.bucketCount());  // 2 < 16/8 은 거짓 → 그대로
        }
        assertEquals(4, ht.bucketCount()); // 1 < 16/8 → 최소 크기 4
        assertEquals("v", ht.get("k99"));
    }

    @Test
    void matchesHashMapDuringRehashing() {
        // 무작위 조작을 HashMap과 나란히 하며 매번 비교한다. 확장·축소·리해싱이 여러 번 섞인다
        HashTable<String> ht = newTable();
        Map<String, String> model = new HashMap<>();
        Random r = new Random(7);
        int sawRehashing = 0;
        int maxBuckets = 0;
        for (int i = 0; i < 50_000; i++) {
            String k = "key" + r.nextInt(2000);
            int op = i < 25_000 ? r.nextInt(3) : 1 + r.nextInt(2); // 후반엔 추가 없이 삭제·조회만 → 축소
            switch (op) {
                case 0 -> assertEquals(model.put(k, "v" + i), ht.set(k, "v" + i));
                case 1 -> assertEquals(model.remove(k), ht.delete(k));
                default -> assertEquals(model.get(k), ht.get(k));
            }
            if (ht.isRehashing()) sawRehashing++;
            maxBuckets = Math.max(maxBuckets, ht.bucketCount());
            assertEquals(model.size(), ht.size());
        }
        assertTrue(sawRehashing > 0);
        assertTrue(ht.bucketCount() < maxBuckets, "축소가 한 번도 안 일어남");
        for (var e : model.entrySet()) assertEquals(e.getValue(), ht.get(e.getKey()));
    }

    @Test
    void cursorVisitsEachKeyOnceAndFreezesResize() {
        HashTable<String> ht = newTable();
        for (int i = 0; i < 100; i++) ht.set("k" + i, "v");
        finishRehash(ht);
        int buckets = ht.bucketCount();

        HashTable<String>.Cursor c = ht.cursor();
        Map<String, Integer> seen = new HashMap<>();
        int steps = 0;
        while (c.step((k, v) -> seen.merge(k, 1, Integer::sum))) {
            // 순회 사이에 다른 명령이 끼어든다: 새 키 추가(확장 조건을 넘김), 기존 키 삭제
            if (steps++ % 3 == 0) ht.set("new" + steps, "v");
            if (steps == 10) ht.delete("k99");
            assertEquals(buckets, ht.bucketCount());
            assertFalse(ht.isRehashing()); // 확장 조건을 넘어도 시작하지 않는다
        }
        assertTrue(seen.values().stream().allMatch(n -> n == 1), "중복 방문");
        assertTrue(ht.isRehashing()); // 닫히자마자 미뤄 둔 확장을 시작
    }

    @Test
    void cursorKeysPresentWholeTimeAreAllVisited() {
        HashTable<String> ht = newTable();
        for (int i = 0; i < 100; i++) ht.set("k" + i, "v");
        finishRehash(ht);
        Set<String> seen = new HashSet<>();
        HashTable<String>.Cursor c = ht.cursor();
        while (c.step((k, v) -> seen.add(k))) {
            ht.set("x" + seen.size(), "v");
        }
        for (int i = 0; i < 100; i++) assertTrue(seen.contains("k" + i), "누락: k" + i);
    }

    @Test
    void cursorOpenedMidRehashCoversBothTables() {
        HashTable<String> ht = newTable();
        for (int i = 0; i < 63; i++) { ht.set("k" + i, "v"); finishRehash(ht); }
        ht.set("k63", "v"); // 키 64 >= 버킷 64 → 확장 시작
        assertTrue(ht.isRehashing());
        ht.rehashStep();
        ht.rehashStep(); // 일부만 옮긴 상태
        assertTrue(ht.isRehashing());

        Set<String> seen = new HashSet<>();
        HashTable<String>.Cursor c = ht.cursor();
        while (c.step((k, v) -> assertTrue(seen.add(k), "중복: " + k))) {
            assertFalse(ht.rehashStep()); // 커서가 열려 있으면 리해싱 정지
            ht.get("k1");                 // 조회해도 리해싱이 진행되지 않는다
        }
        assertEquals(64, seen.size());
        assertTrue(ht.isRehashing());
        finishRehash(ht);
    }

    @Test
    void cursorCloseEarlyReleasesFreeze() {
        HashTable<String> ht = newTable();
        for (int i = 0; i < 3; i++) ht.set("k" + i, "v");
        HashTable<String>.Cursor c = ht.cursor();
        c.step((k, v) -> { });
        ht.set("k3", "v");
        assertFalse(ht.isRehashing());
        c.close();
        c.close(); // 두 번 닫아도 카운트가 꼬이지 않는다
        assertTrue(ht.isRehashing());
    }

    @Test
    void randomEntry() {
        HashTable<String> ht = newTable();
        assertNull(ht.randomEntry());
        for (int i = 0; i < 63; i++) { ht.set("k" + i, "k" + i); finishRehash(ht); }
        ht.set("k63", "k63");
        ht.rehashStep(); // 리해싱 중: 두 테이블 모두에 키가 있다
        assertTrue(ht.isRehashing());

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(ht.randomEntry()); // get을 부르면 리해싱이 진행되므로 부르지 않는다
        }
        assertTrue(ht.isRehashing());
        assertEquals(64, seen.size()); // 어느 테이블에 있든 모든 키가 뽑힌다
    }

    @Test
    void koreanKeys() {
        HashTable<String> ht = newTable();
        ht.set("한글키", "값");
        assertEquals("값", ht.get("한글키"));
    }
}
