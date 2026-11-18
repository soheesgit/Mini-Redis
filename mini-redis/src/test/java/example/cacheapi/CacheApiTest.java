package example.cacheapi;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import example.origin.OriginMain;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import miniredis.engine.Engine;
import miniredis.httpapi.HttpApi;
import miniredis.store.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 원본(50ms 지연) + 미니 Redis + 캐시 API를 한 프로세스에 띄워 캐시 동작을 확인한다 */
class CacheApiTest {
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @TempDir
    Path dir;
    Engine engine;
    HttpServer redis, api;
    String origin, base;

    @BeforeEach
    void up() throws Exception {
        int originPort;
        try (ServerSocket s = new ServerSocket(0)) {
            originPort = s.getLocalPort();
        }
        OriginMain.main(new String[] {"--port", Integer.toString(originPort)}); // 멈출 수단이 없어 테스트 JVM이 끝날 때 같이 끝난다
        origin = "http://127.0.0.1:" + originPort;
        engine = Engine.start(new Engine.Config(dir, 0, Store.Policy.NOEVICTION));
        redis = HttpApi.start(new InetSocketAddress("127.0.0.1", 0), engine, null);
        api = new CacheApiMain("http://127.0.0.1:" + redis.getAddress().getPort(), origin, 60, true).start(0);
        base = "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @AfterEach
    void down() throws InterruptedException {
        api.stop(0);
        redis.stop(0);
        engine.close();
    }

    static HttpResponse<String> send(String method, String url, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    static String xcache(HttpResponse<?> r) {
        return r.headers().firstValue("X-Cache").orElse(null);
    }

    long originCalls() throws Exception {
        Matcher m = Pattern.compile("\"calls\":(\\d+)").matcher(send("GET", origin + "/stats", null).body());
        assertTrue(m.find());
        return Long.parseLong(m.group(1));
    }

    @Test
    void bypassThenMissThenHit() throws Exception {
        long before = originCalls();
        HttpResponse<String> bypass = send("GET", base + "/products/1?cached=false", null);
        assertEquals(200, bypass.statusCode());
        assertEquals("BYPASS", xcache(bypass));
        assertNull(engine.call("GET", "product:1")); // BYPASS는 캐시에 쓰지 않는다

        HttpResponse<String> miss = send("GET", base + "/products/1", null);
        assertEquals("MISS", xcache(miss)); // BYPASS 뒤 첫 일반 요청이 MISS = BYPASS가 캐시를 채우지 않았다는 증거
        HttpResponse<String> hit = send("GET", base + "/products/1", null);
        assertEquals("HIT", xcache(hit));
        assertEquals(miss.body(), hit.body());
        assertEquals(bypass.body(), hit.body());
        assertEquals(2, originCalls() - before); // BYPASS 1번 + MISS 1번. HIT은 원본에 안 간다
    }

    @Test
    void writeInvalidatesCache() throws Exception {
        send("GET", base + "/products/2", null);
        assertEquals("HIT", xcache(send("GET", base + "/products/2", null)));
        assertEquals(200, send("PUT", base + "/products/2", "{\"id\":\"2\",\"price\":2000}").statusCode());
        HttpResponse<String> after = send("GET", base + "/products/2", null);
        assertEquals("MISS", xcache(after)); // 원본 갱신 뒤 캐시가 지워졌다
        assertEquals("{\"id\":\"2\",\"price\":2000}", after.body());
    }

    @Test
    void missingProductIsCachedAsNone() throws Exception {
        long before = originCalls();
        assertEquals(404, send("GET", base + "/products/missing-1", null).statusCode());
        HttpResponse<String> again = send("GET", base + "/products/missing-1", null);
        assertEquals(404, again.statusCode());
        assertEquals("HIT", xcache(again)); // "없음"도 캐시에서 답한다
        assertEquals(1, originCalls() - before);
    }

    @Test
    void concurrentMissCallsOriginOnce() throws Exception {
        long before = originCalls();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Integer> codes = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < 50; t++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    codes.add(send("GET", base + "/products/hot", null).statusCode());
                } catch (Exception x) {
                    codes.add(-1);
                }
            }));
        }
        go.countDown(); // 50개가 동시에 출발. 원본이 50ms 걸리는 동안 모두 같은 조회를 기다린다
        for (Thread t : threads) t.join();
        assertEquals(50, codes.size());
        assertTrue(codes.stream().allMatch(c -> c == 200), codes.toString());
        assertEquals(1, originCalls() - before); // single-flight: 원본 호출 1번
    }
}
