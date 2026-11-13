package miniredis.controller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import miniredis.service.MiniRedisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class CacheApiTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private AtomicReference<Instant> now;

    @Test
    void savesOverwritesAndInvalidatesAKey() throws Exception {
        assertEquals(204, put("item", """
                {"value":"first"}
                """).statusCode());
        assertValue("first", get("item"));

        assertEquals(204, put("item", """
                {"value":""}
                """).statusCode());
        assertValue("", get("item"));

        assertEquals(204, send(request("item").DELETE()).statusCode());
        HttpResponse<String> missing = get("item");
        assertProblem(404, missing);
        assertEquals("no-store", missing.headers().firstValue("Cache-Control").orElseThrow());
        assertProblem(404, send(request("item").DELETE()));
    }

    @Test
    void expiresAKeyAtItsTtlBoundary() throws Exception {
        now.set(START);
        assertEquals(204, put("short-lived", """
                {"value":"temporary","ttlSeconds":2}
                """).statusCode());

        now.set(START.plusSeconds(2).minusNanos(1));
        assertValue("temporary", get("short-lived"));

        now.set(START.plusSeconds(2));
        assertProblem(404, get("short-lived"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "{",
        "null",
        "{}",
        "{\"value\":null}",
        "{\"value\":123}",
        "{\"value\":\"bad\",\"ttlSeconds\":0}",
        "{\"value\":\"bad\",\"ttlSeconds\":-1}",
        "{\"value\":\"bad\",\"ttlSeconds\":0.5}",
        "{\"value\":\"bad\",\"ttlSeconds\":\"2\"}",
        "{\"value\":\"bad\",\"ttlSeconds\":9223372036854775807}",
        "{\"value\":\"bad\",\"ttlSeconds\":9223372036854775808}",
        "{\"value\":\"bad\",\"ttlSecond\":2}",
        "{\"value\":\"bad\"} {}"
    })
    void rejectsInvalidBodiesWithoutOverwritingTheKey(String body) throws Exception {
        assertEquals(204, put("invalid", """
                {"value":"original"}
                """).statusCode());

        assertProblem(400, put("invalid", body));

        assertValue("original", get("invalid"));
    }

    @Test
    void rejectsABlankKey() throws Exception {
        assertProblem(400, put("%20", """
                {"value":"value"}
                """));
    }

    private HttpResponse<String> put(String key, String json) throws IOException, InterruptedException {
        return send(request(key).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> get(String key) throws IOException, InterruptedException {
        return send(request(key).GET());
    }

    private HttpRequest.Builder request(String key) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cache/" + key))
                .timeout(Duration.ofSeconds(5));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertValue(String expected, HttpResponse<String> response) {
        assertEquals(200, response.statusCode());
        assertEquals(expected, JSON.readTree(response.body()).get("value").asString());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private static void assertProblem(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals(status, JSON.readTree(response.body()).get("status").asInt());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean
        AtomicReference<Instant> now() {
            return new AtomicReference<>(START);
        }

        @Bean
        @Primary
        MiniRedisService testCacheService(AtomicReference<Instant> now) {
            InstantSource source = now::get;
            return new MiniRedisService(16, source.withZone(ZoneOffset.UTC));
        }
    }
}
