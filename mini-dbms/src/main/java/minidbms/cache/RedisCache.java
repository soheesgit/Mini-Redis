package minidbms.cache;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mini Redis 서버(../mini-redis)의 HTTP API를 부르는 클라이언트.
 * 두 프로젝트는 소스로 서로를 참조하지 않고 이 HTTP 호출로만 연결된다.
 *
 * GET    /keys/{key}            값 조회 (없으면 404)
 * PUT    /keys/{key}?ttl=초     값 저장
 * POST   /keys/{key}/incr       정수 1 증가, {"value":n}
 *
 * 연결 실패나 예상 밖 응답은 IOException으로 던진다. 캐시를 끌지는 부르는 쪽이 정한다.
 */
public final class RedisCache {
    private static final Pattern INCR_VALUE = Pattern.compile("\"value\":(-?\\d+)");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(1)) // Redis가 꺼져 있으면 오래 기다리지 않고 파일로 돌아간다
            .build();
    private final String baseUrl;
    private final String authorization;

    public RedisCache(String baseUrl, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorization = password == null || password.isEmpty() ? null : "Bearer " + password;
    }

    /** MINIDBMS_REDIS가 없으면 null (캐시 꺼짐). 비밀번호는 Mini Redis와 같은 MINIREDIS_PASSWORD를 쓴다. */
    public static RedisCache fromEnvironment() {
        String url = System.getenv("MINIDBMS_REDIS");
        return url == null || url.isBlank() ? null : new RedisCache(url, System.getenv("MINIREDIS_PASSWORD"));
    }

    /** 없으면 null */
    public byte[] get(String key) throws IOException {
        HttpResponse<byte[]> response = send(request(key, "").GET());
        if (response.statusCode() == 404) {
            return null;
        }
        expect200(response);
        return response.body();
    }

    public void set(String key, byte[] value, long ttlSeconds) throws IOException {
        expect200(send(request(key, "?ttl=" + ttlSeconds).PUT(HttpRequest.BodyPublishers.ofByteArray(value))));
    }

    public long incr(String key) throws IOException {
        HttpResponse<byte[]> response = send(request(key, "/incr").POST(HttpRequest.BodyPublishers.noBody()));
        expect200(response);
        Matcher matcher = INCR_VALUE.matcher(new String(response.body(), StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new IOException("Unexpected INCR response from Mini Redis.");
        }
        return Long.parseLong(matcher.group(1));
    }

    private HttpRequest.Builder request(String key, String suffix) {
        String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/keys/" + encoded + suffix))
                .timeout(Duration.ofSeconds(2));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder builder) throws IOException {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Mini Redis.", exception);
        }
    }

    private static void expect200(HttpResponse<byte[]> response) throws IOException {
        if (response.statusCode() != 200) {
            throw new IOException("Mini Redis returned HTTP " + response.statusCode() + ".");
        }
    }
}
