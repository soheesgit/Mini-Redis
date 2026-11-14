package miniredis.hashtable;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.random.RandomGenerator;

/**
 * 체이닝 해시 테이블 + 점진적 리해싱 (Redis dict.c와 같은 방식).
 * 스레드 안전하지 않다. executor 스레드 하나만 쓴다는 전제다 (기획서 5.1).
 *
 * 기획서 5.2는 엔트리 안에 next(체인)를 두지만, 같은 엔트리가 키 테이블과 만료 테이블(5.3)
 * 두 곳에 들어가야 해서 next가 하나면 체인이 섞인다. 그래서 체인 노드를 테이블 내부에 따로 둔다.
 */
public final class HashTable<V> {
    static final int INITIAL_SIZE = 4;
    private static final int EMPTY_VISITS = 10;

    private static final class Node<V> {
        final String key;
        V value;
        Node<V> next;

        Node(String key, V value, Node<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }

    private final SipHash hasher;
    private final RandomGenerator rng;

    // table[0] = 현재, table[1] = 리해싱 중에만 존재
    @SuppressWarnings("unchecked")
    private final Node<V>[][] table = new Node[2][];
    private final int[] used = new int[2];
    private int rehashIndex = -1; // -1이면 리해싱 중 아님
    private int openCursors = 0;

    public HashTable(SipHash hasher, RandomGenerator rng) {
        this.hasher = hasher;
        this.rng = rng;
        table[0] = newBuckets(INITIAL_SIZE);
    }

    long hash(String key) {
        return hasher.hash(key.getBytes(StandardCharsets.UTF_8));
    }

    private static int index(long hash, int buckets) {
        return (int) (hash & (buckets - 1)); // 버킷 수가 2의 거듭제곱이라 나머지 연산 대신 AND
    }

    public V get(String key) {
        stepIfAllowed();
        Node<V> n = find(key);
        return n == null ? null : n.value;
    }

    /** 있으면 값을 바꾸고 이전 값을 돌려준다. 없으면 추가하고 null. */
    public V set(String key, V value) {
        stepIfAllowed();
        Node<V> n = find(key);
        if (n != null) {
            V old = n.value;
            n.value = value;
            return old;
        }
        int t = isRehashing() ? 1 : 0; // 리해싱 중 새 키는 table[1]로: table[0]은 줄어들기만 해야 끝난다
        int i = index(hash(key), table[t].length);
        table[t][i] = new Node<>(key, value, table[t][i]);
        used[t]++;
        resizeIfNeeded();
        return null;
    }

    /** 지운 값을 돌려준다. 없으면 null. */
    public V delete(String key) {
        stepIfAllowed();
        long h = hash(key);
        for (int t = 0; t <= (isRehashing() ? 1 : 0); t++) {
            int i = index(h, table[t].length);
            Node<V> prev = null;
            for (Node<V> n = table[t][i]; n != null; prev = n, n = n.next) {
                if (!n.key.equals(key)) continue;
                if (prev == null) table[t][i] = n.next;
                else prev.next = n.next;
                used[t]--;
                resizeIfNeeded();
                return n.value;
            }
        }
        return null;
    }

    public int size() {
        return used[0] + used[1];
    }

    public boolean isRehashing() {
        return rehashIndex != -1;
    }

    /** stats·테스트용: 현재 table[0]의 버킷 수 */
    public int bucketCount() {
        return table[0].length;
    }

    /** stats용: 가장 긴 체인 길이. 전체를 훑으므로 자주 부르지 않는다 */
    public int maxChainLength() {
        int max = 0;
        for (Node<V>[] t : table) {
            if (t == null) continue;
            for (Node<V> b : t) {
                int len = 0;
                for (Node<V> n = b; n != null; n = n.next) len++;
                max = Math.max(max, len);
            }
        }
        return max;
    }

    private Node<V> find(String key) {
        long h = hash(key);
        for (int t = 0; t <= (isRehashing() ? 1 : 0); t++) {
            for (Node<V> n = table[t][index(h, table[t].length)]; n != null; n = n.next) {
                if (n.key.equals(key)) return n;
            }
        }
        return null;
    }

    private void stepIfAllowed() {
        if (isRehashing() && openCursors == 0) rehashStep();
    }

    /**
     * 비어 있지 않은 버킷 1개를 table[1]로 옮긴다. 빈 버킷은 최대 10개까지 건너뛴다.
     * 리해싱이 아니거나 커서가 열려 있으면 아무것도 안 한다. 아직 남은 일이 있으면 true.
     */
    public boolean rehashStep() {
        if (!isRehashing() || openCursors > 0) return false;
        int visits = EMPTY_VISITS;
        while (used[0] > 0 && table[0][rehashIndex] == null) {
            rehashIndex++;
            if (--visits == 0) return true; // 빈 버킷만 계속 나오면 이번 단계는 여기까지 (한 번에 오래 멈추지 않게)
        }
        Node<V> n = used[0] > 0 ? table[0][rehashIndex] : null;
        while (n != null) {
            Node<V> next = n.next;
            int i = index(hash(n.key), table[1].length);
            n.next = table[1][i];
            table[1][i] = n;
            used[0]--;
            used[1]++;
            n = next;
        }
        if (used[0] > 0) {
            table[0][rehashIndex++] = null;
            return true;
        }
        // 완료: table[1]이 새 table[0]이 된다
        table[0] = table[1];
        used[0] = used[1];
        table[1] = null;
        used[1] = 0;
        rehashIndex = -1;
        return false;
    }

    private void resizeIfNeeded() {
        if (isRehashing() || openCursors > 0) return; // 리해싱 중·순회 중에는 새 크기 조정 금지
        int buckets = table[0].length;
        int size = used[0];
        if (size >= buckets) {
            startRehash(buckets * 2);
        } else if (buckets > INITIAL_SIZE && size < buckets / 8) {
            int target = INITIAL_SIZE;
            while (target <= size) target *= 2; // 키 수보다 큰 가장 작은 2의 거듭제곱
            startRehash(target);
        }
    }

    private void startRehash(int buckets) {
        table[1] = newBuckets(buckets);
        rehashIndex = 0;
    }

    /** 무작위 버킷에서 1개를 뽑는다. 리해싱 중이면 두 테이블 모두에서. 비어 있으면 null. */
    public V randomEntry() {
        if (size() == 0) return null;
        Node<V> bucket;
        do {
            if (isRehashing()) {
                // table[0]의 rehashIndex 앞쪽은 이미 비었으므로 뺀다
                int range = table[0].length - rehashIndex + table[1].length;
                int i = rehashIndex + rng.nextInt(range);
                bucket = i < table[0].length ? table[0][i] : table[1][i - table[0].length];
            } else {
                bucket = table[0][rng.nextInt(table[0].length)];
            }
        } while (bucket == null);
        int len = 0;
        for (Node<V> n = bucket; n != null; n = n.next) len++;
        Node<V> n = bucket;
        for (int k = rng.nextInt(len); k > 0; k--) n = n.next;
        return n.value;
    }

    /**
     * 버킷 번호 기반 순회. 열려 있는 동안 리해싱·확장·축소가 멈추므로 버킷 배열이 바뀌지 않는다.
     * 그래서 여러 틱에 걸쳐 돌아도, 순회 사이에 다른 명령이 키를 지우거나 넣어도 안전하다.
     */
    public Cursor cursor() {
        openCursors++;
        return new Cursor();
    }

    public final class Cursor {
        // (테이블 번호, 버킷 번호)만 기억한다. 엔트리 참조는 들고 있지 않는다
        private int t = 0;
        private int bucket = isRehashing() ? rehashIndex : 0;
        private boolean closed = false;

        /** 버킷 1개를 통째로 visitor에 넘긴다. 다 돌았으면 스스로 닫고 false. */
        public boolean step(BiConsumer<String, V> visitor) {
            if (closed) return false;
            if (bucket >= table[t].length) {
                if (t == 0 && table[1] != null) {
                    t = 1;
                    bucket = 0;
                } else {
                    close();
                    return false;
                }
            }
            for (Node<V> n = table[t][bucket]; n != null; n = n.next) visitor.accept(n.key, n.value);
            bucket++;
            return true;
        }

        /** 끝까지 돌지 않고 멈출 때도 반드시 호출. 닫히면 미뤄 둔 확장·축소를 바로 판단한다. */
        public void close() {
            if (closed) return;
            closed = true;
            openCursors--;
            resizeIfNeeded();
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> Node<V>[] newBuckets(int n) {
        return (Node<V>[]) new Node[n];
    }
}
