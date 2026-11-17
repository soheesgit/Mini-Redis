package minidb.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BPlusTreeTest {

    @Test
    void 무작위_연산이_TreeMap과_같고_매번_불변식이_지켜진다() {
        for (int order = 3; order <= 5; order++) {
            Random rnd = new Random(1234 + order);
            BPlusTree t = new BPlusTree(order);
            TreeMap<Integer, Long> ref = new TreeMap<>();
            for (int step = 0; step < 2000; step++) {
                int key = rnd.nextInt(500);
                switch (rnd.nextInt(3)) {
                    case 0 -> { // 덮어쓰기 포함
                        long v = rnd.nextLong() & Long.MAX_VALUE;
                        t.put(key, v);
                        ref.put(key, v);
                    }
                    case 1 -> assertEquals(ref.remove(key) != null, t.remove(key), "order " + order + " step " + step);
                    default -> assertEquals(ref.getOrDefault(key, BPlusTree.NONE), t.get(key));
                }
                assertEquals(ref.size(), t.size());
                t.check();
            }
        }
    }

    @Test
    void 기본_차수로_100만건_오름차순_삽입_후_무작위_삭제() {
        int n = 1_000_000;
        BPlusTree t = new BPlusTree();
        for (int id = 1; id <= n; id++) {
            t.put(id, BPlusTree.loc(id / 100 + 1, id % 100));
        }
        assertEquals(n, t.size());
        for (int id = 1; id <= n; id++) {
            assertEquals(BPlusTree.loc(id / 100 + 1, id % 100), t.get(id));
        }
        assertEquals(BPlusTree.NONE, t.get(0));
        assertEquals(BPlusTree.NONE, t.get(n + 1));
        assertTrue(t.height() <= 4, "height=" + t.height());
        t.check();

        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i + 1;
        }
        Random rnd = new Random(7);
        for (int i = n - 1; i > 0; i--) { // Fisher-Yates
            int j = rnd.nextInt(i + 1);
            int tmp = ids[i];
            ids[i] = ids[j];
            ids[j] = tmp;
        }
        for (int id : ids) {
            assertTrue(t.remove(id));
        }
        assertEquals(0, t.size());
        assertEquals(1, t.height());
        t.check();
    }

    @Test
    void 범위_검색이_TreeMap_subMap과_같다() {
        Random rnd = new Random(99);
        BPlusTree t = new BPlusTree(4);
        TreeMap<Integer, Long> ref = new TreeMap<>();
        for (int i = 0; i < 1000; i++) {
            int k = rnd.nextInt(5000) - 2500;
            t.put(k, k * 10L + 100_000);
            ref.put(k, k * 10L + 100_000);
        }
        for (int i = 0; i < 200; i++) {
            int a = rnd.nextInt(6000) - 3000, b = rnd.nextInt(6000) - 3000;
            assertRange(t, ref, Math.min(a, b), Math.max(a, b));
        }
        // 경계가 정확히 키인 경우
        for (int k : new ArrayList<>(ref.keySet()).subList(0, 50)) {
            assertRange(t, ref, k, k);
            assertRange(t, ref, k, ref.lastKey());
            assertRange(t, ref, ref.firstKey(), k);
        }
        assertRange(t, ref, Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertRange(t, ref, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertRange(t, ref, 10, 9); // lo > hi: 아무것도 안 함
        assertRange(new BPlusTree(4), new TreeMap<>(), Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static void assertRange(BPlusTree t, TreeMap<Integer, Long> ref, int lo, int hi) {
        List<Long> got = new ArrayList<>();
        t.range(lo, hi, got::add);
        List<Long> want = lo > hi ? List.of() : new ArrayList<>(ref.subMap(lo, true, hi, true).values());
        assertEquals(want, got, "range " + lo + ".." + hi);
    }

    @Test
    void 루트가_자라고_줄어든다() {
        BPlusTree t = new BPlusTree(3);
        for (int k = 1; k <= 4; k++) {
            t.put(k, k);
        }
        assertEquals(2, t.height());
        t.check();
        for (int k = 1; k <= 4; k++) {
            assertTrue(t.remove(k));
            t.check();
        }
        assertEquals(1, t.height());
        assertEquals(0, t.size());
    }

    @Test
    void 위치값_왕복과_범위_검사() {
        long loc = BPlusTree.loc(40000, 65535); // int 시프트였다면 넘쳤을 page
        assertEquals(40000, BPlusTree.page(loc));
        assertEquals(65535, BPlusTree.slot(loc));
        assertTrue(loc > 0);
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.loc(1, 65536));
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.loc(1, -1));
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.loc(-1, 0));
    }

    @Test
    void 차수_3_미만은_거절한다() {
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(2));
    }
}
