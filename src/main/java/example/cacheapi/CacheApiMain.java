package example.cacheapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 미니 Redis를 캐시로 쓰는 상품 API (cache-aside, 기획서 6.3).
 * 1. 읽기: 캐시 조회 → 없으면 원본 조회 → TTL과 함께 캐시에 저장
 * 2. 쓰기: 원본 갱신 → 캐시 삭제 (반대 순서면 사이에 들어온 읽기가 옛 값을 다시 캐시에 넣는다)
 * 3. TTL ±10% 무작위 (같은 시각에 저장된 키들이 한꺼번에 만료되지 않게)
 * 4. 같은 키의 원본 조회는 하나로 합친다 (single-flight)
 * 5. 원본에 없는 키는 "없음"을 30초간 캐시 (없는 키 반복 조회로 원본을 두드리지 않게)
 *
 * java -cp target/classes example.cacheapi.CacheApiMain [--port 8082] [--redis http://127.0.0.1:8080]
 *      [--origin http://127.0.0.1:8081] [--ttl 60] [--singleflight true]
 */
public final class CacheApiMain {
    static final byte[] NONE = "__none__".getBytes(StandardCharsets.US_ASCII);
    static final int NEGATIVE_TTL = 30;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> flights = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong(), misses = new AtomicLong(), originCalls = new AtomicLong();
    private final String redis, origin, auth;
    private final int ttl;
    private final boolean singleFlight;

    CacheApiMain(String redis, String origin, int ttl, boolean singleFlight) {
        this.redis = redis;
        this.origin = origin;
        this.ttl = ttl;
        this.singleFlight = singleFlight;
        String pw = System.getenv("MINIREDIS_PASSWORD");
        this.auth = pw == null || pw.isEmpty() ? null : "Bearer " + pw;
    }

    public static void main(String[] args) throws IOException {
        int port = 8082, ttl = 60;
        String redis = "http://127.0.0.1:8080", origin = "http://127.0.0.1:8081";
        boolean sf = true;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--redis" -> redis = args[i + 1];
                case "--origin" -> origin = args[i + 1];
                case "--ttl" -> ttl = Integer.parseInt(args[i + 1]);
                case "--singleflight" -> sf = Boolean.parseBoolean(args[i + 1]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        CacheApiMain app = new CacheApiMain(redis, origin, ttl, sf);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("cache api listening on 127.0.0.1:" + port + " (single-flight " + sf + ")");
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/stats") && method.equals("GET")) {
                send(ex, 200, ("{\"hits\":" + hits.get() + ",\"misses\":" + misses.get()
                    + ",\"originCalls\":" + originCalls.get() + "}").getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/stats/reset") && method.equals("POST")) {
                hits.set(0);
                misses.set(0);
                originCalls.set(0);
                send(ex, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } else if (path.startsWith("/products/")) {
                String id = path.substring("/products/".length());
                if (method.equals("GET")) read(ex, id);
                else if (method.equals("PUT")) write(ex, id, ex.getRequestBody().readAllBytes());
                else send(ex, 405, new byte[0]);
            } else {
                send(ex, 404, new byte[0]);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            send(ex, 502, ("{\"error\":\"" + e.getClass().getSimpleName() + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private void read(HttpExchange ex, String id) throws Exception {
        String key = "product:" + id;
        HttpResponse<byte[]> cached = http.send(redisReq(key, "").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        byte[] value;
        if (cached.statusCode() == 200) {
            hits.incrementAndGet();
            value = cached.body();
        } else {
            misses.incrementAndGet();
            value = singleFlight ? loadOnce(id, key) : load(id, key);
        }
        if (value == null || Arrays.equals(value, NONE)) send(ex, 404, "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
        else send(ex, 200, value);
    }

    /** 같은 키를 동시에 요청하면 첫 요청만 원본을 부르고 나머지는 같은 future를 기다린다. */
    private byte[] loadOnce(String id, String key) throws Exception {
        CompletableFuture<byte[]> mine = new CompletableFuture<>();
        CompletableFuture<byte[]> f = flights.putIfAbsent(id, mine);
        if (f != null) return f.get(); // 이미 누가 원본을 부르는 중
        try {
            mine.complete(load(id, key));
        } catch (Exception e) {
            mine.completeExceptionally(e);
        } finally {
            flights.remove(id, mine); // 끝나면 맵에서 지운다. 다음 만료 때 다시 한 번만 부른다
        }
        return mine.get();
    }

    private byte[] load(String id, String key) throws Exception {
        originCalls.incrementAndGet();
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(origin + "/products/" + id)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() == 404) {
            put(key, NONE, NEGATIVE_TTL);
            return null;
        }
        if (r.statusCode() != 200) throw new IOException("origin " + r.statusCode());
        int jittered = Math.max(1, (int) Math.round(ttl * (0.9 + 0.2 * ThreadLocalRandom.current().nextDouble())));
        put(key, r.body(), jittered);
        return r.body();
    }

    private void write(HttpExchange ex, String id, byte[] body) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(origin + "/products/" + id))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            send(ex, 502, r.body());
            return;
        }
        // 원본 갱신이 끝난 뒤에 캐시를 지운다
        http.send(redisReq("product:" + id, "").DELETE().build(), HttpResponse.BodyHandlers.discarding());
        send(ex, 200, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
    }

    private void put(String key, byte[] value, int ttlSeconds) throws Exception {
        http.send(redisReq(key, "?ttl=" + ttlSeconds).PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build(),
            HttpResponse.BodyHandlers.discarding());
    }

    private HttpRequest.Builder redisReq(String key, String query) {
        String enc = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(redis + "/keys/" + enc + query));
        if (auth != null) b.header("Authorization", auth);
        return b;
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
    }
}
