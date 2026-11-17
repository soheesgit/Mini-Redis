package minidb.engine;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import minidb.cache.RedisCache;
import minidb.sql.Parser;
import minidb.sql.Ast.Select;
import minidb.storage.Faults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryCacheTest {
    @TempDir Path dir;
    // HTTP 경계만 대역으로 둔다. Parser/Database/Table/WAL/코덱은 실제 구현을 연결한다.
    static final class Server implements AutoCloseable {
        final HttpServer server;
        final Map<String, byte[]> values = new ConcurrentHashMap<>();
        volatile int status = 200;
        volatile String authorization, query;
        Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/keys/", x -> {
                authorization = x.getRequestHeaders().getFirst("Authorization");
                query = x.getRequestURI().getQuery();
                int code = status;
                String key = x.getRequestURI().getPath();
                byte[] body = new byte[0];
                if (code == 200 && x.getRequestMethod().equals("GET")) {
                    body = values.get(key);
                    if (body == null) { code = 404; body = new byte[0]; }
                } else if (code == 200) values.put(key, x.getRequestBody().readAllBytes());
                x.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
                x.getResponseBody().write(body);
                x.close();
            });
            server.start();
        }
        RedisCache client() { return new RedisCache("http://127.0.0.1:" + server.getAddress().getPort(), "secret"); }
        public void close() { server.stop(0); }
    }
    static Result sql(Database db, String sql) throws IOException { return ExecutorTest.run(db, sql); }
    static List<String> rows(Result r) { return ExecutorTest.text(r); }

    @Test void cachesAcrossReopenAndInvalidatesUncachedWritesEvenWithSameSizeAndTimestamp() throws Exception {
        try (var server = new Server()) {
            try (var db = Database.openCached(dir, false, server.client())) {
                sql(db, "CREATE TABLE t (v INT); INSERT INTO t VALUES (10);");
                assertEquals(List.of("1|10"), rows(sql(db, "SELECT * FROM t;")));
                assertEquals("ttl=60", server.query);
                assertEquals("Bearer secret", server.authorization);
                sql(db, "select * from T;");
                assertEquals("cache: hits=1 misses=1", db.cacheSummary());
            }
            try (var db = Database.openCached(dir, false, server.client())) {
                sql(db, "SELECT * FROM t;");
                assertEquals("cache: hits=1 misses=0", db.cacheSummary());
            }
            Path file = dir.resolve("t.db");
            var time = Files.getLastModifiedTime(file);
            long size = Files.size(file);
            try (var db = Database.open(dir, false)) { sql(db, "UPDATE t SET v = 20;"); }
            Files.setLastModifiedTime(file, time);
            assertEquals(size, Files.size(file));
            try (var db = Database.openCached(dir, false, server.client())) {
                assertEquals(List.of("1|20"), rows(sql(db, "SELECT * FROM t;")));
                sql(db, "INSERT INTO t VALUES (30);");
                assertEquals(2, sql(db, "SELECT * FROM t;").affected());
                sql(db, "DELETE FROM t;");
                assertTrue(sql(db, "SELECT * FROM t;").rows().isEmpty());
                sql(db, "SELECT * FROM t;");
                assertEquals("cache: hits=1 misses=3", db.cacheSummary());
            }
        }
    }

    @Test void queryMeaningAndDatabaseNamespaceStaySeparate() throws Exception {
        try (var server = new Server(); var db = Database.openCached(dir, false, server.client())) {
            sql(db, "CREATE TABLE t (v INT, name VARCHAR(10)); INSERT INTO t VALUES (2, 'a'), (1, 'b'), (NULL, 'c');");
            for (String query : List.of("SELECT v FROM t ORDER BY v;", "SELECT v FROM t ORDER BY v DESC;",
                    "SELECT v FROM t LIMIT 1;", "SELECT v FROM t LIMIT 2;", "SELECT name FROM t;",
                    "SELECT v FROM t WHERE v IS NULL;", "SELECT v FROM t WHERE v IS NOT NULL;",
                    "SELECT v FROM t WHERE v BETWEEN 1 AND 2;", "SELECT v FROM t WHERE v IN (2);",
                    "SELECT v FROM t WHERE name LIKE 'a%';", "SELECT v FROM t WHERE NOT (v = 1 OR v = 2);",
                    "SELECT v FROM t WHERE v > 1 AND name != 'b';")) {
                List<String> expected = rows(sql(db, query));
                assertEquals(expected, rows(sql(db, query)));
            }
            assertEquals("cache: hits=12 misses=12", db.cacheSummary());
            try (var other = Database.openCached(dir.resolve("other"), false, server.client())) {
                sql(other, "CREATE TABLE t (v INT, name VARCHAR(10)); INSERT INTO t VALUES (2, 'a'), (1, 'b'), (NULL, 'c');");
                sql(other, "SELECT v FROM t ORDER BY v;");
                assertEquals("cache: hits=0 misses=1", other.cacheSummary());
            }
        }
    }

    @Test void cacheErrorsAndCorruptPayloadFallBackWithoutHidingSqlErrors() throws Exception {
        for (int code : new int[]{200, 401, 500}) {
            try (var server = new Server(); var db = Database.openCached(dir.resolve("s" + code), false, server.client())) {
                sql(db, "CREATE TABLE t (v INT); INSERT INTO t VALUES (7);");
                sql(db, "SELECT * FROM t;");
                server.values.replaceAll((k, v) -> new byte[]{1, 2, 3});
                server.status = code;
                assertEquals(List.of("1|7"), rows(sql(db, "SELECT * FROM t;")));
                assertTrue(db.cacheSummary().startsWith("cache: off"));
                assertEquals(List.of("1|7"), rows(sql(db, "SELECT * FROM t;")));
                assertThrows(minidb.sql.SqlException.class, () -> sql(db, "SELECT missing FROM t;"));
            }
        }
        try (var server = new Server(); var db = Database.openCached(dir.resolve("down"), false, server.client())) {
            sql(db, "CREATE TABLE t (v INT);");
            server.close();
            assertTrue(sql(db, "SELECT * FROM t;").rows().isEmpty());
            assertTrue(db.cacheSummary().startsWith("cache: off"));
        }
    }

    @Test void recoveredWalCannotReuseOldCachedResult() throws Exception {
        try (var server = new Server()) {
            try (var db = Database.openCached(dir, false, server.client())) {
                sql(db, "CREATE TABLE t (v INT); INSERT INTO t VALUES (1); SELECT * FROM t;");
            }
            AtomicBoolean fail = new AtomicBoolean(true);
            Faults faults = (point, index) -> { if (point.equals("data.write") && fail.getAndSet(false)) throw new IOException("cut"); };
            try (var db = Database.open(dir, false, faults)) {
                assertThrows(IOException.class, () -> sql(db, "UPDATE t SET v = 2;"));
            }
            try (var db = Database.openCached(dir, false, server.client())) {
                assertEquals(List.of("1|2"), rows(sql(db, "SELECT * FROM t;")));
                assertEquals("cache: hits=0 misses=1", db.cacheSummary());
            }
        }
    }

    @Test void sqlTypesSurviveCacheHitAndFailedMutationKeepsValidEntry() throws Exception {
        try (var server = new Server(); var db = Database.openCached(dir, false, server.client())) {
            sql(db, "CREATE TABLE t (i INT, b BIGINT, d DOUBLE, n DECIMAL(10,4), ok BOOLEAN, s VARCHAR(30), day DATE, dt DATETIME, ts TIMESTAMP);");
            sql(db, "INSERT INTO t VALUES (1, 9223372036854775807, 1.25, 12.3400, TRUE, '한글😀', '2026-11-17', '2026-11-17 12:30:00', '2026-11-17 12:30:00'), (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);");
            Result miss = sql(db, "SELECT * FROM t;");
            assertThrows(minidb.sql.SqlException.class, () -> sql(db, "UPDATE t SET i = 'bad';"));
            Result hit = sql(db, "SELECT * FROM t;");
            assertEquals(miss.columns(), hit.columns());
            for (int i = 0; i < miss.rows().size(); i++) assertArrayEquals(miss.rows().get(i), hit.rows().get(i));
            assertEquals("cache: hits=1 misses=1", db.cacheSummary());
        }
    }

    @Test void cacheWriteFailureStillReturnsSuccessfulQuery() throws Exception {
        try (var server = new Server()) {
            var cache = new QueryCache(server.client(), dir, ZoneId.of("UTC"));
            var select = (Select) Parser.parse("SELECT * FROM t;").getFirst();
            Result expected = new Result(List.of("v"), Collections.singletonList(new Object[]{1}), 1, null);
            assertSame(expected, cache.select(select, "r", () -> { server.status = 500; return expected; }));
            assertTrue(cache.summary().startsWith("cache: off"));
        }
    }

    @Test void oversizedResultSkipsStorageWithoutDisablingNextQuery() throws Exception {
        try (var server = new Server()) {
            var cache = new QueryCache(server.client(), dir, ZoneId.of("UTC"));
            var select = (Select) Parser.parse("SELECT * FROM t;").getFirst();
            Result large = new Result(List.of("v"), Collections.singletonList(new Object[]{"x".repeat(1 << 20)}), 1, null);
            assertSame(large, cache.select(select, "r1", () -> large));
            assertTrue(server.values.isEmpty());
            Result small = new Result(List.of("v"), Collections.singletonList(new Object[]{1}), 1, null);
            cache.select(select, "r2", () -> small);
            assertEquals(List.of("1"), rows(cache.select(select, "r2", () -> { throw new AssertionError("cache miss"); })));
            assertEquals("cache: hits=1 misses=2", cache.summary());
        }
    }
}
