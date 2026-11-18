package minidb.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

/**
 * 메모리 B+ 트리 (설계 2절). 키는 자동 id(int), 값은 행 위치 {@link #loc}(long).
 * 키는 리프에만 값과 함께 있고, 안쪽 노드는 길 안내용 구분 키만 가진다.
 * 리프끼리는 next로 이어져 있어 범위 검색은 리프 하나를 찾은 뒤 옆으로만 걷는다.
 *
 * <p>차수 o: 노드당 키 최대 o개, 루트가 아니면 최소 o/2개. 안쪽 노드는 자식이 키 수 + 1개.
 * 구분 키 sep[i]: 자식 i의 키는 모두 sep[i-1] ≤ key < sep[i].
 * 동시성은 다루지 않는다 (실행기가 이미 synchronized).
 */
public final class BPlusTree {

    public static final int DEFAULT_ORDER = 64;
    /** get이 못 찾았을 때. 위치값은 page ≥ 0, slot ≥ 0이라 음수가 될 수 없다 */
    public static final long NONE = -1;

    private abstract static class Node {
        final int[] keys; // 분할 직전 o+1개까지 잠깐 담으므로 o+1칸
        int n;

        Node(int order) {
            keys = new int[order + 1];
        }
    }

    private static final class Leaf extends Node {
        final long[] vals;
        Leaf next;

        Leaf(int order) {
            super(order);
            vals = new long[order + 1];
        }
    }

    private static final class Inner extends Node {
        final Node[] kids; // 키 + 1개, 분할 직전엔 o+2개
        Inner(int order) {
            super(order);
            kids = new Node[order + 2];
        }
    }

    private final int order;
    private Node root;
    private int size;
    private int height = 1;
    /** insert가 분할됐을 때 부모로 올릴 키. 반환값은 새 오른쪽 노드 */
    private int splitKey;

    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("차수는 3 이상: " + order);
        }
        this.order = order;
        this.root = new Leaf(order);
    }

    public int size() {
        return size;
    }

    /** 리프만 있으면 1 */
    public int height() {
        return height;
    }

    // ---- 위치값 ----

    /** (long)으로 먼저 넓힌 뒤 민다. int에서 밀면 page 32768 이상에서 넘친다 */
    public static long loc(int page, int slot) {
        if (page < 0 || slot < 0 || slot > 0xFFFF) {
            throw new IllegalArgumentException("위치 범위 밖: page=" + page + ", slot=" + slot);
        }
        return (long) page << 16 | slot;
    }

    public static int page(long loc) {
        return (int) (loc >>> 16);
    }

    public static int slot(long loc) {
        return (int) (loc & 0xFFFF);
    }

    // ---- 조회 ----

    public long get(int key) {
        Leaf lf = findLeaf(key);
        int r = Arrays.binarySearch(lf.keys, 0, lf.n, key);
        return r >= 0 ? lf.vals[r] : NONE;
    }

    /** lo ≤ key ≤ hi 인 값을 키 오름차순으로 넘긴다 */
    public void range(int lo, int hi, LongConsumer out) {
        scanRange(lo, hi, location -> {
            out.accept(location);
            return true;
        });
    }

    @FunctionalInterface
    public interface RangeVisitor<E extends Exception> {
        boolean visit(long location) throws E;
    }

    /** 범위의 값을 키 오름차순으로 방문하고, 방문자가 false를 반환하면 멈춘다. */
    public <E extends Exception> void scanRange(int lo, int hi, RangeVisitor<E> visitor) throws E {
        if (lo > hi) {
            return;
        }
        Leaf lf = findLeaf(lo);
        // lo가 리프의 빈틈에 걸리면 이 리프는 전부 lo보다 작을 수 있다 → 시작 칸이 n이면 다음 리프로 넘어간다
        int r = Arrays.binarySearch(lf.keys, 0, lf.n, lo);
        int i = r >= 0 ? r : -r - 1;
        while (lf != null) {
            for (; i < lf.n; i++) {
                if (lf.keys[i] > hi) {
                    return;
                }
                if (!visitor.visit(lf.vals[i])) {
                    return;
                }
            }
            lf = lf.next;
            i = 0;
        }
    }

    private Leaf findLeaf(int key) {
        Node node = root;
        while (node instanceof Inner in) {
            node = in.kids[childIndex(in, key)];
        }
        return (Leaf) node;
    }

    /** key가 속한 자식 번호 = key 이하인 구분 키의 개수 */
    private static int childIndex(Inner in, int key) {
        int r = Arrays.binarySearch(in.keys, 0, in.n, key);
        return r >= 0 ? r + 1 : -r - 1;
    }

    // ---- 삽입 ----

    public void put(int key, long value) {
        if (value < 0) { // 음수는 get의 NONE(-1)과 구분할 수 없다
            throw new IllegalArgumentException("값은 0 이상: " + value);
        }
        Node sibling = insert(root, key, value);
        if (sibling != null) { // 루트가 쪼개졌다 → 새 루트를 얹어 높이 +1
            Inner nr = new Inner(order);
            nr.kids[0] = root;
            nr.kids[1] = sibling;
            nr.keys[0] = splitKey;
            nr.n = 1;
            root = nr;
            height++;
        }
    }

    /** 쪼개졌으면 새 오른쪽 노드를 돌려주고 올릴 키는 splitKey에 둔다. 아니면 null */
    private Node insert(Node node, int key, long value) {
        if (node instanceof Leaf lf) {
            int r = Arrays.binarySearch(lf.keys, 0, lf.n, key);
            if (r >= 0) {
                lf.vals[r] = value; // 덮어쓰기: size 그대로
                return null;
            }
            int at = -r - 1;
            System.arraycopy(lf.keys, at, lf.keys, at + 1, lf.n - at);
            System.arraycopy(lf.vals, at, lf.vals, at + 1, lf.n - at);
            lf.keys[at] = key;
            lf.vals[at] = value;
            lf.n++;
            size++;
            if (lf.n <= order) {
                return null;
            }
            // 리프 분할: 왼쪽 (o+1)/2개, 나머지는 오른쪽. 오른쪽 첫 키는 리프에 남기고 부모엔 복사
            int keep = (order + 1) / 2;
            Leaf right = new Leaf(order);
            right.n = lf.n - keep;
            System.arraycopy(lf.keys, keep, right.keys, 0, right.n);
            System.arraycopy(lf.vals, keep, right.vals, 0, right.n);
            lf.n = keep;
            right.next = lf.next;
            lf.next = right;
            splitKey = right.keys[0];
            return right;
        }
        Inner in = (Inner) node;
        int i = childIndex(in, key);
        Node sibling = insert(in.kids[i], key, value);
        if (sibling == null) {
            return null;
        }
        System.arraycopy(in.keys, i, in.keys, i + 1, in.n - i);
        System.arraycopy(in.kids, i + 1, in.kids, i + 2, in.n - i);
        in.keys[i] = splitKey;
        in.kids[i + 1] = sibling;
        in.n++;
        if (in.n <= order) {
            return null;
        }
        // 안쪽 분할: 가운데 키는 위로 이동 (아래 노드엔 남기지 않는다)
        int mid = in.n / 2;
        int up = in.keys[mid];
        Inner right = new Inner(order);
        right.n = in.n - mid - 1;
        System.arraycopy(in.keys, mid + 1, right.keys, 0, right.n);
        System.arraycopy(in.kids, mid + 1, right.kids, 0, right.n + 1);
        Arrays.fill(in.kids, mid + 1, in.n + 1, null); // 넘겨준 자식 참조를 끊어 메모리 회수
        in.n = mid;
        splitKey = up;
        return right;
    }

    // ---- 삭제 ----

    public boolean remove(int key) {
        if (!delete(root, key)) {
            return false;
        }
        size--;
        if (root instanceof Inner in && in.n == 0) { // 자식 하나만 남은 루트는 필요 없다 → 높이 -1
            root = in.kids[0];
            height--;
        }
        return true;
    }

    private boolean delete(Node node, int key) {
        if (node instanceof Leaf lf) {
            int r = Arrays.binarySearch(lf.keys, 0, lf.n, key);
            if (r < 0) {
                return false;
            }
            System.arraycopy(lf.keys, r + 1, lf.keys, r, lf.n - r - 1);
            System.arraycopy(lf.vals, r + 1, lf.vals, r, lf.n - r - 1);
            lf.n--;
            return true;
        }
        Inner in = (Inner) node;
        int i = childIndex(in, key);
        if (!delete(in.kids[i], key)) {
            return false;
        }
        if (in.kids[i].n < order / 2) {
            rebalance(in, i);
        }
        return true;
    }

    /** 부모 p의 i번 자식이 최소보다 적다: 왼쪽 → 오른쪽 형제에게 빌리고, 안 되면 합친다 */
    private void rebalance(Inner p, int i) {
        int min = order / 2;
        Node left = i > 0 ? p.kids[i - 1] : null;
        Node right = i < p.n ? p.kids[i + 1] : null;
        if (left != null && left.n > min) {
            borrowFromLeft(p, i);
        } else if (right != null && right.n > min) {
            borrowFromRight(p, i);
        } else if (left != null) {
            merge(p, i - 1);
        } else {
            merge(p, i);
        }
    }

    private void borrowFromLeft(Inner p, int i) {
        Node c = p.kids[i], l = p.kids[i - 1];
        if (c instanceof Leaf cl) {
            Leaf ll = (Leaf) l;
            System.arraycopy(cl.keys, 0, cl.keys, 1, cl.n);
            System.arraycopy(cl.vals, 0, cl.vals, 1, cl.n);
            cl.keys[0] = ll.keys[ll.n - 1];
            cl.vals[0] = ll.vals[ll.n - 1];
            cl.n++;
            ll.n--;
            p.keys[i - 1] = cl.keys[0]; // 리프는 키가 직접 옮겨가고 부모 구분 키는 새 첫 키로 고친다
        } else {
            // 안쪽은 회전: 부모 구분 키가 내려오고, 왼쪽 마지막 키가 부모로 올라간다
            Inner ci = (Inner) c, li = (Inner) l;
            System.arraycopy(ci.keys, 0, ci.keys, 1, ci.n);
            System.arraycopy(ci.kids, 0, ci.kids, 1, ci.n + 1);
            ci.keys[0] = p.keys[i - 1];
            ci.kids[0] = li.kids[li.n];
            li.kids[li.n] = null;
            p.keys[i - 1] = li.keys[li.n - 1];
            ci.n++;
            li.n--;
        }
    }

    private void borrowFromRight(Inner p, int i) {
        Node c = p.kids[i], r = p.kids[i + 1];
        if (c instanceof Leaf cl) {
            Leaf rl = (Leaf) r;
            cl.keys[cl.n] = rl.keys[0];
            cl.vals[cl.n] = rl.vals[0];
            cl.n++;
            System.arraycopy(rl.keys, 1, rl.keys, 0, rl.n - 1);
            System.arraycopy(rl.vals, 1, rl.vals, 0, rl.n - 1);
            rl.n--;
            p.keys[i] = rl.keys[0];
        } else {
            Inner ci = (Inner) c, ri = (Inner) r;
            ci.keys[ci.n] = p.keys[i];
            ci.kids[ci.n + 1] = ri.kids[0];
            ci.n++;
            p.keys[i] = ri.keys[0];
            System.arraycopy(ri.keys, 1, ri.keys, 0, ri.n - 1);
            System.arraycopy(ri.kids, 1, ri.kids, 0, ri.n);
            ri.kids[ri.n] = null;
            ri.n--;
        }
    }

    /**
     * p의 j+1번 자식을 j번 자식에 합치고 p에서 구분 키 j와 자식 j+1을 뺀다.
     * 합친 노드가 o를 넘지 않는 이유: 리프는 (o/2 - 1) + o/2 ≤ o,
     * 안쪽은 (o/2 - 1) + o/2 + 내려받은 구분 키 1 ≤ o.
     */
    private void merge(Inner p, int j) {
        Node a = p.kids[j], b = p.kids[j + 1];
        if (a instanceof Leaf al) {
            Leaf bl = (Leaf) b;
            System.arraycopy(bl.keys, 0, al.keys, al.n, bl.n);
            System.arraycopy(bl.vals, 0, al.vals, al.n, bl.n);
            al.n += bl.n;
            al.next = bl.next; // 사라지는 리프를 건너뛰고 연결 리스트를 다시 잇는다
        } else {
            Inner ai = (Inner) a, bi = (Inner) b;
            ai.keys[ai.n] = p.keys[j]; // 부모 구분 키를 내려 받아야 두 노드 사이 범위가 이어진다
            System.arraycopy(bi.keys, 0, ai.keys, ai.n + 1, bi.n);
            System.arraycopy(bi.kids, 0, ai.kids, ai.n + 1, bi.n + 1);
            ai.n += bi.n + 1;
        }
        System.arraycopy(p.keys, j + 1, p.keys, j, p.n - j - 1);
        System.arraycopy(p.kids, j + 2, p.kids, j + 1, p.n - j - 1);
        p.n--;
        p.kids[p.n + 1] = null;
    }

    // ---- 불변식 검사 (테스트용) ----

    void check() {
        List<Leaf> leaves = new ArrayList<>();
        int depth = checkNode(root, Long.MIN_VALUE, Long.MAX_VALUE, 1, true, leaves);
        if (depth != height) {
            throw new AssertionError("height=" + height + "인데 리프 깊이는 " + depth);
        }
        int count = 0;
        long prev = Long.MIN_VALUE;
        Leaf lf = leaves.get(0);
        for (int li = 0; lf != null; li++, lf = lf.next) {
            if (li >= leaves.size() || leaves.get(li) != lf) {
                throw new AssertionError("next 연결이 트리 순서와 다름: " + li + "번째 리프");
            }
            for (int i = 0; i < lf.n; i++, count++) {
                if (lf.keys[i] <= prev) {
                    throw new AssertionError("리프 연결 순회가 오름차순이 아님: " + prev + " 뒤에 " + lf.keys[i]);
                }
                prev = lf.keys[i];
            }
            if (li == leaves.size() - 1 && lf.next != null) {
                throw new AssertionError("마지막 리프의 next가 null이 아님");
            }
        }
        if (count != size) {
            throw new AssertionError("size=" + size + "인데 리프 키 개수는 " + count);
        }
    }

    /** key는 [lo, hi) 범위여야 한다. 리프 깊이를 돌려준다 */
    private int checkNode(Node node, long lo, long hi, int depth, boolean isRoot, List<Leaf> leaves) {
        if (node.n > order || (!isRoot && node.n < order / 2)) {
            throw new AssertionError("깊이 " + depth + " 노드의 키 수 " + node.n + " (허용 " + order / 2 + ".." + order + ")");
        }
        for (int i = 0; i < node.n; i++) {
            if (i > 0 && node.keys[i - 1] >= node.keys[i]) {
                throw new AssertionError("깊이 " + depth + " 노드 키가 엄격한 오름차순이 아님: "
                        + node.keys[i - 1] + ", " + node.keys[i]);
            }
            if (node.keys[i] < lo || node.keys[i] >= hi) {
                throw new AssertionError("깊이 " + depth + " 키 " + node.keys[i] + "가 범위 [" + lo + ", " + hi + ") 밖");
            }
        }
        if (node instanceof Leaf lf) {
            leaves.add(lf);
            return depth;
        }
        Inner in = (Inner) node;
        if (isRoot && in.n < 1) {
            throw new AssertionError("안쪽 루트의 키가 0개");
        }
        int leafDepth = -1;
        for (int i = 0; i <= in.n; i++) {
            long cLo = i == 0 ? lo : in.keys[i - 1];
            long cHi = i == in.n ? hi : in.keys[i];
            int d = checkNode(in.kids[i], cLo, cHi, depth + 1, false, leaves);
            if (leafDepth != -1 && d != leafDepth) {
                throw new AssertionError("깊이 " + depth + " 노드의 자식들 리프 깊이가 다름: " + leafDepth + " vs " + d);
            }
            leafDepth = d;
        }
        return leafDepth;
    }
}
