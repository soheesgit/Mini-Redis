package minidb.index;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

/** 테이블의 ID를 페이지·슬롯 위치에 매핑하는 메모리 B+ 트리. */
public final class BPlusTree {
    public static final int DEFAULT_ORDER = 64;
    public static final long NONE = -1L;

    private final int maxKeys;
    private Node root = new Leaf();
    private int size;

    public BPlusTree() {
        this(DEFAULT_ORDER);
    }

    public BPlusTree(int maxKeys) {
        if (maxKeys < 3 || maxKeys == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("노드의 키 용량이 범위를 벗어남: " + maxKeys);
        }
        this.maxKeys = maxKeys;
    }

    public int size() {
        return size;
    }

    public int height() {
        int levels = 1;
        Node node = root;
        while (node instanceof Branch branch) {
            node = branch.children.getFirst();
            levels++;
        }
        return levels;
    }

    public static long loc(int page, int slot) {
        if (page < 0 || slot < 0 || slot > 0xffff) {
            throw new IllegalArgumentException("잘못된 행 위치: " + page + "/" + slot);
        }
        return ((long) page << 16) | slot;
    }

    public static int page(long location) {
        return (int) (location >>> 16);
    }

    public static int slot(long location) {
        return (int) (location & 0xffff);
    }

    public long get(int key) {
        Leaf leaf = leafFor(key);
        int index = leaf.lowerBound(key);
        return leaf.hasKeyAt(index, key) ? leaf.entries.get(index).location() : NONE;
    }

    /** 양끝을 포함한 키 범위의 행 위치를 키 순서대로 전달한다. */
    public void range(int low, int high, LongConsumer visitor) {
        if (low > high) return;
        Leaf leaf = leafFor(low);
        int index = leaf.lowerBound(low);
        while (leaf != null) {
            for (; index < leaf.entries.size(); index++) {
                Entry entry = leaf.entries.get(index);
                if (entry.key() > high) return;
                visitor.accept(entry.location());
            }
            leaf = leaf.next;
            index = 0;
        }
    }

    public void put(int key, long location) {
        if (location < 0) throw new IllegalArgumentException("잘못된 행 위치: " + location);
        Node sibling = insert(root, new Entry(key, location));
        if (sibling != null) {
            Branch parent = new Branch();
            parent.children.add(root);
            parent.children.add(sibling);
            parent.refreshMinimum();
            root = parent;
        }
    }

    /** 분할하면 오른쪽 노드를 돌려준다. 기존 노드는 왼쪽 절반을 그대로 보유한다. */
    private Node insert(Node node, Entry entry) {
        if (node instanceof Leaf leaf) {
            int at = leaf.lowerBound(entry.key());
            if (leaf.hasKeyAt(at, entry.key())) {
                leaf.entries.set(at, entry);
                return null;
            }
            leaf.entries.add(at, entry);
            size++;
        } else {
            Branch branch = (Branch) node;
            int at = branch.childIndex(entry.key());
            Node sibling = insert(branch.children.get(at), entry);
            if (sibling != null) branch.children.add(at + 1, sibling);
        }

        node.refreshMinimum();
        if (node.count() <= maximum(node)) return null;
        return split(node);
    }

    private static Node split(Node left) {
        Node right;
        if (left instanceof Leaf leaf) {
            Leaf sibling = new Leaf();
            moveUpperHalf(leaf.entries, sibling.entries);
            sibling.next = leaf.next;
            leaf.next = sibling;
            right = sibling;
        } else {
            Branch sibling = new Branch();
            moveUpperHalf(((Branch) left).children, sibling.children);
            right = sibling;
        }
        right.refreshMinimum();
        return right;
    }

    private static <T> void moveUpperHalf(List<T> left, List<T> right) {
        List<T> upper = left.subList(left.size() / 2, left.size());
        right.addAll(upper);
        upper.clear();
    }

    public boolean remove(int key) {
        if (!delete(root, key)) return false;
        size--;
        if (root instanceof Branch branch && branch.children.size() == 1) {
            root = branch.children.getFirst();
        }
        return true;
    }

    private boolean delete(Node node, int key) {
        if (node instanceof Leaf leaf) {
            int at = leaf.lowerBound(key);
            if (!leaf.hasKeyAt(at, key)) return false;
            leaf.entries.remove(at);
        } else {
            Branch branch = (Branch) node;
            int at = branch.childIndex(key);
            Node child = branch.children.get(at);
            if (!delete(child, key)) return false;
            if (child.count() < minimum(child)) repair(branch, at, child);
        }
        node.refreshMinimum();
        return true;
    }

    private void repair(Branch parent, int at, Node child) {
        Node left = at > 0 ? parent.children.get(at - 1) : null;
        Node right = at + 1 < parent.children.size() ? parent.children.get(at + 1) : null;

        if (left != null && left.count() > minimum(left)) {
            borrow(left, child, true);
        } else if (right != null && right.count() > minimum(right)) {
            borrow(right, child, false);
        } else {
            // 항상 오른쪽 노드를 왼쪽에 붙여 앞쪽 리프의 연결을 보존한다.
            int rightIndex = left == null ? at + 1 : at;
            merge(parent.children.get(rightIndex - 1), parent.children.remove(rightIndex));
        }
    }

    private static void borrow(Node donor, Node receiver, boolean fromLeft) {
        if (donor instanceof Leaf leaf) {
            moveEdge(leaf.entries, ((Leaf) receiver).entries, fromLeft);
        } else {
            moveEdge(((Branch) donor).children, ((Branch) receiver).children, fromLeft);
        }
        donor.refreshMinimum();
        receiver.refreshMinimum();
    }

    private static <T> void moveEdge(List<T> donor, List<T> receiver, boolean fromLeft) {
        if (fromLeft) receiver.addFirst(donor.removeLast());
        else receiver.addLast(donor.removeFirst());
    }

    private static void merge(Node left, Node right) {
        if (left instanceof Leaf leaf) {
            Leaf sibling = (Leaf) right;
            leaf.entries.addAll(sibling.entries);
            leaf.next = sibling.next;
        } else {
            ((Branch) left).children.addAll(((Branch) right).children);
        }
        left.refreshMinimum();
    }

    private Leaf leafFor(int key) {
        Node node = root;
        while (node instanceof Branch branch) {
            node = branch.children.get(branch.childIndex(key));
        }
        return (Leaf) node;
    }

    private int maximum(Node node) {
        return node instanceof Leaf ? maxKeys : maxKeys + 1;
    }

    private int minimum(Node node) {
        int capacity = maximum(node);
        return capacity / 2 + capacity % 2;
    }

    private abstract static sealed class Node permits Leaf, Branch {
        int firstKey;

        abstract int count();
        abstract void refreshMinimum();
    }

    private record Entry(int key, long location) {}

    private static final class Leaf extends Node {
        final List<Entry> entries = new ArrayList<>();
        Leaf next;

        int lowerBound(int key) {
            int start = 0, end = entries.size();
            while (start < end) {
                int middle = start + (end - start) / 2;
                if (entries.get(middle).key() < key) start = middle + 1;
                else end = middle;
            }
            return start;
        }

        boolean hasKeyAt(int index, int key) {
            return index < entries.size() && entries.get(index).key() == key;
        }

        @Override
        int count() {
            return entries.size();
        }

        @Override
        void refreshMinimum() {
            if (!entries.isEmpty()) firstKey = entries.getFirst().key();
        }
    }

    private static final class Branch extends Node {
        final List<Node> children = new ArrayList<>();

        // 자식의 첫 키가 분리 키다. 첫 자식은 그보다 작은 키도 받는다.
        int childIndex(int key) {
            int start = 1, end = children.size();
            while (start < end) {
                int middle = start + (end - start) / 2;
                if (children.get(middle).firstKey <= key) start = middle + 1;
                else end = middle;
            }
            return start - 1;
        }

        @Override
        int count() {
            return children.size();
        }

        @Override
        void refreshMinimum() {
            firstKey = children.getFirst().firstKey;
        }
    }

    /** 테스트에서 리프 깊이, 노드 점유율, 분리 키와 리프 연결을 함께 확인한다. */
    void checkStructure() {
        if (root instanceof Leaf leaf && leaf.entries.isEmpty()) {
            if (size != 0 || leaf.next != null) throw new IllegalStateException("빈 트리 상태 불일치");
            return;
        }
        List<Leaf> leaves = new ArrayList<>();
        if (inspect(root, leaves).entries() != size) throw new IllegalStateException("리프의 전체 키 수 불일치");
        for (int i = 0; i < leaves.size(); i++) {
            Leaf expected = i + 1 < leaves.size() ? leaves.get(i + 1) : null;
            if (leaves.get(i).next != expected) throw new IllegalStateException("리프 연결 순서 불일치");
        }
    }

    private Bounds inspect(Node node, List<Leaf> leaves) {
        int required = node == root ? (node instanceof Branch ? 2 : 1) : minimum(node);
        if (node.count() < required || node.count() > maximum(node)) {
            throw new IllegalStateException("노드 점유율 범위 위반");
        }
        if (node instanceof Leaf leaf) {
            int first = leaf.entries.getFirst().key();
            int last = first;
            for (int i = 1; i < leaf.entries.size(); i++) {
                int key = leaf.entries.get(i).key();
                if (key <= last) throw new IllegalStateException("리프 키 순서 위반");
                last = key;
            }
            if (node.firstKey != first) throw new IllegalStateException("리프의 최소 키 불일치");
            leaves.add(leaf);
            return new Bounds(first, last, 1, leaf.entries.size());
        }

        Branch branch = (Branch) node;
        Bounds first = inspect(branch.children.getFirst(), leaves);
        int last = first.high();
        int entries = first.entries();
        for (int i = 1; i < branch.children.size(); i++) {
            Bounds child = inspect(branch.children.get(i), leaves);
            if (last >= child.low() || first.depth() != child.depth()) {
                throw new IllegalStateException("자식 키 범위 또는 리프 깊이 불일치");
            }
            last = child.high();
            entries += child.entries();
        }
        if (node.firstKey != first.low()) throw new IllegalStateException("내부 노드의 최소 키 불일치");
        return new Bounds(first.low(), last, first.depth() + 1, entries);
    }

    private record Bounds(int low, int high, int depth, int entries) {}
}
