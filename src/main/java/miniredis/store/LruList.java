package miniredis.store;

/**
 * 엔트리 안의 prev/next를 그대로 쓰는 이중 연결 리스트. 앞 = 최근 사용, 뒤 = 가장 오래 안 쓴 것.
 * 같은 엔트리가 리스트 2개(전체, TTL)에 걸려야 해서 어느 필드 쌍을 쓸지 ttl로 고른다.
 */
final class LruList {
    private final boolean ttl;
    private Entry head, tail;

    LruList(boolean ttl) {
        this.ttl = ttl;
    }

    private Entry prev(Entry e) { return ttl ? e.ttlPrev : e.lruPrev; }
    private Entry next(Entry e) { return ttl ? e.ttlNext : e.lruNext; }
    private void setPrev(Entry e, Entry p) { if (ttl) e.ttlPrev = p; else e.lruPrev = p; }
    private void setNext(Entry e, Entry n) { if (ttl) e.ttlNext = n; else e.lruNext = n; }

    void pushFront(Entry e) {
        setPrev(e, null);
        setNext(e, head);
        if (head != null) setPrev(head, e);
        head = e;
        if (tail == null) tail = e;
    }

    void remove(Entry e) {
        Entry p = prev(e), n = next(e);
        if (p != null) setNext(p, n); else head = n;
        if (n != null) setPrev(n, p); else tail = p;
        setPrev(e, null);
        setNext(e, null);
    }

    void moveToFront(Entry e) {
        if (head == e) return;
        remove(e);
        pushFront(e);
    }

    Entry tail() {
        return tail;
    }

    void clear() {
        head = tail = null;
    }
}
