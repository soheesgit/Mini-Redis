package minidbms.engine;

import com.sun.net.httpserver.HttpServer;
import minidbms.cache.RedisCache;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SELECT 캐시 검증. MINIDBMS_TEST_REDIS(예: http://127.0.0.1:8080)가 있으면 실제 Mini Redis 서버로,
 * 없으면 같은 HTTP 규칙을 흉내 내는 가짜 서버로 돈다.
 */
public final class QueryCacheTest {
    private QueryCacheTest() {}

    public static void main(String[] args) throws Exception {
        String url = System.getenv("MINIDBMS_TEST_REDIS");
        HttpServer fake = url == null ? fakeRedis() : null;
        String target = url != null ? url : "http://127.0.0.1:" + fake.getAddress().getPort();
        try {
            RedisCache redis = new RedisCache(target, System.getenv("MINIREDIS_PASSWORD"));
            secondProcessHitsCache(redis);
            insertInvalidatesCache(redis);
            differentQueriesUseDifferentKeys(redis);
            codecKeepsValues();
            redisDownFallsBackToFile();
        } finally {
            if (fake != null) {
                fake.stop(0);
            }
        }
        System.out.println("Passed 5 cache checks (" + (url != null ? "real Mini Redis " + url : "fake Redis") + ").");
    }

    /** 첫 실행은 MISS, 같은 데이터 폴더를 쓰는 다음 실행(새 엔진 = 새 CLI 프로세스)은 Redis에서 HIT */
    private static void secondProcessHitsCache(RedisCache redis) throws IOException {
        Path dir = Files.createTempDirectory("mini-dbms-cache-");
        new SqlEngine(dir).execute("""
                INSERT INTO users (name, email) VALUES ('소희', 'sohee@example.com');
                INSERT INTO users (name, email) VALUES ('지호', 'jiho@example.com');
                """, result -> {});
        var first = new SqlEngine(dir, redis);
        List<SqlEngine.Result> a = run(first, "SELECT * FROM users;");
        expect(first.cacheSummary(), "cache: hits=0 misses=1");

        var second = new SqlEngine(dir, redis);
        List<SqlEngine.Result> b = run(second, "SELECT * FROM users;");
        expect(second.cacheSummary(), "cache: hits=1 misses=0");
        expect(b, a);
    }

    /** INSERT가 버전을 올리면 다음 SELECT는 MISS가 되고 새 행을 본다 */
    private static void insertInvalidatesCache(RedisCache redis) throws IOException {
        Path dir = Files.createTempDirectory("mini-dbms-cache-");
        var engine = new SqlEngine(dir, redis);
        run(engine, "INSERT INTO users (name, email) VALUES ('a', 'a@example.com');");
        run(engine, "SELECT id FROM users;");
        run(engine, "SELECT id FROM users;");
        expect(engine.cacheSummary(), "cache: hits=1 misses=1");

        run(engine, "INSERT INTO users (name, email) VALUES ('b', 'b@example.com');");
        var after = (SqlEngine.Selected) run(engine, "SELECT id FROM users;").get(0);
        expect(engine.cacheSummary(), "cache: hits=1 misses=2");
        expect(after.rows(), List.of(List.of(1L), List.of(2L)));
    }

    /** 열·조건·값 타입이 다르면 다른 키다 */
    private static void differentQueriesUseDifferentKeys(RedisCache redis) throws IOException {
        Path dir = Files.createTempDirectory("mini-dbms-cache-");
        var engine = new SqlEngine(dir, redis);
        run(engine, "INSERT INTO users (name, email) VALUES ('a', 'a@example.com');");
        run(engine, """
                SELECT * FROM users WHERE id = 1;
                SELECT * FROM users WHERE email = 'a@example.com';
                SELECT name FROM users WHERE id = 1;
                SELECT * FROM users WHERE id = 1;
                """);
        expect(engine.cacheSummary(), "cache: hits=1 misses=3");
    }

    /** 한글·탭·줄바꿈·빈 문자열·64KB 넘는 문자열도 그대로 돌아온다 */
    private static void codecKeepsValues() throws IOException {
        var selected = new SqlEngine.Selected(List.of("id", "name", "email"), List.of(
                List.of(1L, "소희\t탭\n줄", ""),
                List.of(Long.MAX_VALUE, "x".repeat(70_000), "o'brien@example.com")));
        expect(QueryCache.decode(QueryCache.encode(selected)), selected);
    }

    /** Redis가 꺼져 있어도 결과는 파일에서 정상으로 나오고, 캐시만 꺼진다 */
    private static void redisDownFallsBackToFile() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Path dir = Files.createTempDirectory("mini-dbms-cache-");
        var engine = new SqlEngine(dir, new RedisCache("http://127.0.0.1:" + closedPort, null));
        run(engine, "INSERT INTO users (name, email) VALUES ('a', 'a@example.com');");
        var rows = ((SqlEngine.Selected) run(engine, "SELECT name FROM users;").get(0)).rows();
        expect(rows, List.of(List.of("a")));
        if (!engine.cacheSummary().startsWith("cache: off")) {
            throw new AssertionError("Cache should be off when Redis is down: " + engine.cacheSummary());
        }
    }

    private static List<SqlEngine.Result> run(SqlEngine engine, String sql) throws IOException {
        var results = new ArrayList<SqlEngine.Result>();
        engine.execute(sql, results::add);
        return results;
    }

    private static void expect(Object actual, Object expected) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected: " + expected + "\n  actual: " + actual);
        }
    }

    /** Mini Redis HTTP API 중 캐시가 쓰는 세 가지만 흉내 낸다: GET, PUT ?ttl=, POST /incr */
    private static HttpServer fakeRedis() throws IOException {
        Map<String, byte[]> store = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys/", exchange -> {
            try (exchange) {
                String rest = exchange.getRequestURI().getRawPath().substring("/keys/".length());
                boolean incr = rest.endsWith("/incr");
                String key = URLDecoder.decode(incr ? rest.substring(0, rest.length() - 5) : rest, StandardCharsets.UTF_8);
                byte[] body = new byte[0];
                int status = 200;
                switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        body = store.get(key);
                        if (body == null) {
                            status = 404;
                            body = new byte[0];
                        }
                    }
                    case "PUT" -> store.put(key, exchange.getRequestBody().readAllBytes());
                    case "POST" -> {
                        long next = store.containsKey(key) ? Long.parseLong(new String(store.get(key), StandardCharsets.UTF_8)) + 1 : 1;
                        store.put(key, Long.toString(next).getBytes(StandardCharsets.UTF_8));
                        body = ("{\"value\":" + next + "}").getBytes(StandardCharsets.UTF_8);
                    }
                    default -> status = 405;
                }
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            }
        });
        server.start();
        return server;
    }
}
