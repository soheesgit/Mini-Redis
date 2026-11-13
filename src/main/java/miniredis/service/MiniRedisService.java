package miniredis.service;

import miniredis.storage.HashTable;

/** 해시 테이블을 사용해 문자열 키와 값에 대한 명령을 실행한다. */
public final class MiniRedisService {
    private final HashTable table;

    public MiniRedisService(int bucketCount) {
        this.table = new HashTable(bucketCount);
    }

    /** 키와 값을 저장한다. 같은 키가 있으면 값을 덮어쓴다. */
    public void set(String key, String value) {
        table.put(key, value);
    }

    /** 키에 해당하는 값을 반환한다. 키가 없으면 {@code null}을 반환한다. */
    public String get(String key) {
        return table.get(key);
    }

    /** 키를 삭제했으면 {@code true}, 키가 없으면 {@code false}를 반환한다. */
    public boolean del(String key) {
        return table.remove(key) != null;
    }
}
