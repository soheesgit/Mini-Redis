package miniredis.storage;

import java.util.Objects;

/**
 * 문자열 키와 값 객체를 저장하는 고정 크기 해시 테이블.
 * 같은 버킷에 배정된 엔트리는 단방향 연결 리스트로 관리한다.
 * 키와 값은 {@code null}을 허용하지 않는다. 아래 C 주석은 문자열 값을 저장하는 예시다.
 */
public final class HashTable<V> {
    // 각 배열 칸은 버킷의 첫 엔트리를 가리킨다. 빈 버킷은 null이다.
    private final Entry<V>[] buckets;

    @SuppressWarnings("unchecked")
    public HashTable(int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        // C의 버킷 생성 함수에서는 엔트리 포인터 배열을 힙에 할당한다.
        // Entry **buckets = calloc(bucket_count, sizeof *buckets);
        // if (buckets == NULL) return NULL;
        // for (size_t i = 0; i < bucket_count; ++i) buckets[i] = NULL;
        // 이 배열에는 이 테이블이 만든 Entry<V>만 저장한다.
        buckets = (Entry<V>[]) new Entry<?>[bucketCount];
    }

    /** 키와 값을 저장한다. 같은 키가 있으면 기존 값을 덮어쓴다. */
    public void put(String key, V value) {
        int index = bucketIndex(key);
        Objects.requireNonNull(value, "value");

        Entry<V> existing = findEntry(buckets[index], key);
        // C의 put()은 입력 문자열을 복사해서 보관한다. 아래 예시는 void 함수 기준이다.
        // char *new_value = malloc(strlen(value) + 1);
        // if (new_value == NULL) return;
        // strcpy(new_value, value);
        if (existing != null) {
            // C에서는 새 값 복사에 성공한 뒤 기존 값의 메모리를 해제한다.
            // free(existing->value);
            // existing->value = new_value;
            existing.value = value;
            return;
        }

        // 새 엔트리를 기존 첫 엔트리 앞에 연결한 뒤 버킷의 시작점을 바꾼다.
        // C에서 새 키를 추가할 때는 엔트리와 키 문자열도 할당한다.
        // Entry *entry = malloc(sizeof *entry);
        // if (entry == NULL) {
        //     free(new_value);
        //     return;
        // }
        // char *new_key = malloc(strlen(key) + 1);
        // if (new_key == NULL) {
        //     free(entry);
        //     free(new_value);
        //     return;
        // }
        // strcpy(new_key, key);
        // *entry = (Entry){.key = new_key, .value = new_value, .next = buckets[index]};
        // buckets[index] = entry;
        buckets[index] = new Entry<>(key, value, buckets[index]);
    }

    /** 해당 버킷에서 키를 찾아 값을 반환한다. 키가 없으면 {@code null}을 반환한다. */
    public V get(String key) {
        Entry<V> entry = findEntry(buckets[bucketIndex(key)], key);
        return entry == null ? null : entry.value;
    }

    /** 키를 삭제하고 삭제된 값을 반환한다. 키가 없으면 {@code null}을 반환한다. */
    public V remove(String key) {
        int index = bucketIndex(key);
        Entry<V> previous = null;
        Entry<V> current = buckets[index];

        while (current != null) {
            if (current.key.equals(key)) {
                if (previous == null) {
                    // 첫 엔트리를 지울 때는 버킷이 다음 엔트리를 가리키게 한다.
                    buckets[index] = current.next;
                } else {
                    // 직전 엔트리를 다음 엔트리와 연결해 현재 엔트리를 건너뛴다.
                    previous.next = current.next;
                }
                // C에서는 연결을 끊은 뒤 키와 엔트리를 해제하고 값은 호출자에게 넘긴다.
                // char *removed_value = current->value;
                // free(current->key);
                // free(current);
                // return removed_value;
                // 호출자는 반환된 값을 다 쓴 뒤 free(removed_value); 를 실행한다.
                return current.value;
            }

            previous = current;
            current = current.next;
        }

        return null;
    }

    private int bucketIndex(String key) {
        int hash = Objects.requireNonNull(key, "key").hashCode();
        // 음수 해시도 0부터 buckets.length - 1까지의 인덱스로 바꾼다.
        return Math.floorMod(hash, buckets.length);
    }

    private Entry<V> findEntry(Entry<V> head, String key) {
        // 같은 버킷에 여러 키가 있을 수 있으므로 키의 문자열 값을 비교한다.
        for (Entry<V> entry = head; entry != null; entry = entry.next) {
            if (entry.key.equals(key)) {
                return entry;
            }
        }

        return null;
    }

    // C에서 테이블 전체를 해제할 때는 남은 엔트리부터 해제한다.
    // 해제한 엔트리에는 접근할 수 없으므로 다음 엔트리를 먼저 저장한다.
    // for (size_t i = 0; i < bucket_count; ++i) {
    //     Entry *entry = buckets[i];
    //     while (entry != NULL) {
    //         Entry *next = entry->next;
    //         free(entry->key);
    //         free(entry->value);
    //         free(entry);
    //         entry = next;
    //     }
    // }
    // free(buckets);
    private static final class Entry<V> {
        private final String key;
        private V value;
        private Entry<V> next;

        private Entry(String key, V value, Entry<V> next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
