package minidb.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BPlusTreeRegressionTest {

    @Test
    void missingKeysAndReplacementLeaveTheExpectedSize() {
        BPlusTree tree = new BPlusTree();
        assertEquals(BPlusTree.NONE, tree.get(0));
        assertFalse(tree.remove(0));
        assertEquals(List.of(), range(tree, -1, 1));

        tree.put(Integer.MIN_VALUE, 3);
        tree.put(0, 0);
        tree.put(Integer.MAX_VALUE, 5);
        tree.put(0, 8);

        assertEquals(3, tree.size());
        assertEquals(8, tree.get(0));
        assertEquals(List.of(3L, 8L, 5L), range(tree, Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(List.of(3L), range(tree, Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(List.of(5L), range(tree, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(List.of(), range(tree, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> tree.put(0, -1));
        assertEquals(8, tree.get(0));
        assertThrows(IllegalArgumentException.class, () -> new BPlusTree(2));
        tree.check();
    }

    @Test
    void underfullLeafBorrowsFromEitherSideBeforeMerging() {
        BPlusTree tree = new BPlusTree(4);
        for (int key = 1; key <= 5; key++) tree.put(key, key);
        assertEquals(2, tree.height());

        assertTrue(tree.remove(1));
        assertEquals(2, tree.height());
        assertEquals(List.of(2L, 3L, 4L, 5L), range(tree, 2, 5));
        tree.check();

        tree.put(0, 10);
        assertTrue(tree.remove(4));
        assertEquals(2, tree.height());
        assertEquals(List.of(10L, 2L, 3L, 5L), range(tree, 0, 5));
        tree.check();

        assertTrue(tree.remove(0));
        assertEquals(1, tree.height());
        assertEquals(List.of(2L, 3L, 5L), range(tree, 0, 5));
        tree.check();
    }

    @Test
    void rangeCrossesLeafBoundariesAndSkipsHolesAfterSplits() {
        BPlusTree tree = new BPlusTree(3);
        for (int key = 300; key >= 0; key -= 3) tree.put(key, 900L - key);
        assertTrue(tree.height() > 2);
        assertEquals(List.of(894L, 891L, 888L, 885L, 882L), range(tree, 4, 20));
        assertEquals(List.of(600L), range(tree, 299, 301));
        assertEquals(List.of(), range(tree, 301, Integer.MAX_VALUE));
        tree.check();

        // 양쪽 끝에서 번갈아 지워 여러 부모의 병합과 루트 축소를 유도한다.
        for (int pair = 0; pair < 50; pair++) {
            int low = 3 * pair, high = 300 - low;
            assertTrue(tree.remove(low));
            assertTrue(tree.remove(high));
            assertEquals(BPlusTree.NONE, tree.get(low));
            assertEquals(BPlusTree.NONE, tree.get(high));
            tree.check();
        }
        assertEquals(1, tree.height());
        assertEquals(List.of(750L), range(tree, 0, 300));
        assertTrue(tree.remove(150));
        assertEquals(0, tree.size());
        tree.check();
    }

    @Test
    void mixedOperationsAgreeWithTreeMapAtEvenAndOddCapacities() {
        for (int capacity : new int[] {3, 4, 7, BPlusTree.DEFAULT_ORDER}) {
            BPlusTree tree = new BPlusTree(capacity);
            TreeMap<Integer, Long> reference = new TreeMap<>();
            Random random = new Random(93230L + capacity);
            for (int i = 0; i < 25_000; i++) {
                int key = random.nextInt(-1_500, 1_501);
                switch (random.nextInt(7)) {
                    case 0, 1, 2 -> {
                        long location = random.nextLong(Long.MAX_VALUE);
                        tree.put(key, location);
                        reference.put(key, location);
                    }
                    case 3, 4 -> assertEquals(reference.remove(key) != null, tree.remove(key), "remove " + key);
                    default -> assertEquals(reference.getOrDefault(key, BPlusTree.NONE).longValue(), tree.get(key));
                }
                if (i % 127 == 0) {
                    int high = key + random.nextInt(250);
                    assertEquals(List.copyOf(reference.subMap(key, true, high, true).values()), range(tree, key, high));
                    assertEquals(reference.size(), tree.size());
                    tree.check();
                }
            }
            assertEquals(List.copyOf(reference.values()), range(tree, Integer.MIN_VALUE, Integer.MAX_VALUE));
            var keys = new ArrayList<>(reference.keySet());
            Collections.shuffle(keys, random);
            for (int key : keys) assertTrue(tree.remove(key));
            assertEquals(0, tree.size());
            assertEquals(1, tree.height());
            assertEquals(List.of(), range(tree, Integer.MIN_VALUE, Integer.MAX_VALUE));
            tree.check();
        }
    }

    @Test
    void packedLocationPreservesLargePageNumbersAndUnsignedSlots() {
        long last = BPlusTree.loc(Integer.MAX_VALUE, 65_535);
        assertEquals(Integer.MAX_VALUE, BPlusTree.page(last));
        assertEquals(65_535, BPlusTree.slot(last));
        assertEquals(40_000, BPlusTree.page(BPlusTree.loc(40_000, 7)));
        assertEquals(7, BPlusTree.slot(BPlusTree.loc(40_000, 7)));
        assertNotEquals(BPlusTree.NONE, BPlusTree.loc(0, 0));
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.loc(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.loc(1, -1));
        assertThrows(IllegalArgumentException.class, () -> BPlusTree.loc(1, 65_536));
    }

    private static List<Long> range(BPlusTree tree, int low, int high) {
        List<Long> locations = new ArrayList<>();
        tree.range(low, high, locations::add);
        return locations;
    }
}
