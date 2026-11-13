package miniredis.controller;

import java.io.IOException;
import java.time.Duration;

import miniredis.service.MiniRedisService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 편의점 예제 카탈로그를 조회하고 상품별 JSON 응답을 Mini-Redis에 캐싱한다. */
@RestController
@RequestMapping(value = "/api/store/products", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConvenienceStoreController {
    private static final String CACHE_PREFIX = "store:product:";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final ClassPathResource origin = new ClassPathResource("demo/store-products.json");
    private final MiniRedisService cache;
    private final JsonMapper json;

    public ConvenienceStoreController(MiniRedisService cache, JsonMapper json) {
        this.cache = cache;
        this.json = json;
    }

    @GetMapping
    public ResponseEntity<JsonNode> catalog() throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(readCatalog());
    }

    @GetMapping("/{code}")
    public ResponseEntity<String> get(@PathVariable("code") String code,
            @RequestParam(name = "cached", defaultValue = "true") boolean cached)
            throws IOException {
        if (!cached) {
            return respond(findProduct(code), "BYPASS");
        }

        // 판매 등록은 sale: 접두어를 쓰므로 카탈로그 캐시와 독립적으로 만료된다.
        String cacheKey = CACHE_PREFIX + code;
        String body = cache.get(cacheKey);
        if (body != null) {
            return respond(body, "HIT");
        }

        body = findProduct(code);
        cache.set(cacheKey, body, CACHE_TTL);
        return respond(body, "MISS");
    }

    private JsonNode readCatalog() throws IOException {
        try (var input = origin.getInputStream()) {
            return json.readTree(input);
        }
    }

    private String findProduct(String code) throws IOException {
        for (JsonNode product : readCatalog()) {
            if (code.equals(product.get("code").asString())) {
                return json.writeValueAsString(product);
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "product not found");
    }

    private static ResponseEntity<String> respond(String body, String cacheStatus) {
        // 클라이언트가 매 요청을 서버로 보내야 Mini-Redis의 HIT과 MISS를 관찰할 수 있다.
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Cache", cacheStatus)
                .body(body);
    }
}
