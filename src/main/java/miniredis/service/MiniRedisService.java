package miniredis.service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import miniredis.storage.HashTable;

/** 해시 테이블을 사용해 문자열 키와 값에 대한 명령을 실행한다. */
public final class MiniRedisService {
    private final HashTable<StoredValue> table;
    private final Clock clock;

    public MiniRedisService(int bucketCount) {
        this(bucketCount, Clock.systemUTC());
    }

    public MiniRedisService(int bucketCount, Clock clock) {
        this.table = new HashTable<>(bucketCount);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 만료 시각 없이 저장한다. 같은 키를 덮어쓰면 이전 TTL도 지운다. */
    public void set(String key, String value) {
        table.put(key, new StoredValue(value, null));
    }

    /**
     * 저장 시각에 TTL을 더해 만료 시각을 정한다. 같은 키를 덮어쓰면 TTL도 갱신한다.
     *
     * @throws IllegalArgumentException TTL이 양수가 아니거나 만료 시각을 표현할 수 없는 경우
     * @throws NullPointerException TTL, 키 또는 값이 {@code null}인 경우
     */
    public void set(String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        Instant expiresAt;
        try {
            expiresAt = clock.instant().plus(ttl);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("ttl exceeds the supported expiration range", exception);
        }
        table.put(key, new StoredValue(value, expiresAt));
    }

    /** 키의 값을 반환한다. 키가 없거나 만료됐으면 {@code null}을 반환한다. */
    public String get(String key) {
        StoredValue stored = table.get(key);
        if (stored == null) {
            return null;
        }
        if (isExpired(stored)) { // 지연 만료 (Lazy Expiration). 데이터가 만료 되어도 일단 남겨 두었다가 요청했을 때 삭제
            table.remove(key);
            return null;
        }
        return stored.value();
    }

    /** 유효한 키를 삭제했으면 {@code true}, 없거나 만료됐으면 {@code false}를 반환한다. */
    public boolean del(String key) {
        StoredValue removed = table.remove(key);
        return removed != null && !isExpired(removed);
    }

    /** 만료 시각이 존재하고, 현재 시각이 만료 시각과 같거나 이후라면 만료된 것으로 판단. 만료되었으면 True, 아직 만료되지 않았다면 False **/
    private boolean isExpired(StoredValue stored) {
        Instant expiresAt = stored.expiresAt();
        
        return expiresAt != null && !clock.instant().isBefore(expiresAt);
    }

    private record StoredValue(String value, Instant expiresAt) {
        private StoredValue {
            Objects.requireNonNull(value, "value");
        }
    }
}
