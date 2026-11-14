package example.origin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 느린 원본 API (상품 조회). 응답마다 50ms 지연. 캐시 효과를 비교하는 기준선이다.
 * GET /products/{id}   id가 "missing"으로 시작하면 404
 * PUT /products/{id}   본문으로 상품 갱신
 * GET /stats           {"calls":원본 조회 횟수}
 */
public final class OriginMain {
    static final long DELAY_MS = 50;

    public static void main(String[] args) throws IOException {
        int port = args.length >= 2 && args[0].equals("--port") ? Integer.parseInt(args[1]) : 8081;
        Map<String, byte[]> products = new ConcurrentHashMap<>();
        AtomicLong calls = new AtomicLong();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", ex -> {
            try (ex) {
                String path = ex.getRequestURI().getPath();
                String method = ex.getRequestMethod();
                if (path.equals("/stats")) {
                    send(ex, 200, "{\"calls\":" + calls.get() + "}");
                } else if (path.startsWith("/products/") && method.equals("GET")) {
                    calls.incrementAndGet();
                    sleep(DELAY_MS); // 느린 DB 조회 흉내
                    String id = path.substring("/products/".length());
                    if (id.startsWith("missing")) {
                        send(ex, 404, "{\"error\":\"not found\"}");
                    } else {
                        byte[] p = products.get(id);
                        send(ex, 200, p != null ? new String(p, StandardCharsets.UTF_8)
                            : "{\"id\":\"" + id + "\",\"name\":\"product-" + id + "\",\"price\":1000}");
                    }
                } else if (path.startsWith("/products/") && method.equals("PUT")) {
                    sleep(DELAY_MS);
                    products.put(path.substring("/products/".length()), ex.getRequestBody().readAllBytes());
                    send(ex, 200, "{\"ok\":true}");
                } else {
                    send(ex, 404, "{\"error\":\"no route\"}");
                }
            }
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("origin listening on 127.0.0.1:" + port);
    }

    static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
