package miniredis.storage;

import java.util.Objects;

/** A fixed-bucket hash table for non-null string keys and values. */
public final class HashTable {
    private final Entry[] buckets;

    public HashTable(int bucketCount) {
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        buckets = new Entry[bucketCount];
    }

    public void put(String key, String value) {
        int index = bucketIndex(key);
        Objects.requireNonNull(value, "value");

        Entry existing = findEntry(buckets[index], key);
        if (existing != null) {
            existing.value = value;
            return;
        }

        buckets[index] = new Entry(key, value, buckets[index]);
    }

    /** Returns the value, or null if the key is absent. */
    public String get(String key) {
        Entry entry = findEntry(buckets[bucketIndex(key)], key);
        return entry == null ? null : entry.value;
    }

    /** Removes the entry and returns its value, or null if the key is absent. */
    public String remove(String key) {
        int index = bucketIndex(key);
        Entry previous = null;
        Entry current = buckets[index];

        while (current != null) {
            if (current.key.equals(key)) {
                if (previous == null) {
                    buckets[index] = current.next;
                } else {
                    previous.next = current.next;
                }
                return current.value;
            }

            previous = current;
            current = current.next;
        }

        return null;
    }

    private int bucketIndex(String key) {
        int hash = Objects.requireNonNull(key, "key").hashCode();
        return Math.floorMod(hash, buckets.length);
    }

    private static Entry findEntry(Entry head, String key) {
        for (Entry entry = head; entry != null; entry = entry.next) {
            if (entry.key.equals(key)) {
                return entry;
            }
        }

        return null;
    }

    private static final class Entry {
        private final String key;
        private String value;
        private Entry next;

        private Entry(String key, String value, Entry next) {
            this.key = key;
            this.value = value;
            this.next = next;
        }
    }
}
