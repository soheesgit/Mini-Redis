package minidb.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import minidb.engine.Database;
import minidb.sql.Parser;
import minidb.sql.SqlException;
import minidb.storage.StorageException;

/** HTTP 입출력과 고정 크기 SQL 워커 풀을 관리한다. Database의 생명주기는 호출자가 관리한다. */
public final class QueryServer implements AutoCloseable {
    static final int MAX_BODY_BYTES = 1 << 20;
    private static final int EXTRA_REQUESTS = 64;
    private static final System.Logger LOG = System.getLogger(QueryServer.class.getName());

    private final HttpServer http;
    private final Database db;
    private final ExecutorService sqlWorkers;
    private final ExecutorService httpHandlers;
    private final Semaphore admission;
    private volatile boolean stopping;

    private QueryServer(HttpServer http, Database db, int threads, int extraRequests) {
        this.http = http;
        this.db = db;
        sqlWorkers = Executors.newFixedThreadPool(threads, Thread.ofPlatform().name("minidb-sql-", 1).factory());
        httpHandlers = Executors.newVirtualThreadPerTaskExecutor();
        admission = new Semaphore(threads + extraRequests);
        http.setExecutor(httpHandlers);
        http.createContext("/", this::handle);
    }

    public static QueryServer start(InetSocketAddress address, Database db, int threads) throws IOException {
        return start(address, db, threads, EXTRA_REQUESTS);
    }

    static QueryServer start(InetSocketAddress address, Database db, int threads, int extraRequests) throws IOException {
        if (threads < 1 || extraRequests < 0 || threads > Integer.MAX_VALUE - extraRequests) {
            throw new IllegalArgumentException("SQL 스레드 수 또는 대기 요청 수가 올바르지 않습니다");
        }
        QueryServer server = new QueryServer(HttpServer.create(address, 0), db, threads, extraRequests);
        try {
            server.http.start();
            return server;
        } catch (RuntimeException | Error e) {
            server.close();
            throw e;
        }
    }

    public InetSocketAddress address() {
        return http.getAddress();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"/query".equals(exchange.getRequestURI().getRawPath())) {
                send(exchange, error(404, "없는 경로입니다"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, error(405, "POST 요청을 사용하세요"));
                return;
            }
            if (stopping || !admission.tryAcquire()) {
                send(exchange, busy());
                return;
            }
            Response response;
            try {
                // 본문을 읽기 전에 자리를 확보해 버퍼와 SQL 대기열을 함께 제한한다.
                byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                response = body.length > MAX_BODY_BYTES
                        ? error(413, "SQL 본문은 1 MiB까지 보낼 수 있습니다")
                        : dispatch(body);
            } finally {
                admission.release();
            }
            send(exchange, response);
        }
    }

    private Response dispatch(byte[] body) {
        try {
            // HTTP 연결이 끊겨도 실행 중인 SQL은 문장 단위로 완료한다.
            return CompletableFuture.supplyAsync(() -> execute(body), sqlWorkers).join();
        } catch (RejectedExecutionException e) {
            return busy();
        } catch (CompletionException e) {
            return internalError(e.getCause());
        }
    }

    private Response execute(byte[] body) {
        try {
            String sql = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            var statements = Parser.parse(sql);
            if (statements.size() != 1) return error(400, "요청마다 SQL 문장 하나를 보내세요");
            return new Response(200, Json.result(db.execute(statements.getFirst()), db.zone()));
        } catch (CharacterCodingException e) {
            return error(400, "SQL 본문은 UTF-8이어야 합니다");
        } catch (SqlException e) {
            return new Response(400, Json.error(e.getMessage(), e.line(), e.col()));
        } catch (IOException | StorageException e) {
            return internalError(e);
        }
    }

    private static Response internalError(Throwable e) {
        LOG.log(System.Logger.Level.ERROR, "SQL 실행 실패", e);
        return error(500, "SQL 실행 중 서버 오류가 발생했습니다");
    }

    private static Response busy() {
        return error(503, "서버가 처리할 수 있는 요청 수를 초과했습니다");
    }

    private static Response error(int status, String message) {
        return new Response(status, Json.error(message, 0, 0));
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(response.status(), -1);
            return;
        }
        byte[] json = response.json().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status(), json.length);
        exchange.getResponseBody().write(json);
    }

    @Override
    public synchronized void close() {
        if (stopping) return;
        stopping = true;
        http.stop(1);
        sqlWorkers.shutdown();
        httpHandlers.shutdown();
        // 실행 중인 파일 I/O를 인터럽트하지 않고 SQL 작업을 모두 끝낸다.
        boolean interrupted = awaitTermination(sqlWorkers);
        interrupted |= awaitTermination(httpHandlers);
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean awaitTermination(ExecutorService executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private record Response(int status, String json) {}
}
