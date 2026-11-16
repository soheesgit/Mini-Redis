# Mini DBMS

Java로 구현한 파일 기반 미니 DBMS입니다. Phase 1 CLI는 UTF-8 SQL 파일을 읽어 `INSERT`와 `SELECT`를 실행합니다. 저장소 경로가 같으면 CLI를 종료한 뒤에도 같은 행을 조회할 수 있습니다.

## 실행

JDK 21 이상이 필요합니다. 저장소 루트에서 다음 명령을 실행합니다.

```sh
cd mini-dbms
mkdir -p out/main out/test
find src/main/java -name '*.java' -print > out/main-sources.txt
javac --release 21 -encoding UTF-8 -d out/main @out/main-sources.txt

dbms_demo_dir=$(mktemp -d)
java -cp out/main minidbms.cli.Main --data-dir "$dbms_demo_dir" examples/phase1.sql
printf '%s\n' 'SELECT id, name FROM users WHERE id = 2;' > out/read.sql
java -cp out/main minidbms.cli.Main --data-dir "$dbms_demo_dir" out/read.sql
```

첫 프로세스가 두 행을 저장합니다. 두 번째 프로세스는 같은 저장 경로에서 `id=2`인 `O'Brien`을 조회합니다. `--data-dir`를 생략하면 현재 디렉터리의 `data/`를 사용합니다. 각 테이블은 `users.tbl`과 같이 별도 파일에 저장됩니다.

## 지원하는 SQL

- `INSERT INTO users (name, email) VALUES ('소희', 'sohee@example.com');`에서 `id`는 1부터 자동으로 증가합니다. `name`과 `email`은 필수이며, 두 열의 순서는 바꿀 수 있습니다.
- `SELECT * FROM users;`는 전체 열을 출력합니다. `SELECT id, name FROM users WHERE email = 'sohee@example.com';`처럼 조회 열과 동등 조건 하나를 지정할 수 있습니다. `id`는 정수, `name`과 `email`은 문자열과 비교합니다.
- 문장 사이는 세미콜론으로 구분합니다. 키워드와 식별자는 대소문자를 구분하지 않으며, `--` 줄 주석과 문자열 안의 `''` 이스케이프를 지원합니다.
- `SELECT`는 열 이름과 행을 탭으로 구분해 출력합니다. 스크립트 전체의 구문 검사가 끝나면 문장을 순서대로 실행합니다. 실행 도중 오류가 나면 그 전에 성공한 삽입은 파일에 남습니다. Phase 1은 데이터 디렉터리 하나를 CLI 프로세스 하나가 사용하는 범위입니다.

## 테스트

위에서 `cd mini-dbms`와 메인 소스 컴파일을 마친 후 실행합니다.

```sh
find src/test/java -name '*.java' -print > out/test-sources.txt
javac --release 21 -encoding UTF-8 -cp out/main -d out/test @out/test-sources.txt
java -cp out/main:out/test minidbms.cli.MainTest
java -cp out/main:out/test minidbms.sql.SqlParserTest
java -cp out/main:out/test minidbms.cli.Phase1IntegrationTest
```

통합 테스트는 임시 데이터를 사용합니다. 별도 JVM에서 삽입, 조회, 재삽입을 실행해 기존 행과 다음 ID가 유지되는지 확인합니다. 빈 테이블, 스키마 오류, 불완전한 레코드도 검증합니다.

## Mini Redis 캐시

`MINIDBMS_REDIS=http://127.0.0.1:8080`처럼 Mini Redis 주소를 주면 `SELECT` 결과를 Redis에 캐시합니다. `INSERT`는 테이블 버전을 올려 옛 결과를 무효화합니다. 캐시 통계는 stderr에 `cache: hits=.. misses=..`로 출력됩니다. Redis가 응답하지 않으면 캐시를 끄고 파일에서 읽습니다. 두 서버를 함께 띄우는 방법은 루트의 `scripts/run-dbms-with-redis.sh`에 있습니다.

```sh
java -cp out/main:out/test minidbms.engine.QueryCacheTest
```

캐시 테스트는 기본으로 가짜 Redis 서버를 씁니다. `MINIDBMS_TEST_REDIS`에 실제 Mini Redis 주소를 주면 그 서버로 검증합니다.

## 구현 단계

| 단계 | 결과물 |
| --- | --- |
| 1 | SQL 파일 입력, `INSERT`와 `SELECT` 실행, 테이블별 파일 저장 |
| 2 | 메모리 B+ 트리, 100만 건 이상 삽입, 인덱스와 선형 탐색 성능 비교 |
| 3 | 기존 엔진을 재사용하는 HTTP API, 고정 크기 스레드 풀, 동시 요청 검증 |

저장 형식과 단계별 검증 기준은 [구현 계획](PLAN.md)에 있습니다.
