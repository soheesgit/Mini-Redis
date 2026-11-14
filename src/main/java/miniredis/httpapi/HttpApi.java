package miniredis.httpapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import miniredis.engine.Engine;
import miniredis.store.StoreException;

/**
 * HTTP REST API. 요청마다 가상 스레드 1개가 이 코드를 실행하고, 데이터는 직접 만지지 않는다.
 * 검사(Origin, 인증, 크기, 입력)를 마친 뒤 명령을 엔진 큐에 넣고 결과를 기다린다.
 */
public final class HttpApi {
    public static final int MAX_BODY = 1 << 20;
    static final int MAX_KEY_BYTES = 1024;
    static final long MAX_TTL = 315_360_000L; // 10년

    private final Engine engine;
    private final byte[] passwordHash; // null이면 인증 끔

    private HttpApi(Engine engine, String password) {
        this.engine = engine;
        this.passwordHash = password == null ? null : sha256(password.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpServer start(InetSocketAddress addr, Engine engine, String password) throws IOException {
        HttpServer server = HttpServer.create(addr, 0);
        HttpApi api = new HttpApi(engine, password);
        server.createContext("/", api::handle);
        // start() 전에 반드시. 빠뜨리면 모든 요청이 스레드 하나에서 차례로 처리되는데, 테스트는 그래도 통과한다
        // 요청마다 가상 스레드를 하나씩 만듦 = 핸들러 스레드 생성 - 요청 받는걸 멀티스레드
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    private record Response(int status, byte[] body, String type) { }

    private void handle(HttpExchange ex) throws IOException {
        Response r;
        try {
            r = route(ex);
        } catch (StoreException e) {
            r = error(e.code, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = error(503, "중단됨");
        } catch (RuntimeException e) {
            r = error(500, "서버 오류");
            e.printStackTrace();
        }
        try {
            ex.getResponseHeaders().set("Content-Type", r.type);
            boolean noBody = r.body.length == 0 || ex.getRequestMethod().equals("HEAD");
            ex.sendResponseHeaders(r.status, noBody ? -1 : r.body.length); // 0을 넘기면 chunked가 되므로 -1
            if (!noBody) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(r.body);
                }
            }
        } finally {
            ex.close();
        }
    }

    private Response route(HttpExchange ex) throws IOException, InterruptedException {
        // 브라우저가 보낸 요청에는 Origin이 붙는다. 같은 PC의 웹페이지가 명령을 실행하지 못하게 모두 막는다
        if (ex.getRequestHeaders().containsKey("Origin")) return error(403, "Origin 헤더가 있는 요청은 거부");
        if (!authorized(ex.getRequestHeaders().getFirst("Authorization"))) return error(401, "인증 실패");

        String method = ex.getRequestMethod();
        List<String> seg = splitPath(ex.getRequestURI().getRawPath());
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());

        if (seg.size() == 1 && seg.get(0).equals("ping")) {
            if (!method.equals("GET")) return error(405, "허용되지 않는 메서드");
            call("PING");
            return json(200, "{\"pong\":true}");
        }
        if (!seg.isEmpty() && seg.get(0).equals("keys")) return keys(ex, method, seg, q);
        if (seg.size() == 2 && seg.get(0).equals("admin")) return admin(method, seg.get(1));
        return error(404, "없는 경로");
    }

    private Response keys(HttpExchange ex, String method, List<String> seg, Map<String, String> q)
            throws IOException, InterruptedException {
        if (seg.size() == 1) {
            return switch (method) {
                case "GET" -> {
                    @SuppressWarnings("unchecked")
                    List<String> keys = (List<String>) call("KEYS", q.getOrDefault("pattern", "*"));
                    StringBuilder sb = new StringBuilder("{\"keys\":[");
                    for (int i = 0; i < keys.size(); i++) sb.append(i == 0 ? "" : ",").append(quote(keys.get(i)));
                    yield json(200, sb.append("]}").toString());
                }
                case "DELETE" -> { call("FLUSHALL"); yield json(200, "{\"ok\":true}"); }
                default -> error(405, "허용되지 않는 메서드");
            };
        }
        String key = seg.get(1);
        int keyBytes = key.getBytes(StandardCharsets.UTF_8).length; // String.length()는 UTF-16 단위라 쓰지 않는다
        if (keyBytes < 1 || keyBytes > MAX_KEY_BYTES) return error(400, "키는 1~1024바이트");

        if (seg.size() == 2) {
            return switch (method) {
                case "PUT" -> {
                    byte[] value = readBody(ex);
                    String ttl = q.get("ttl");
                    if (ttl == null) call(bytes("SET"), bytes(key), value); // ttl 없이 PUT하면 기존 TTL이 지워진다
                    else call(bytes("SET"), bytes(key), value, bytes("EX"), bytes(Long.toString(ttlParam(ttl))));
                    yield json(200, "{\"ok\":true}");
                }
                case "GET" -> {
                    Object v = call("GET", key);
                    yield v == null ? error(404, "키 없음") : new Response(200, (byte[]) v, "application/octet-stream");
                }
                case "HEAD" -> (Boolean) call("EXISTS", key) ? json(200, "") : json(404, "");
                case "DELETE" -> json(200, "{\"deleted\":" + call("DEL", key) + "}");
                default -> error(405, "허용되지 않는 메서드");
            };
        }
        if (seg.size() == 3 && seg.get(2).equals("incr")) {
            if (!method.equals("POST")) return error(405, "허용되지 않는 메서드");
            return json(200, "{\"value\":" + call("INCR", key) + "}");
        }
        if (seg.size() == 3 && seg.get(2).equals("ttl")) {
            return switch (method) {
                case "GET" -> json(200, "{\"ttl\":" + call("TTL", key) + "}");
                case "PUT" -> {
                    String s = q.get("seconds");
                    if (s == null) yield error(400, "seconds가 필요하다");
                    yield (Boolean) call("EXPIRE", key, Long.toString(ttlParam(s))) ? json(200, "{\"ok\":true}") : error(404, "키 없음");
                }
                case "DELETE" -> { call("PERSIST", key); yield json(200, "{\"ok\":true}"); }
                default -> error(405, "허용되지 않는 메서드");
            };
        }
        return error(404, "없는 경로");
    }

    private Response admin(String method, String what) throws InterruptedException {
        switch (what) {
            case "save", "bgsave", "rewrite-aof" -> {
                if (!method.equals("POST")) return error(405, "허용되지 않는 메서드");
                if (what.equals("save")) {
                    call("SAVE");
                    return json(200, "{\"ok\":true}");
                }
                call(what.equals("bgsave") ? "BGSAVE" : "REWRITEAOF");
                return json(200, "{\"started\":true}");
            }
            case "stats" -> {
                if (!method.equals("GET")) return error(405, "허용되지 않는 메서드");
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) call("STATS");
                StringBuilder sb = new StringBuilder("{");
                for (var e : m.entrySet()) {
                    if (sb.length() > 1) sb.append(',');
                    Object v = e.getValue();
                    sb.append(quote(e.getKey())).append(':').append(v instanceof String s ? quote(s) : String.valueOf(v));
                }
                return json(200, sb.append('}').toString());
            }
            default -> {
                return error(404, "없는 경로");
            }
        }
    }

    // ---------------- 입력 검사 ----------------

    private boolean authorized(String header) {
        if (passwordHash == null) return true;
        if (header == null || !header.startsWith("Bearer ")) return false;
        // 해시로 길이를 32바이트로 맞춘 뒤 비교: isEqual은 길이가 다르면 바로 끝나서 길이가 드러난다
        return MessageDigest.isEqual(passwordHash, sha256(header.substring(7).getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] readBody(HttpExchange ex) throws IOException {
        String len = ex.getRequestHeaders().getFirst("Content-Length");
        if (len != null) {
            try {
                if (Long.parseLong(len.trim()) > MAX_BODY) throw new StoreException(413, "본문은 최대 1MB");
            } catch (NumberFormatException e) {
                throw new StoreException(400, "Content-Length 오류");
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY + 1); // 길이 헤더가 없는 chunked 요청도 1MB+1까지만 읽는다
            if (body.length > MAX_BODY) throw new StoreException(413, "본문은 최대 1MB");
            return body;
        }
    }

    private static long ttlParam(String s) {
        try {
            long n = Long.parseLong(s);
            if (n >= 1 && n <= MAX_TTL) return n;
        } catch (NumberFormatException ignored) {
            // 아래에서 400
        }
        throw new StoreException(400, "TTL은 1~" + MAX_TTL + " 정수");
    }

    /** raw 경로를 '/'로 먼저 나눈 뒤 조각마다 디코딩한다. 그래야 %2F가 든 키가 경로 구분자로 바뀌지 않는다. */
    static List<String> splitPath(String raw) {
        List<String> out = new ArrayList<>();
        String[] parts = raw.split("/", -1);
        for (int i = 1; i < parts.length; i++) out.add(percentDecode(parts[i]));
        return out;
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) m.put(percentDecode(kv), "");
            else m.put(percentDecode(kv.substring(0, eq)), percentDecode(kv.substring(eq + 1)));
        }
        return m;
    }

    /** URLDecoder는 '+'를 공백으로 바꾸므로 쓰지 않는다. 결과가 올바른 UTF-8이 아니면 400. */
    static String percentDecode(String s) {
        byte[] out = new byte[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) throw new StoreException(400, "잘못된 퍼센트 인코딩");
                int hi = Character.digit(s.charAt(i + 1), 16), lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) throw new StoreException(400, "잘못된 퍼센트 인코딩");
                out[n++] = (byte) (hi * 16 + lo);
                i += 2;
            } else {
                if (c > 0x7F) throw new StoreException(400, "경로에 ASCII 아닌 문자");
                out[n++] = (byte) c;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out, 0, n)).toString();
        } catch (CharacterCodingException e) {
            throw new StoreException(400, "올바른 UTF-8이 아님");
        }
    }

    // ---------------- 응답 ----------------

    private Object call(String... args) throws InterruptedException {
        return engine.call(args);
    }

    private Object call(byte[]... argv) throws InterruptedException {
        return Engine.await(engine.submit(argv));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Response json(int status, String body) {
        return new Response(status, bytes(body), "application/json");
    }

    private static Response error(int status, String msg) {
        return json(status, "{\"error\":" + quote(msg) + "}");
    }

    /** 응답 형태가 고정이라 JSON 라이브러리 없이 문자열 이스케이프 함수 하나로 만든다. */
    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
