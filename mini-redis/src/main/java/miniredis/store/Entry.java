package miniredis.store;

/** 키 하나. 키 테이블과 만료 테이블이 같은 객체를 가리키고, LRU 리스트 2개에 동시에 걸린다. */
public final class Entry {
    public final String key;
    final int keyBytes; // UTF-8 바이트 수 (메모리 추정용)
    byte[] value;
    long expireAt; // 단조 시계 ns, 0이면 TTL 없음

    Entry lruPrev, lruNext; // 전체 LRU
    Entry ttlPrev, ttlNext; // TTL 키만 모은 LRU

    Entry(String key, int keyBytes, byte[] value) {
        this.key = key;
        this.keyBytes = keyBytes;
        this.value = value;
    }

    public byte[] value() {
        return value;
    }

    public long expireAt() {
        return expireAt;
    }
}
