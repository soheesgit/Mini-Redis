# Mini Redis

> 해시 테이블을 직접 구현하고, 외부 API에서 사용할 수 있는 키-값 저장소를 만드는 프로젝트입니다.
> 순수 Java(JDK 25) + JDK 내장 HTTP 서버로 만들었고, 외부 라이브러리는 테스트용 JUnit뿐입니다.

## ✅ 검증 체크리스트

- [x] **단위 테스트:** 저장, 조회, 삭제
- [x] **기능 테스트:** 예제 상품 API 응답을 Mini Redis에 저장하고 다음 요청에서 재사용
- [x] **경계 사례:** 없는 키 조회·삭제, 같은 키 덮어쓰기, 만료된 키 조회, 같은 키 동시 요청
- [x] **성능 비교:** 같은 경로에서 캐시 사용 전후 응답 시간 비교 (HIT이 캐시를 안 쓸 때보다 p50 약 26배 빠름)

테스트 57개는 모두 통과했습니다. 성능 그래프와 세부 결과는 [검증결과.md](검증결과.md)에 있습니다.

## 실행

JDK 25와 Maven이 필요합니다(`pom.xml`이 Java 25로 컴파일). PATH의 `java`가 25보다 낮으면 클래스 버전 오류가 나므로, `JAVA_HOME`을 JDK 25로 잡고 `$JAVA_HOME/bin/java`로 실행합니다. `run.sh`는 `JAVA_HOME`이 없으면 `~/tools/jdk-25.0.5+8`을 씁니다.

```bash
mvn compile           # target/classes 생성
./run.sh              # Mini Redis 서버 :8080 (데이터는 ./data)
mvn test              # 테스트 57개
```

캐시 예제를 돌리려면 서버 두 개를 더 띄웁니다.

```bash
$JAVA_HOME/bin/java -cp target/classes example.origin.OriginMain       # 느린 원본 :8081 (응답마다 50ms)
$JAVA_HOME/bin/java -cp target/classes example.cacheapi.CacheApiMain   # 상품 API :8082 (Mini Redis를 캐시로 사용)
```

## API

| 요청 | 성공 | 키가 없을 때 |
|---|---|---|
| `PUT /keys/{key}?ttl=초` (본문 = 값) | `200 {"ok":true}` | 새로 만든다 |
| `GET /keys/{key}` | `200` + 값 그대로 | `404` |
| `DELETE /keys/{key}` | `200 {"deleted":1}` | `200 {"deleted":0}` |
| `GET` / `PUT ?seconds=` / `DELETE` `/keys/{key}/ttl` | 남은 시간 조회 / 설정 / 제거 | `-2` / `404` |
| `POST /keys/{key}/incr` | `200 {"value":n}` | 0에서 시작 |

- `ttl` 없이 PUT하면 기존 TTL이 지워집니다. 만료된 키는 없는 키로 취급합니다.
- 키는 1~1024바이트, 값은 최대 1MB의 아무 바이트입니다.
- `Origin` 헤더가 붙은 요청(브라우저)은 `403`입니다. `MINIREDIS_PASSWORD`를 설정하면 `Authorization: Bearer …`가 필요합니다.

```bash
curl -X PUT --data 'hello' 'http://127.0.0.1:8080/keys/demo?ttl=10'
curl -i http://127.0.0.1:8080/keys/demo
curl -X DELETE http://127.0.0.1:8080/keys/demo
```

상품 API (`:8082`)는 응답 헤더 `X-Cache`에 처리 경로를 적습니다.

```bash
curl -i 'http://127.0.0.1:8082/products/1?cached=false'   # BYPASS: 캐시를 읽지도 쓰지도 않음
curl -i http://127.0.0.1:8082/products/1                  # MISS: 원본 조회 후 캐시에 저장
curl -i http://127.0.0.1:8082/products/1                  # HIT: 캐시에서 바로 응답
```

## 설계 요약 (과제 설계 질문에 대한 답)

| 주제 | 답 |
|---|---|
| 해시 테이블 | 체이닝 + 무작위 시드 SipHash. 키 수가 버킷 수에 닿으면 2배로 키우고, 한 번에 다 옮기지 않고 명령마다 한 버킷씩 옮깁니다(점진적 리해싱) |
| 동시성 | 데이터를 만지는 스레드는 하나(executor)입니다. 요청 스레드는 명령을 큐에 넣고 결과를 기다리므로 락 없이 경쟁이 없습니다 |
| TTL | 조회할 때 만료 키를 지우고, 100ms마다 TTL 키 20개를 무작위로 검사해 조회되지 않는 만료 키도 지웁니다. 시간은 단조 시계로 잽니다 |
| 캐시 무효화 | 원본을 먼저 갱신하고 캐시를 지웁니다. TTL ±10% 무작위, 없는 상품은 30초 캐시, 같은 키 원본 조회는 하나로 합칩니다(single-flight) |
| 데이터 보존 | 쓰기마다 AOF에 기록하고 RDB 스냅샷을 만들 수 있습니다. `kill -9` 뒤에도 응답을 보낸 쓰기는 복구됩니다 |

팀원 소희 님 구현(`sohee/redis-java`)에서 캐시 우회(BYPASS)·`X-Cache` 헤더, 측정 응답 검증, 경계 사례 테스트를 가져왔습니다. 자세한 비교는 [검증결과.md](검증결과.md)에 있습니다.

## 문서

- [기획서.md](기획서.md): 전체 설계
- [트러블슈팅.md](트러블슈팅.md): 막혔던 문제와 해결
- [study/](study/): 주제별 학습 정리
