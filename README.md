# Mini Redis

> 해시 테이블을 직접 구현하고, 외부 API에서 사용할 수 있는 키-값 저장소를 만드는 프로젝트입니다.

## 🎯 과제 목표

- 해시 테이블을 활용해 데이터를 저장하고, 조회하고, 삭제할 수 있는 키-값 저장소를 구현합니다.
- 외부 애플리케이션에서 이 저장소를 사용할 수 있도록 API를 제공합니다.
- 언어와 프레임워크는 자유롭게 선택합니다. AI 등의 도구를 활용해 동작하는 결과물을 만드는 것을 우선하되, 해시 테이블을 어떻게 설계하고 구현했는지, 주요 함수가 어떻게 동작하는지 설명할 수 있어야 합니다.

## 🧩 설계할 때 살펴볼 점

| 주제 | 설계 질문 |
| --- | --- |
| 동시성 | 여러 요청이 같은 키를 동시에 읽거나 수정할 때 데이터 일관성을 어떻게 지킬까? |
| 만료 시간(TTL) | 만료된 키를 조회하면 무엇을 반환하고, 만료된 데이터는 언제 정리할까? |
| 외부 API | 다른 애플리케이션에서 저장·조회·삭제를 쉽게 호출하려면 어떤 인터페이스가 좋을까? |
| 캐시 무효화 | 원본 데이터가 바뀌었을 때 사용자가 기존 캐시를 어떻게 무효화할 수 있을까? |
| 데이터 보존 (선택) | 서버가 비정상 종료된 뒤에도 데이터를 최대한 복구하려면 어떻게 보관해야 할까? |

## ✅ 검증 체크리스트

- [ ] **단위 테스트:** 저장, 조회, 삭제가 정상적으로 동작하는지 확인합니다.
- [ ] **기능 테스트:** 간단한 예제 API를 만들고, 응답을 Mini Redis에 저장한 뒤 다음 요청에서 재사용할 수 있는지 확인합니다.
- [ ] **경계 사례:** 없는 키 조회·삭제, 같은 키 덮어쓰기, 만료된 키 조회, 같은 키에 대한 동시 요청을 확인합니다.
- [ ] **성능 비교:** 같은 조건에서 예제 API의 캐시 사용 전후 응답 시간을 비교합니다.

## 개발 환경

JDK 21을 선택한 상태에서 Gradle Wrapper로 테스트 작업을 실행합니다.

```sh
./gradlew test
```

Java 소스는 `src/main/java`, JUnit 5 테스트는 `src/test/java`에 둡니다. Stage 1의 해시 테이블과 테스트는 각 경로의 `miniredis/storage` 패키지에 작성합니다.

## REST API 실행

JDK 21 환경에서 서버를 실행합니다. 기본 포트는 8080입니다.

```sh
./gradlew bootRun
```

| 요청 | 성공 응답 | 키가 없거나 만료된 경우 |
| --- | --- | --- |
| `PUT /api/cache/{key}` | `204 No Content` | 새 키를 생성합니다. |
| `GET /api/cache/{key}` | `200 OK`와 JSON 값 | `404 Not Found` |
| `DELETE /api/cache/{key}` | `204 No Content` | `404 Not Found` |

`PUT`은 JSON 문자열 `value`를 받습니다. 빈 문자열도 저장할 수 있습니다. `ttlSeconds`는 선택 항목이며, 지정할 때는 양의 정수를 사용합니다. 생략하거나 `null`을 전달하면 만료 시각 없이 저장하며 덮어쓰기 시 기존 TTL도 지웁니다. 잘못된 JSON, 공백뿐인 키, 유효하지 않은 값 또는 TTL에는 `400 Bad Request`와 `application/problem+json` 응답을 반환합니다.

```sh
curl -i -X PUT http://localhost:8080/api/cache/demo \
  -H 'Content-Type: application/json' -d '{"value":"hello"}'
curl -i http://localhost:8080/api/cache/demo
curl -i -X DELETE http://localhost:8080/api/cache/demo
curl -i http://localhost:8080/api/cache/demo

curl -i -X PUT http://localhost:8080/api/cache/temporary \
  -H 'Content-Type: application/json' -d '{"value":"short-lived","ttlSeconds":2}'
sleep 3
curl -i http://localhost:8080/api/cache/temporary
```
