package minidb.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import minidb.engine.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryServerTest {
    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @TempDir Path dir;

    @Test
    void httpContractAndCrud() throws Exception {
        try (var db = Database.open(dir, false); var server = QueryServer.start(LOOPBACK, db, 2)) {
            assertOk(sql(server, "CREATE TABLE t (v VARCHAR(40));"), "{\"affected\":0}");
            assertOk(sql(server, "INSERT INTO t VALUES ('한글\"a\\b'), (NULL);"), "{\"affected\":2}");
            assertOk(sql(server, "SELECT * FROM t ORDER BY id LIMIT 2;"),
                    "{\"columns\":[\"id\",\"v\"],\"rows\":[[\"1\",\"한글\\\"a\\\\b\"],[\"2\",null]]}");
            assertOk(sql(server, "UPDATE t SET v = 'new' WHERE id = 1;"), "{\"affected\":1}");
            assertOk(sql(server, "DELETE FROM t WHERE id = 2;"), "{\"affected\":1}");
            assertOk(sql(server, "SELECT v FROM t WHERE id = 1;"), "{\"columns\":[\"v\"],\"rows\":[[\"new\"]]}");

            assertEquals(400, sql(server, "").statusCode());
            assertEquals(400, sql(server, "DELETE FROM t; SELECT * FROM t;").statusCode());
            assertOk(sql(server, "SELECT id FROM t;"), "{\"columns\":[\"id\"],\"rows\":[[\"1\"]]}");
            var syntaxError = sql(server, "SELECT FROM;");
            assertEquals(400, syntaxError.statusCode());
            assertTrue(syntaxError.body().contains("\"line\":1"));
            assertTrue(syntaxError.body().contains("\"col\":"));
            assertEquals(400, send(request(server, "/query")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{(byte) 0xff})).build()).statusCode());
            assertEquals(413, sql(server, "x".repeat(QueryServer.MAX_BODY_BYTES + 1)).statusCode());
            assertEquals(404, send(request(server, "/query/extra").GET().build()).statusCode());
            var methodError = send(request(server, "/query").GET().build());
            assertEquals(405, methodError.statusCode());
            assertEquals("POST", methodError.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test
    void twoHttpSelectsReadTheSameTableAtTheSameTime() throws Exception {
        AtomicBoolean armed = new AtomicBoolean();
        CountDownLatch bothReading = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> workerNames = ConcurrentHashMap.newKeySet();
        try (var db = Database.open(dir, false, (point, index) -> {
            if (armed.get() && point.equals("data.read")) {
                workerNames.add(Thread.currentThread().getName());
                bothReading.countDown();
                awaitRelease(release);
            }
        }); var server = QueryServer.start(LOOPBACK, db, 2)) {
            assertOk(sql(server, "CREATE TABLE t (v INT);"), "{\"affected\":0}");
            assertOk(sql(server, "INSERT INTO t VALUES (10), (20);"), "{\"affected\":2}");
            armed.set(true);

            var first = sqlAsync(server, "SELECT id FROM t WHERE id = 1;");
            var second = sqlAsync(server, "SELECT id FROM t WHERE id = 2;");
            try {
                assertTrue(bothReading.await(5, SECONDS), "두 요청이 동시에 페이지 읽기에 진입해야 합니다");
                assertEquals(2, workerNames.size());
                assertTrue(workerNames.stream().allMatch(name -> name.startsWith("minidb-sql-")));
                assertFalse(first.isDone());
                assertFalse(second.isDone());
            } finally {
                release.countDown();
            }
            assertOk(first.get(5, SECONDS), "{\"columns\":[\"id\"],\"rows\":[[\"1\"]]}");
            assertOk(second.get(5, SECONDS), "{\"columns\":[\"id\"],\"rows\":[[\"2\"]]}");
        }
    }

    @Test
    void blockedWriteDoesNotBlockAnotherTableAndReadersSeeTheCommit() throws Exception {
        AtomicBoolean pauseNextWrite = new AtomicBoolean();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var db = Database.open(dir, false, (point, index) -> {
            if (point.equals("data.write") && pauseNextWrite.compareAndSet(true, false)) {
                writing.countDown();
                awaitRelease(release);
            }
        }); var server = QueryServer.start(LOOPBACK, db, 3)) {
            assertOk(sql(server, "CREATE TABLE a (v INT);"), "{\"affected\":0}");
            assertOk(sql(server, "CREATE TABLE b (v INT);"), "{\"affected\":0}");
            assertOk(sql(server, "INSERT INTO a VALUES (10);"), "{\"affected\":1}");
            pauseNextWrite.set(true);

            var writer = sqlAsync(server, "UPDATE a SET v = 20 WHERE id = 1;");
            CompletableFuture<HttpResponse<String>> sameTable;
            try {
                assertTrue(writing.await(5, SECONDS), "테이블 a의 쓰기가 커밋에 진입해야 합니다");
                sameTable = sqlAsync(server, "SELECT v FROM a WHERE id = 1;");
                assertOk(sqlAsync(server, "INSERT INTO b VALUES (30);").get(5, SECONDS), "{\"affected\":1}");
                assertOk(sqlAsync(server, "SELECT v FROM b;").get(5, SECONDS),
                        "{\"columns\":[\"v\"],\"rows\":[[\"30\"]]}");
                assertFalse(writer.isDone());
                assertFalse(sameTable.isDone());
            } finally {
                release.countDown();
            }
            assertOk(writer.get(5, SECONDS), "{\"affected\":1}");
            assertOk(sameTable.get(5, SECONDS), "{\"columns\":[\"v\"],\"rows\":[[\"20\"]]}");
        }
    }

    @Test
    void concurrentHttpInsertsAssignUniqueIds() throws Exception {
        try (var db = Database.open(dir, false); var server = QueryServer.start(LOOPBACK, db, 8)) {
            assertOk(sql(server, "CREATE TABLE t (v INT);"), "{\"affected\":0}");
            var requests = IntStream.range(0, 64)
                    .mapToObj(value -> sqlAsync(server, "INSERT INTO t VALUES (" + value + ");")).toList();
            for (var pending : requests) assertOk(pending.get(10, SECONDS), "{\"affected\":1}");

            String rows = IntStream.rangeClosed(1, 64).mapToObj(id -> "[\"" + id + "\"]")
                    .collect(Collectors.joining(","));
            assertOk(sql(server, "SELECT id FROM t ORDER BY id LIMIT 100;"),
                    "{\"columns\":[\"id\"],\"rows\":[" + rows + "]}");
        }
    }

    @Test
    void rejectsOverloadAndReleasesAdmissionAfterTheRequest() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var db = Database.open(dir, false, (point, index) -> {
            if (point.equals("data.write")) {
                writing.countDown();
                awaitRelease(release);
            }
        }); var server = QueryServer.start(LOOPBACK, db, 1, 0)) {
            assertOk(sql(server, "CREATE TABLE t (v INT);"), "{\"affected\":0}");
            var writer = sqlAsync(server, "INSERT INTO t VALUES (1);");
            try {
                assertTrue(writing.await(5, SECONDS));
                assertEquals(503, sqlAsync(server, "SELECT * FROM t;").get(5, SECONDS).statusCode());
            } finally {
                release.countDown();
            }
            assertOk(writer.get(5, SECONDS), "{\"affected\":1}");
            assertOk(sql(server, "SELECT id FROM t;"), "{\"columns\":[\"id\"],\"rows\":[[\"1\"]]}");
        }
    }

    @Test
    void exclusiveDirectoryLockIsReleasedOnClose() throws IOException {
        try (var db = Database.open(dir, false)) {
            var error = assertThrows(IOException.class, () -> {
                try (var duplicate = Database.open(dir.resolve("."), false)) {
                    throw new AssertionError("같은 데이터 폴더에 DB를 두 번 열었습니다");
                }
            });
            assertTrue(error.getMessage().contains("이미 사용 중인 데이터 폴더"));
        }
        try (var reopened = Database.open(dir, false)) {
            assertEquals(0, reopened.execute(minidb.sql.Parser.parse("CREATE TABLE t (v INT);").getFirst()).affected());
        }
    }

    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            if (!release.await(10, SECONDS)) throw new IOException("테스트에서 락을 해제하지 않았습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static HttpRequest.Builder request(QueryServer server, String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.address().getPort() + path))
                .timeout(Duration.ofSeconds(12));
    }

    private static CompletableFuture<HttpResponse<String>> sqlAsync(QueryServer server, String sql) {
        return HTTP.sendAsync(post(server, sql), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sql(QueryServer server, String sql) throws Exception {
        return send(post(server, sql));
    }

    private static HttpRequest post(QueryServer server, String sql) {
        return request(server, "/query").header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql)).build();
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertOk(HttpResponse<String> response, String body) {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(body, response.body());
    }
}
