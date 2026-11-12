package miniredis.storage;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashTableTest {
    // All three keys have the same String.hashCode().
    private static final List<String> COLLIDING_KEYS = List.of("Aa", "BB", "C#");

    @Test
    void storesAndRetrievesANewKey() {
        HashTable table = new HashTable(16);

        table.put("color", "blue");

        assertEquals("blue", table.get("color"));
    }

    @Test
    void overwritesAnEqualKeyWithoutLeavingAnOlderEntry() {
        HashTable table = new HashTable(16);
        table.put("color", "blue");

        table.put(new String("color"), "green");

        assertEquals("green", table.get("color"));
        assertEquals("green", table.remove("color"));
        assertNull(table.get("color"));
    }

    @Test
    void returnsNullForAMissingKeyEvenInANonEmptyBucket() {
        HashTable table = new HashTable(1);
        table.put("present", "value");

        assertNull(table.get("missing"));
        assertNull(table.remove("missing"));
        assertEquals("value", table.get("present"));
    }

    @Test
    void storesEmptyStrings() {
        HashTable table = new HashTable(16);

        table.put("", "");

        assertEquals("", table.get(""));
        assertEquals("", table.remove(""));
        assertNull(table.get(""));
    }

    @Test
    void preservesOtherValuesWhenUpdatingACollidingKey() {
        HashTable table = tableWithCollidingKeys();

        table.put("BB", "updated");

        assertEquals("Aa value", table.get("Aa"));
        assertEquals("updated", table.get("BB"));
        assertEquals("C# value", table.get("C#"));
    }

    // In insertion order Aa, BB, C#, the chain is C# -> BB -> Aa.
    @ParameterizedTest
    @ValueSource(strings = {"C#", "BB", "Aa"})
    void removesHeadMiddleOrTailWithoutLosingNeighbors(String keyToRemove) {
        HashTable table = tableWithCollidingKeys();

        assertEquals(keyToRemove + " value", table.remove(keyToRemove));

        assertNull(table.get(keyToRemove));
        assertNull(table.remove(keyToRemove));
        for (String key : COLLIDING_KEYS) {
            if (!key.equals(keyToRemove)) {
                assertEquals(key + " value", table.get(key));
            }
        }
    }

    @Test
    void handlesANegativeHashAndTheMinimumIntHash() {
        assertTrue("abcdef".hashCode() < 0);
        assertEquals(Integer.MIN_VALUE, "polygenelubricants".hashCode());
        HashTable table = new HashTable(7);

        table.put("abcdef", "negative");
        table.put("polygenelubricants", "minimum");

        assertEquals("negative", table.get("abcdef"));
        assertEquals("minimum", table.get("polygenelubricants"));
        assertEquals("negative", table.remove("abcdef"));
        assertEquals("minimum", table.remove("polygenelubricants"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBucketCounts(int bucketCount) {
        assertThrows(IllegalArgumentException.class, () -> new HashTable(bucketCount));
    }

    @Test
    void rejectsNullKeysAndValues() {
        HashTable table = new HashTable(16);

        assertThrows(NullPointerException.class, () -> table.put(null, "value"));
        assertThrows(NullPointerException.class, () -> table.put("key", null));
        assertThrows(NullPointerException.class, () -> table.get(null));
        assertThrows(NullPointerException.class, () -> table.remove(null));
    }

    private static HashTable tableWithCollidingKeys() {
        HashTable table = new HashTable(16);
        for (String key : COLLIDING_KEYS) {
            table.put(key, key + " value");
        }
        return table;
    }
}
