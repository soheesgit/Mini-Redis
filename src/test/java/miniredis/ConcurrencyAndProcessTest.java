package miniredis;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import miniredis.engine.Engine;
import miniredis.httpapi.HttpApi;
import miniredis.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 4단계 통과 기준: 동시 INCR 100,000, kill -9 복구. 그리고 별도 프로세스 잠금 충돌. */
class ConcurrencyAndProcessTest {
    @TempDir
    Path dir;

    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void concurrentIncrIsExact() throws Exception {
        try (Engine e = Engine.start(new Engine.Config(dir, 0, Store.Policy.NOEVICTION))) {
            HttpServer s = HttpApi.start(new InetSocketAddress("127.0.0.1", 0), e, null);
            try {
                URI uri = URI.create("http://127.0.0.1:" + s.getAddress().getPort() + "/keys/counter/incr");
                AtomicInteger failures = new AtomicInteger();
                List<Thread> threads = new ArrayList<>();
                for (int t = 0; t < 100; t++) {
                    threads.add(Thread.ofVirtual().start(() -> {
                        for (int i = 0; i < 1000; i++) {
                            try {
                                HttpRequest req = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
                                if (HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) failures.incrementAndGet();
                            } catch (Exception x) {
                                failures.incrementAndGet();
                            }
                        }
                    }));
                }
                for (Thread t : threads) t.join();
                assertEquals(0, failures.get());
                assertEquals(100_000L, Long.parseLong(new String((byte[]) e.call("GET", "counter"))));
            } finally {
                s.stop(0);
            }
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    Process server(int port, String... extra) throws IOException {
        return server(List.of(), port, extra);
    }

    Process server(List<String> jvmOptions, int port, String... extra) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java")));
        cmd.addAll(jvmOptions);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), "miniredis.Main",
            "--addr", "127.0.0.1:" + port, "--dir", dir.toString()));
        cmd.addAll(List.of(extra));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("MINIREDIS_PASSWORD");
        return pb.start();
    }

    static void waitReady(int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try {
                if (HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ping")).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        fail("서버가 뜨지 않았다");
    }

    static HttpResponse<String> req(int port, String method, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void killDash9ThenRestartKeepsAcknowledgedWrites() throws Exception {
        int port = freePort();
        Process p = server(port);
        try {
            waitReady(port);
            assertEquals(200, req(port, "PUT", "/keys/k", "살아남는다").statusCode());
            for (int i = 0; i < 50; i++) req(port, "POST", "/keys/n/incr", null);
        } finally {
            p.destroyForcibly(); // 유닉스에서 SIGKILL. 종료 훅도 fsync도 실행되지 않는다
            p.waitFor(10, TimeUnit.SECONDS);
        }
        Process p2 = server(port);
        try {
            waitReady(port);
            assertEquals("살아남는다", req(port, "GET", "/keys/k", null).body());
            assertEquals("50", req(port, "GET", "/keys/n", null).body());
        } finally {
            p2.destroyForcibly();
            p2.waitFor(10, TimeUnit.SECONDS);
        }
    }

    /**
     * run.sh와 같은 OOM 옵션 + 힙 32MB로 띄우고 메모리가 바닥날 때까지 쓴다.
     * 응답 없이 멈추지 않고 바로 끝나야 하고(종료 코드 3), 재시작하면 응답받은 쓰기는 전부 남아 있어야 한다.
     */
    @Test
    void outOfMemoryStopsServerAndKeepsAcknowledgedWrites() throws Exception {
        int port = freePort();
        Process p = server(List.of("-Xmx32m", "-XX:+ExitOnOutOfMemoryError"), port);
        List<String> acked = new ArrayList<>();
        try {
            waitReady(port);
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            String value = "x".repeat(16 * 1024);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            for (int i = 0; p.isAlive() && System.nanoTime() < deadline; i++) {
                try {
                    HttpRequest put = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/keys/k" + i))
                        .timeout(java.time.Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.ofString(value)).build();
                    if (client.send(put, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) acked.add("k" + i);
                } catch (IOException ignored) {
                    // 핸들러 쪽에서 OOM이 나거나 프로세스가 끝나는 중이면 연결이 끊긴다
                }
            }
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), "OOM 뒤에도 프로세스가 살아 있다 (응답 없이 멈춤)");
            String out = new String(p.getInputStream().readAllBytes());
            assertEquals(3, p.exitValue(), out); // ExitOnOutOfMemoryError의 종료 코드
            assertTrue(out.contains("Terminating due to java.lang.OutOfMemoryError"), out);
        } finally {
            p.destroyForcibly();
        }
        assertTrue(acked.size() > 100, "메모리가 너무 빨리 바닥났다: " + acked.size());

        Process p2 = server(port);
        try {
            waitReady(port);
            for (String k : acked) assertEquals(200, req(port, "HEAD", "/keys/" + k, null).statusCode(), "사라진 키: " + k);
        } finally {
            p2.destroyForcibly();
            p2.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void secondProcessOnSameDirIsRefused() throws Exception {
        int port = freePort();
        Process first = server(port);
        try {
            waitReady(port);
            Process second = server(freePort());
            assertTrue(second.waitFor(20, TimeUnit.SECONDS));
            String out = new String(second.getInputStream().readAllBytes());
            assertEquals(1, second.exitValue());
            assertTrue(out.contains("다른 프로세스"), out);
        } finally {
            first.destroyForcibly();
            first.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void externalBindWithoutPasswordIsRefused() throws Exception {
        List<String> cmd = List.of(ProcessHandle.current().info().command().orElse("java"),
            "-cp", System.getProperty("java.class.path"), "miniredis.Main",
            "--addr", "0.0.0.0:" + freePort(), "--dir", dir.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("MINIREDIS_PASSWORD");
        Process p = pb.start();
        assertTrue(p.waitFor(20, TimeUnit.SECONDS));
        assertEquals(1, p.exitValue());
    }
}
