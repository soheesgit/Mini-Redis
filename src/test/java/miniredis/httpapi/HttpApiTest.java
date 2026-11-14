package miniredis.httpapi;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import miniredis.engine.Engine;
import miniredis.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 4단계: 엔드포인트별 정상·에러 상태 코드, 인증, Origin 차단, 크기 제한, 키 인코딩 엣지 케이스. */
class HttpApiTest {
    @TempDir
    Path dir;

    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** 서버를 띄우고 body를 실행한 뒤 정리한다. 포트는 OS가 고른다 (0). */
    interface Body { void run(String base, int port) throws Exception; }

    void withServer(String password, Body body) throws Exception {
        try (Engine e = Engine.start(new Engine.Config(dir, 0, Store.Policy.NOEVICTION))) {
            HttpServer s = HttpApi.start(new InetSocketAddress("127.0.0.1", 0), e, password);
            try {
                int port = s.getAddress().getPort();
                body.run("http://127.0.0.1:" + port, port);
            } finally {
                s.stop(0);
            }
        }
    }

    static HttpResponse<byte[]> send(String method, String url, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) b.headers(headers);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static int status(String method, String url) throws Exception {
        return send(method, url, null).statusCode();
    }

    static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    @Test
    void endpoints() throws Exception {
        withServer(null, (base, port) -> {
            assertEquals("{\"pong\":true}", text(send("GET", base + "/ping", null)));
            assertEquals(200, send("PUT", base + "/keys/a?ttl=100", "1".getBytes()).statusCode());
            assertEquals("1", text(send("GET", base + "/keys/a", null)));
            assertEquals(200, status("HEAD", base + "/keys/a"));
            assertEquals(404, status("HEAD", base + "/keys/없음".replace("없음", "%EC%97%86%EC%9D%8C")));
            assertEquals("{\"ttl\":100}", text(send("GET", base + "/keys/a/ttl", null)));
            assertEquals(200, status("DELETE", base + "/keys/a/ttl"));
            assertEquals("{\"ttl\":-1}", text(send("GET", base + "/keys/a/ttl", null)));
            assertEquals(200, send("PUT", base + "/keys/a/ttl?seconds=5", null).statusCode());
            assertEquals(404, send("PUT", base + "/keys/nope/ttl?seconds=5", null).statusCode());
            assertEquals("{\"ttl\":-2}", text(send("GET", base + "/keys/nope/ttl", null)));
            assertEquals("{\"value\":1}", text(send("POST", base + "/keys/c/incr", null)));
            assertEquals("{\"value\":2}", text(send("POST", base + "/keys/c/incr", null)));
            assertEquals("{\"keys\":[\"c\"]}", text(send("GET", base + "/keys?pattern=c*", null)));
            assertEquals("{\"deleted\":1}", text(send("DELETE", base + "/keys/c", null)));
            assertEquals("{\"deleted\":0}", text(send("DELETE", base + "/keys/c", null)));
            assertEquals(404, status("GET", base + "/keys/c"));
            assertEquals(200, status("DELETE", base + "/keys"));
            assertEquals("{\"keys\":[]}", text(send("GET", base + "/keys", null)));
            assertEquals(404, status("GET", base + "/nothing"));
            assertEquals(405, status("POST", base + "/ping"));
            assertEquals(200, send("POST", base + "/admin/save", null).statusCode());
            assertEquals("{\"started\":true}", text(send("POST", base + "/admin/bgsave", null)));
            assertTrue(text(send("GET", base + "/admin/stats", null)).contains("\"keys\":"));
        });
    }

    @Test
    void invalidInputs() throws Exception {
        withServer(null, (base, port) -> {
            for (String ttl : new String[] {"0", "-1", "abc", "315360001"}) {
                assertEquals(400, send("PUT", base + "/keys/k?ttl=" + ttl, "v".getBytes()).statusCode(), "ttl=" + ttl);
            }
            assertEquals(200, send("PUT", base + "/keys/k?ttl=315360000", "v".getBytes()).statusCode());
            send("PUT", base + "/keys/max", Long.toString(Long.MAX_VALUE).getBytes());
            assertEquals(400, status("POST", base + "/keys/max/incr")); // 64비트 넘침
            send("PUT", base + "/keys/s", "abc".getBytes());
            assertEquals(400, status("POST", base + "/keys/s/incr"));
            // 잘못된 퍼센트 인코딩 (HttpClient는 이런 URL을 만들지 못하게 막으므로 소켓으로 보낸다)
            assertEquals(400, raw(port, "GET /keys/%zz HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n", null));
            assertEquals(400, status("GET", base + "/keys/%ff"));       // 올바른 UTF-8 아님
            assertEquals(400, status("GET", base + "/keys/"));          // 빈 키
        });
    }

    @Test
    void valuesAndKeyEncoding() throws Exception {
        withServer(null, (base, port) -> {
            byte[] bin = {0, 1, '\n', (byte) 0xff, '\r'};
            send("PUT", base + "/keys/bin", bin);
            assertArrayEquals(bin, send("GET", base + "/keys/bin", null).body());
            send("PUT", base + "/keys/empty", new byte[0]);
            HttpResponse<byte[]> r = send("GET", base + "/keys/empty", null);
            assertEquals(200, r.statusCode());
            assertEquals(0, r.body().length);

            // %2F는 경로 구분자가 아니라 키의 일부, '+'는 공백이 아니라 '+'
            send("PUT", base + "/keys/a%2Fb+c", "v".getBytes());
            assertEquals("v", text(send("GET", base + "/keys/a%2Fb+c", null)));
            // 키 이름이 "x/ttl"이어도 /ttl 경로로 가지 않는다
            send("PUT", base + "/keys/x%2Fttl", "t".getBytes());
            assertEquals("t", text(send("GET", base + "/keys/x%2Fttl", null)));
            assertTrue(text(send("GET", base + "/keys", null)).contains("\"a/b+c\""));

            // 한글 1글자 = UTF-8 3바이트. 341글자 = 1023바이트
            String k1024 = "가".repeat(341) + "a", k1025 = "가".repeat(341) + "aa";
            assertEquals(200, send("PUT", base + "/keys/" + enc(k1024), "v".getBytes()).statusCode());
            assertEquals(400, send("PUT", base + "/keys/" + enc(k1025), "v".getBytes()).statusCode());
        });
    }

    static String enc(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) sb.append(String.format("%%%02X", b & 0xff));
        return sb.toString();
    }

    @Test
    void bodySizeLimit() throws Exception {
        withServer(null, (base, port) -> {
            byte[] exact = new byte[HttpApi.MAX_BODY];
            assertEquals(200, send("PUT", base + "/keys/big", exact).statusCode());
            assertEquals(HttpApi.MAX_BODY, send("GET", base + "/keys/big", null).body().length);
            // 1MB+1: 길이 헤더만 보고 본문을 읽기 전에 413
            assertEquals(413, raw(port, "PUT /keys/big HTTP/1.1\r\nHost: x\r\nContent-Length: " + (HttpApi.MAX_BODY + 1)
                + "\r\nConnection: close\r\n\r\n", null));
            // 길이 헤더 없는 chunked 본문도 1MB를 넘으면 413
            byte[] chunk = new byte[HttpApi.MAX_BODY + 1];
            assertEquals(413, raw(port, "PUT /keys/big HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n" + Integer.toHexString(chunk.length) + "\r\n", chunk));
        });
    }

    /** 소켓으로 직접 보내고 상태 코드만 읽는다. HttpClient는 Content-Length를 마음대로 못 바꾸게 막아서 쓴다. */
    static int raw(int port, String head, byte[] chunkBody) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            if (chunkBody != null) {
                out.write(chunkBody);
                out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
            InputStream in = s.getInputStream();
            StringBuilder line = new StringBuilder();
            for (int c; (c = in.read()) != -1 && c != '\n'; ) line.append((char) c);
            return Integer.parseInt(line.toString().split(" ")[1]);
        }
    }

    @Test
    void originAndAuth() throws Exception {
        withServer("s3cret", (base, port) -> {
            assertEquals(401, status("GET", base + "/ping"));
            assertEquals(401, send("GET", base + "/ping", null, "Authorization", "Bearer wrong").statusCode());
            assertEquals(401, send("GET", base + "/ping", null, "Authorization", "s3cret").statusCode());
            assertEquals(200, send("GET", base + "/ping", null, "Authorization", "Bearer s3cret").statusCode());
            // 인증이 맞아도 브라우저가 보낸 요청(Origin 있음)은 거부
            assertEquals(403, send("GET", base + "/ping", null, "Authorization", "Bearer s3cret",
                "Origin", "http://evil.example").statusCode());
        });
        withServer(null, (base, port) -> // 인증이 꺼져 있어도 Origin은 막는다
            assertEquals(403, send("GET", base + "/ping", null, "Origin", "http://evil.example").statusCode()));
    }

    @Test
    void bgsaveTwiceIs409() throws Exception {
        withServer(null, (base, port) -> {
            // 큰 값을 많이 넣어 BGSAVE가 여러 틱(틱마다 2ms)에 걸치게 한다
            for (int i = 0; i < 300; i++) send("PUT", base + "/keys/k" + i, new byte[30_000]);
            assertEquals(200, status("POST", base + "/admin/bgsave"));
            assertEquals(409, status("POST", base + "/admin/bgsave"));
            assertEquals(409, status("POST", base + "/admin/rewrite-aof"));
        });
    }

    @Test
    void statsJsonHasNoUnknownTypes() {
        assertEquals("\"a\\\"b\\n\\u0001\"", HttpApi.quote("a\"b\n\u0001"));
        assertEquals(Map.of("a", "b c"), HttpApi.query("a=b%20c"));
        assertEquals("+", HttpApi.query("p=+").get("p"));
    }
}
