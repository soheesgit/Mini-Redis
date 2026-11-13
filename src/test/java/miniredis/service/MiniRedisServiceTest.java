package miniredis.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniRedisServiceTest {
    private final MiniRedisService service = new MiniRedisService(16);

    @Test
    void setStoresAValueForGet() {
        service.set("color", "blue");

        assertEquals("blue", service.get("color"));
    }

    @Test
    void setOverwritesTheValueForAnExistingKey() {
        service.set("color", "blue");

        service.set("color", "green");

        assertEquals("green", service.get("color"));
    }

    @Test
    void getReturnsNullForAMissingKey() {
        service.set("Aa", "stored");

        assertNull(service.get("BB"));
    }

    @Test
    void delRemovesTheKeyAndReportsSuccessOnlyOnce() {
        service.set("Aa", "delete me");
        service.set("BB", "keep me");

        assertTrue(service.del("Aa"));

        assertNull(service.get("Aa"));
        assertFalse(service.del("Aa"));
        assertEquals("keep me", service.get("BB"));
    }

    @Test
    void delReturnsFalseForAMissingKey() {
        service.set("Aa", "keep me");

        assertFalse(service.del("BB"));
        assertEquals("keep me", service.get("Aa"));
    }

    @Test
    void delReportsSuccessForAnEmptyStringValue() {
        service.set("empty", "");

        assertEquals("", service.get("empty"));
        assertTrue(service.del("empty"));
        assertNull(service.get("empty"));
    }
}
