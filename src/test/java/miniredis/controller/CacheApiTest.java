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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void cachesTheOriginResponseAndRefillsItAfterDeleteOrExpiration() throws Exception {
        String cacheKey = "store:product:cola";
        String path = "/api/store/products/cola";
        send(request(cacheKey).DELETE());
        now.set(START);

        HttpResponse<String> bypass = send(requestPath(path + "?cached=false").GET());
        assertCacheStatus("BYPASS", bypass);
        assertEquals("콜라", JSON.readTree(bypass.body()).get("name").asString());
        assertProblem(404, get(cacheKey));

        HttpResponse<String> miss = send(requestPath(path).GET());
        assertCacheStatus("MISS", miss);
        assertEquals(bypass.body(), miss.body());
        assertValue(miss.body(), get(cacheKey));

        HttpResponse<String> hit = send(requestPath(path).GET());
        assertCacheStatus("HIT", hit);
        assertEquals(miss.body(), hit.body());

        assertEquals(204, send(request(cacheKey).DELETE()).statusCode());
        HttpResponse<String> refill = send(requestPath(path).GET());
        assertCacheStatus("MISS", refill);
        assertEquals(miss.body(), refill.body());

        now.set(START.plusSeconds(30).minusNanos(1));
        assertCacheStatus("HIT", send(requestPath(path).GET()));

        now.set(START.plusSeconds(30));
        HttpResponse<String> afterExpiry = send(requestPath(path).GET());
        assertCacheStatus("MISS", afterExpiry);
        assertEquals(miss.body(), afterExpiry.body());
        assertCacheStatus("HIT", send(requestPath(path).GET()));
    }

    @Test
    void bypassReadsTheOriginWhileCachedRequestsUseTheStoredBody() throws Exception {
        String cacheKey = "store:product:potato-chips";
        String path = "/api/store/products/potato-chips";
        String cachedBody = "{\"code\":\"potato-chips\",\"name\":\"cached copy\"}";
        String putBody = JSON.createObjectNode().put("value", cachedBody).toString();
        assertEquals(204, put(cacheKey, putBody).statusCode());
        try {
            HttpResponse<String> hit = send(requestPath(path).GET());
            assertCacheStatus("HIT", hit);
            assertEquals(cachedBody, hit.body());

            HttpResponse<String> bypass = send(requestPath(path + "?cached=false").GET());
            assertCacheStatus("BYPASS", bypass);
            assertNotEquals(cachedBody, bypass.body());
            assertValue(cachedBody, get(cacheKey));
        } finally {
            assertEquals(204, send(request(cacheKey).DELETE()).statusCode());
        }
    }

    @Test
    void seedingAndComparingTheCatalogPreservesAnIdenticallyNamedSaleKeyAndItsTtl() throws Exception {
        String saleKey = "sale:cola";
        now.set(START);
        assertEquals(204, put(saleKey, """
                {"value":"직접 등록한 콜라","ttlSeconds":8}
                """).statusCode());

        HttpResponse<String> catalog = send(requestPath("/api/store/products").GET());
        assertEquals(200, catalog.statusCode());
        var products = JSON.readTree(catalog.body());
        assertEquals(20, products.size());
        now.set(START.plusSeconds(3));
        for (var product : products) {
            String code = product.get("code").asString();
            String path = "/api/store/products/" + code;
            String cacheKey = "store:product:" + code;
            HttpResponse<String> bypass = send(requestPath(path + "?cached=false").GET());
            assertCacheStatus("BYPASS", bypass);
            assertEquals(product, JSON.readTree(bypass.body()));
            String seed = JSON.createObjectNode().put("value", bypass.body()).put("ttlSeconds", 30).toString();
            assertEquals(204, put(cacheKey, seed).statusCode());
            assertCacheStatus("HIT", send(requestPath(path).GET()));
            assertEquals(204, send(request(cacheKey).DELETE()).statusCode());
            HttpResponse<String> miss = send(requestPath(path).GET());
            assertCacheStatus("MISS", miss);
            HttpResponse<String> hit = send(requestPath(path).GET());
            assertCacheStatus("HIT", hit);
            assertEquals(bypass.body(), miss.body());
            assertEquals(bypass.body(), hit.body());
        }

        assertProblem(404, send(requestPath("/api/store/products/unknown").GET()));
        assertProblem(404, send(requestPath("/api/demo/product").GET()));
        now.set(START.plusSeconds(8).minusNanos(1));
        assertValue("직접 등록한 콜라", get(saleKey));
        now.set(START.plusSeconds(8));
        assertProblem(404, get(saleKey));
        assertCacheStatus("HIT", send(requestPath("/api/store/products/cola").GET()));
    }

    @Test
    void concurrentHttpRequestsKeepIndependentKeysAndDeleteASharedKeyOnce() throws Exception {
        int requestCount = 12;
        List<CompletableFuture<HttpResponse<String>>> writes = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            String body = JSON.createObjectNode().put("value", "value-" + index).toString();
            writes.add(sendAsync(request("parallel-" + index).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))));
        }
        for (int index = 0; index < requestCount; index++) {
            assertEquals(204, writes.get(index).get(5, TimeUnit.SECONDS).statusCode());
        }
        for (int index = 0; index < requestCount; index++) {
            assertValue("value-" + index, get("parallel-" + index));
        }

        List<CompletableFuture<HttpResponse<String>>> deletes = new ArrayList<>();
        for (int index = 0; index < requestCount; index++) {
            deletes.add(sendAsync(request("parallel-0").DELETE()));
        }
        int deleted = 0;
        for (var pending : deletes) {
            HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
            if (response.statusCode() == 204) {
                deleted++;
            } else {
                assertProblem(404, response);
            }
        }
        assertEquals(1, deleted);
        assertProblem(404, get("parallel-0"));
        assertValue("value-1", get("parallel-1"));
    }

    private static void assertCacheStatus(String expected, HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(expected, response.headers().firstValue("X-Cache").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    }

    private HttpResponse<String> put(String key, String json) throws IOException, InterruptedException {
        return send(request(key).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpResponse<String> get(String key) throws IOException, InterruptedException {
        return send(request(key).GET());
    }

    private HttpRequest.Builder request(String key) {
        return requestPath("/api/cache/" + key);
    }

    private HttpRequest.Builder requestPath(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest.Builder request) {
        return CLIENT.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
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
