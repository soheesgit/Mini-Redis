package example.flowdemo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import miniredis.debug.Trace;
import miniredis.engine.Engine;
import miniredis.httpapi.HttpApi;
import miniredis.store.Store;
import miniredis.store.StoreException;

/**
 * 미니 Redis 흐름 관찰 데모 서버 (데모페이지-기획서.md, 결정 1 (가)).
 *
 * 브라우저 ──▶ 이 서버 127.0.0.1:8083 ──① 실제 HTTP──▶ 내장 미니 Redis 127.0.0.1:8090
 *                                     └─② 같은 JVM 메서드 호출──▶ Trace 읽기, DEBUG 명령(큐를 거친다)
 *
 * 미니 Redis는 라이브러리처럼 쓴다: Engine.start + HttpApi.start. 관찰 기능은 Engine.Config의 Debug로만 켜진다.
 * 실행: java -cp target/classes example.flowdemo.FlowDemoMain [--port 8083] [--redis-port 8090] [--dir ./data-demo]
 */
public final class FlowDemoMain {
    private static final String MARK = ".flowdemo";
    private static final Set<String> METHODS = Set.of("GET", "PUT", "POST", "DELETE", "HEAD");
    private static final Set<String> DEBUG_SUBS = Set.of("TABLE", "LOCATE", "COLLIDE", "BUCKETS", "AOF", "TICKS", "TICK");
    // 정적 파일: classpath의 /flowdemo/ 아래 이름만(경로 구분자 없이) 허용한다
    private static final java.util.regex.Pattern STATIC = java.util.regex.Pattern.compile("^/([a-z0-9-]+\\.(html|js|css))$");
    private static final Map<String, String> SOURCES = Map.of(
        "HttpApi.java", "httpapi/HttpApi.java", "Engine.java", "engine/Engine.java", "Store.java", "store/Store.java",
        "HashTable.java", "hashtable/HashTable.java", "Persistence.java", "persist/Persistence.java",
        "Records.java", "persist/Records.java", "LruList.java", "store/LruList.java", "Entry.java", "store/Entry.java");

    private final int port, redisPort;
    private final Path dir;
    private final String password = emptyToNull(System.getenv("MINIREDIS_PASSWORD")); // 브라우저로 내보내지 않는다
    private final Trace trace = new Trace(); // 재시작해도 같은 기록을 이어 간다
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build();

    // 아래는 synchronized 메서드에서만 바꾼다
    private Engine engine;
    private HttpServer redis;
    private int epoch;
    private long maxmemory = 0;
    private Store.Policy policy = Store.Policy.NOEVICTION;
    private boolean fixedSeed = false;
    private String lastError;

    private FlowDemoMain(int port, int redisPort, Path dir) {
        this.port = port;
        this.redisPort = redisPort;
        this.dir = dir;
    }

    public static void main(String[] args) throws Exception {
        int port = 8083, redisPort = 8090;
        Path dir = Path.of("./data-demo");
        boolean keep = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--redis-port" -> redisPort = Integer.parseInt(args[++i]);
                case "--dir" -> dir = Path.of(args[++i]);
                case "--keep" -> keep = true; // 지난번 데모 데이터를 이어 쓴다 (기본은 빈 상태로 시작)
                default -> throw new IllegalArgumentException("알 수 없는 옵션: " + args[i]);
            }
        }
        FlowDemoMain demo = new FlowDemoMain(port, redisPort, dir);
        demo.prepareDir();
        if (!keep) demo.clearDir();
        demo.startRedis();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0); // 루프백 고정
        server.createContext("/", demo::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("flow demo: http://127.0.0.1:" + port + "  (미니 Redis 127.0.0.1:" + redisPort + ", data " + dir + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            demo.stopRedis();
        }));
    }

    // ---------------- 내장 미니 Redis ----------------

    /** 데이터 폴더: 없으면 만들고 표식을 둔다. 표식 없는 기존 폴더(예: ./data)는 쓰지 않는다. */
    private void prepareDir() throws IOException {
        if (!Files.exists(dir)) Files.createDirectories(dir);
        if (!Files.exists(dir.resolve(MARK))) {
            try (Stream<Path> s = Files.list(dir)) {
                if (s.findAny().isPresent()) throw new IOException(dir + "는 데모 폴더가 아니다(" + MARK + " 없음). 다른 --dir을 쓰자");
            }
            Files.createFile(dir.resolve(MARK));
        }
    }

    /** 표식 있는 데모 폴더의 파일을 지운다(표식만 남긴다). 서버가 멈춰 있을 때만 부른다. */
    private void clearDir() throws IOException {
        if (!Files.exists(dir.resolve(MARK))) throw new StoreException(403, "데모 폴더가 아니다");
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) if (!p.getFileName().toString().equals(MARK)) Files.delete(p);
        }
    }

    private synchronized void startRedis() throws IOException {
        epoch++;
        engine = Engine.start(new Engine.Config(dir, maxmemory, policy, new Engine.Debug(trace, fixedSeed)));
        redis = HttpApi.start(new InetSocketAddress("127.0.0.1", redisPort), engine, password);
        lastError = null;
    }

    private synchronized void stopRedis() {
        if (redis != null) redis.stop(0);
        redis = null;
        if (engine != null) {
            try {
                engine.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        engine = null;
    }

    /** 설정 변경·손상 실험·초기화 후 다시 띄운다. 시작이 거부되면(중간 손상) 멈춘 채로 이유를 남긴다. */
    private synchronized Map<String, Object> restart(Map<String, String> q) throws IOException {
        if (q.containsKey("maxmemory")) {
            long m = Long.parseLong(q.get("maxmemory"));
            if (m < 0 || m > (1L << 30)) throw new StoreException(400, "maxmemory는 0~1GB");
            maxmemory = m;
        }
        if (q.containsKey("policy")) policy = Store.Policy.parse(q.get("policy"));
        if (q.containsKey("fixedSeed")) fixedSeed = q.get("fixedSeed").equals("1");
        String action = q.getOrDefault("action", "restart");
        stopRedis();
        String note = null;
        if (action.equals("reset")) {
            clearDir();
            note = "데모 데이터 폴더를 비웠다";
        } else if (action.equals("truncate") || action.equals("flip")) {
            note = damage(action, Integer.parseInt(q.getOrDefault("n", "3")));
        }
        try {
            startRedis();
        } catch (IOException e) {
            lastError = e.getMessage();
            trace.emit("server.fail", "req", 0, "error", lastError);
        }
        Map<String, Object> m = status();
        m.put("note", note);
        return m;
    }

    /** 표식 있는 데모 폴더의 현재 AOF만 건드린다. truncate: 끝 n바이트 자르기, flip: 앞에서 n번째 바이트 뒤집기 */
    private String damage(String kind, int n) throws IOException {
        if (!Files.exists(dir.resolve(MARK))) throw new StoreException(403, "데모 폴더가 아니다");
        List<String> mf = Files.readAllLines(dir.resolve("manifest"));
        Path aof = dir.resolve(mf.get(mf.size() - 1).trim());
        long size = Files.size(aof);
        if (size == 0) return "AOF가 비어 있어 손상할 것이 없다";
        try (FileChannel c = FileChannel.open(aof, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            if (kind.equals("truncate")) {
                c.truncate(Math.max(0, size - Math.max(1, n)));
                return aof.getFileName() + " 끝 " + n + "바이트를 잘랐다 (" + size + " → " + c.size() + ")";
            }
            long pos = Math.min(Math.max(0, n), size - 1);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            c.read(b, pos);
            b.put(0, (byte) ~b.get(0)).rewind();
            c.write(b, pos);
            return aof.getFileName() + " 위치 " + pos + "의 바이트를 뒤집었다";
        }
    }

    private synchronized Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", engine != null);
        m.put("epoch", epoch);
        m.put("redisPort", redisPort);
        m.put("dir", dir.toString());
        m.put("maxmemory", maxmemory);
        m.put("policy", policy.name().toLowerCase().replace('_', '-'));
        m.put("fixedSeed", fixedSeed);
        m.put("password", password != null); // 있는지만 알린다
        m.put("error", lastError);
        return m;
    }

    private synchronized Engine engine() {
        if (engine == null) throw new StoreException(503, "미니 Redis가 멈춰 있다: " + lastError);
        return engine;
    }

    // ---------------- HTTP ----------------

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        String type = "application/json; charset=utf-8";
        byte[] body;
        try {
            String path = ex.getRequestURI().getPath();
            guard(ex, path);
            java.util.regex.Matcher sm = STATIC.matcher(path.equals("/") ? "/index.html" : path);
            if (sm.matches()) {
                String name = sm.group(1);
                try (InputStream in = FlowDemoMain.class.getResourceAsStream("/flowdemo/" + name)) {
                    if (in == null) throw new StoreException(404, "없는 파일");
                    body = in.readAllBytes();
                }
                type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8";
            } else {
                body = Trace.json(api(ex, path)).getBytes(StandardCharsets.UTF_8);
            }
        } catch (StoreException e) {
            status = e.code;
            body = Trace.json(Map.of("error", e.getMessage())).getBytes(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = 503;
            body = "{\"error\":\"중단됨\"}".getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            status = 500;
            body = Trace.json(Map.of("error", String.valueOf(e.getMessage()))).getBytes(StandardCharsets.UTF_8);
        }
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
        ex.close();
    }

    /** 중계 서버가 새 구멍이 되지 않게: Host 고정(DNS 리바인딩), 다른 출처 거부, /api는 전용 헤더 필수(CORS 허용 헤더는 보내지 않는다) */
    private void guard(HttpExchange ex, String path) {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (!("127.0.0.1:" + port).equals(host) && !("localhost:" + port).equals(host)) throw new StoreException(403, "Host 거부");
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.equals("http://127.0.0.1:" + port) && !origin.equals("http://localhost:" + port)) {
            throw new StoreException(403, "다른 출처 거부");
        }
        if (path.startsWith("/api/") && !"1".equals(ex.getRequestHeaders().getFirst("X-Flow-Demo"))) {
            throw new StoreException(403, "X-Flow-Demo 헤더 필요");
        }
    }

    private Object api(HttpExchange ex, String path) throws Exception {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String method = ex.getRequestMethod();
        return switch (path) {
            case "/api/send" -> { post(method); yield send(q, readBody(ex)); }
            case "/api/burst" -> { post(method); yield burst(q, readBody(ex)); }
            case "/api/trace" -> new Trace.Raw(trace.since(Long.parseLong(q.getOrDefault("since", "0")), 1500));
            case "/api/debug" -> debug(q);
            case "/api/server" -> method.equals("POST") ? restart(q) : status();
            case "/api/code" -> code(q);
            default -> throw new StoreException(404, "없는 경로");
        };
    }

    private static void post(String method) {
        if (!method.equals("POST")) throw new StoreException(405, "POST만");
    }

    /** 브라우저가 고른 요청 1개를 실제 HTTP로 미니 Redis에 보낸다. 대상 주소는 고정이고 경로·쿼리만 받는다. */
    private Map<String, Object> send(Map<String, String> q, byte[] body) throws Exception {
        String method = q.getOrDefault("method", "GET").toUpperCase();
        if (!METHODS.contains(method)) throw new StoreException(400, "메서드");
        String target = q.getOrDefault("path", "/ping");
        if (!target.startsWith("/") || target.startsWith("//")) throw new StoreException(400, "경로는 /로 시작");
        URI uri;
        try {
            uri = URI.create("http://127.0.0.1:" + redisPort + target);
        } catch (IllegalArgumentException e) {
            throw new StoreException(400, "경로 형식: " + e.getMessage());
        }
        if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != redisPort) throw new StoreException(400, "대상 고정");
        if ("1mb".equals(q.get("preset"))) body = new byte[HttpApi.MAX_BODY + 1];
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        b.method(method, method.equals("PUT") || method.equals("POST")
            ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody());
        if ("1".equals(q.get("origin"))) b.header("Origin", "http://evil.example"); // 다른 사이트에서 온 척
        String auth = q.getOrDefault("auth", "ok");
        if (auth.equals("ok") && password != null) b.header("Authorization", "Bearer " + password);
        if (auth.equals("wrong")) b.header("Authorization", "Bearer wrong-password");
        long t = System.nanoTime();
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.statusCode());
        m.put("us", (System.nanoTime() - t) / 1000);
        m.put("traceId", Long.parseLong(r.headers().firstValue("X-Trace-Id").orElse("0")));
        m.put("contentType", r.headers().firstValue("Content-Type").orElse(""));
        byte[] rb = r.body();
        m.put("bodyBytes", rb.length);
        m.put("body", new String(rb, 0, Math.min(rb.length, 64 * 1024), StandardCharsets.UTF_8));
        return m;
    }

    /** 같은 요청 N개. concurrent면 가상 스레드 N개를 출발선에 세웠다가 한꺼번에 보낸다. 경로의 {i}는 순번으로 바뀐다. */
    private Map<String, Object> burst(Map<String, String> q, byte[] body) throws Exception {
        int n = Integer.parseInt(q.getOrDefault("count", "10"));
        if (n < 1 || n > 500) throw new StoreException(400, "count는 1~500");
        boolean concurrent = !"0".equals(q.get("concurrent"));
        String path = q.getOrDefault("path", "/ping");
        List<Map<String, Object>> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, String> one = new LinkedHashMap<>(q);
            one.put("path", path.replace("{i}", Integer.toString(i)));
            Runnable task = () -> {
                try {
                    if (concurrent) go.await();
                    results.add(send(one, body));
                } catch (Exception e) {
                    results.add(Map.of("status", 0, "error", String.valueOf(e.getMessage())));
                }
            };
            if (concurrent) threads.add(Thread.ofVirtual().start(task));
            else task.run();
        }
        go.countDown();
        for (Thread th : threads) th.join();
        Map<String, Long> statuses = new LinkedHashMap<>();
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> r : results) {
            statuses.merge(String.valueOf(r.get("status")), 1L, Long::sum);
            if (r.get("traceId") != null) ids.add(r.get("traceId"));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sent", n);
        m.put("statuses", statuses);
        m.put("traceIds", ids);
        return m;
    }

    /** DEBUG 하위 명령을 큐에 넣어 executor가 실행하게 한다(요청 ID -1 = 관찰). */
    private Object debug(Map<String, String> q) throws InterruptedException {
        String sub = q.getOrDefault("cmd", "TABLE").toUpperCase();
        if (!DEBUG_SUBS.contains(sub)) throw new StoreException(400, "DEBUG 하위 명령");
        List<String> args = new ArrayList<>(List.of("DEBUG", sub));
        for (int i = 1; q.containsKey("a" + i); i++) args.add(q.get("a" + i));
        Engine e = engine();
        trace.bind(-1);
        try {
            return new Trace.Raw((String) e.call(args.toArray(String[]::new)));
        } finally {
            trace.unbind();
        }
    }

    /** 코드 위치 패널: 고정 목록 파일에서 find 문자열이 있는 줄을 찾아 앞뒤 줄을 준다(줄 번호가 밀려도 찾는다). */
    private Map<String, Object> code(Map<String, String> q) throws IOException {
        String rel = SOURCES.get(q.getOrDefault("file", ""));
        if (rel == null) throw new StoreException(400, "허용되지 않은 파일");
        Path p = Path.of("src/main/java/miniredis").resolve(rel);
        if (!Files.exists(p)) throw new StoreException(404, "소스 없음 (프로젝트 폴더에서 실행해야 한다)");
        List<String> lines = Files.readAllLines(p);
        String find = q.getOrDefault("find", "");
        int at = 0;
        for (int i = 0; i < lines.size(); i++) if (!find.isEmpty() && lines.get(i).contains(find)) { at = i; break; }
        int from = Math.max(0, at - 6), to = Math.min(lines.size(), at + 9);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", q.get("file"));
        m.put("line", at + 1);
        m.put("from", from + 1);
        m.put("lines", lines.subList(from, to));
        return m;
    }

    // ---------------- 도우미 ----------------

    private static byte[] readBody(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(2 * 1024 * 1024 + 1);
        if (b.length > 2 * 1024 * 1024) throw new StoreException(413, "본문 2MB 초과");
        return b;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
