package miniredis.service;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniRedisExpirationTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private Instant now = START;
    private final InstantSource time = () -> now;
    private final MiniRedisService service = new MiniRedisService(16, time.withZone(ZoneOffset.UTC));

    @Test
    void getReturnsTheValueUntilTheExpirationBoundary() {
        service.set("key", "value", Duration.ofSeconds(2));

        now = START.plusSeconds(2).minusNanos(1);
        assertEquals("value", service.get("key"));

        now = START.plusSeconds(2);
        assertNull(service.get("key"));
    }

    @Test
    void getUnlinksAnExpiredEntryWithoutRemovingACollidingKey() {
        service.set("Aa", "expired", Duration.ofSeconds(1));
        service.set("BB", "keep");

        now = START.plusSeconds(2);
        assertNull(service.get("Aa"));
        assertEquals("keep", service.get("BB"));

        now = START;
        assertNull(service.get("Aa"));
    }

    @Test
    void setWithoutTtlRemovesTheOldExpiration() {
        service.set("key", "old", Duration.ofSeconds(1));

        service.set("key", "persistent");
        now = START.plus(Duration.ofDays(1));

        assertEquals("persistent", service.get("key"));
    }

    @Test
    void setWithTtlReplacesTheOldExpirationUsingTheCurrentTime() {
        service.set("key", "old", Duration.ofSeconds(2));

        now = START.plusSeconds(1);
        service.set("key", "new", Duration.ofSeconds(3));

        now = START.plusSeconds(2);
        assertEquals("new", service.get("key"));

        now = START.plusSeconds(4);
        assertNull(service.get("key"));
    }

    @Test
    void setReplacesAnExpiredEntryThatHasNotBeenRead() {
        service.set("key", "expired", Duration.ofSeconds(1));
        now = START.plusSeconds(2);

        service.set("key", "new", Duration.ofSeconds(1));
        assertEquals("new", service.get("key"));

        now = START.plusSeconds(3);
        assertNull(service.get("key"));
    }

    @Test
    void delRemovesAnUnexpiredKey() {
        service.set("key", "value", Duration.ofSeconds(1));

        assertTrue(service.del("key"));
        assertNull(service.get("key"));
    }

    @Test
    void delTreatsAnExpiredKeyAsMissingAndUnlinksIt() {
        service.set("key", "expired", Duration.ofSeconds(1));

        now = START.plusSeconds(1);
        assertFalse(service.del("key"));

        now = START;
        assertNull(service.get("key"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsANonPositiveTtlWithoutOverwritingTheKey(long seconds) {
        service.set("key", "original");

        assertThrows(IllegalArgumentException.class,
                () -> service.set("key", "new", Duration.ofSeconds(seconds)));

        assertEquals("original", service.get("key"));
    }

    @Test
    void rejectsNullTtlWithoutOverwritingTheKey() {
        service.set("key", "original");

        assertThrows(NullPointerException.class, () -> service.set("key", "new", null));

        assertEquals("original", service.get("key"));
    }

    @Test
    void rejectsUnrepresentableExpirationWithoutStoringTheKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.set("key", "value", Duration.ofSeconds(Long.MAX_VALUE)));

        assertNull(service.get("key"));
    }
}
