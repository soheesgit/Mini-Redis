package miniredis.controller;

import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;
import miniredis.service.MiniRedisService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * HTTP 요청을 MiniRedisService의 저장·조회·삭제 명령으로 연결한다.
 * 컨트롤러는 입력을 검증하고, 서비스 실행 결과를 HTTP 응답으로 변환한다.
 * {@link RestController}는 메서드의 반환값을 응답 본문으로 보낸다.
 * {@link RequestMapping}의 공통 경로에서 {key}는 경로의 한 구간을 뜻한다.
 * Spring은 같은 경로로 들어온 요청을 HTTP 메서드에 따라 set, get, del에 연결한다.
 */
@RestController
@RequestMapping("/api/cache/{key}")
public class CacheController {
    private final MiniRedisService service;

    // Spring이 등록된 서비스 빈을 생성자로 주입한다. 세 메서드가 같은 저장소를 사용한다.
    public CacheController(MiniRedisService service) {
        this.service = service;
    }

    /**
     * application/json으로 받은 값을 새로 저장하거나 덮어쓴다.
     * {@link PathVariable}은 경로의 {key}를 꺼내고, {@link RequestBody}는 JSON을 SetRequest로 읽는다.
     * 본문을 읽을 수 없으면 Spring이 메서드를 호출하기 전에 400으로 응답한다.
     * 서비스 호출이 성공하면 {@link ResponseStatus}가 본문 없이 204를 반환하도록 지정한다.
     */
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void set(@PathVariable("key") String key, @RequestBody SetRequest request) {
        requireKey(key);
        // value가 없거나 JSON 문자열이 아니면 저장하기 전에 400으로 응답한다.
        // 빈 문자열은 유효한 값이다.
        if (request.value() == null || !request.value().isString()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must be a JSON string");
        }

        String value = request.value().asString();
        JsonNode ttl = request.ttlSeconds();
        // TTL을 생략하거나 null을 보내면 만료 시각 없이 저장한다. 덮어쓸 때는 기존 TTL도 지운다.
        if (ttl == null || ttl.isNull()) {
            service.set(key, value);
            return;
        }
        // 숫자 문자열과 소수는 거부한다. Duration.ofSeconds에 전달할 수 있는 양의 long 정수만 받는다.
        if (!ttl.isIntegralNumber() || !ttl.canConvertToLong() || ttl.asLong() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ttlSeconds must be a positive integer");
        }
        try {
            // 서비스가 저장 시점의 현재 시각에 이 기간을 더해 만료 시각을 계산한다.
            service.set(key, value, Duration.ofSeconds(ttl.asLong()));
        } catch (IllegalArgumentException exception) {
            // 초 단위 TTL이 long 범위에 들어가도 계산한 만료 시각은 Instant 범위를 넘을 수 있다.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /** 유효한 값은 200과 JSON으로 반환한다. 없거나 만료된 키는 404로 응답한다. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public GetResponse get(@PathVariable("key") String key, HttpServletResponse response) {
        // 브라우저나 프록시가 이 응답을 캐시에 저장하지 않도록 요청한다.
        // 그래야 다음 GET도 서버에 도달해 삭제나 만료를 반영한다.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        requireKey(key);
        // 서비스가 만료된 키를 삭제하고 null을 반환하면 컨트롤러는 이를 404로 변환한다.
        String value = service.get(key);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key not found");
        }
        // Spring이 응답 객체를 {"value":"..."} 형태의 JSON으로 직렬화한다.
        return new GetResponse(value);
    }

    /** 서비스가 유효한 키를 삭제하면 204, 없거나 만료됐으면 404를 반환한다. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void del(@PathVariable("key") String key) {
        requireKey(key);
        // 서비스는 유효한 키가 실제로 삭제된 경우에만 true를 반환한다.
        // 같은 키를 한 번 더 삭제하면 서비스가 false를 반환하므로 404로 응답한다.
        if (!service.del(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key not found");
        }
    }

    // 경로에서 디코딩한 키가 공백뿐이면 서비스 호출 전에 400으로 응답한다.
    private static void requireKey(String key) {
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "key must not be blank");
        }
    }

    /**
     * PUT 요청 본문을 담는다. JsonNode는 JSON의 원래 타입을 보존한다.
     * 컨트롤러는 이 타입을 검사해 숫자가 문자열로, 소수가 정수로 자동 변환되는 것을 막는다.
     */
    public record SetRequest(JsonNode value, JsonNode ttlSeconds) {}

    /** 조회 성공 시 반환할 JSON 본문이다. */
    public record GetResponse(String value) {}
}
