# Mini DBMS

파일 기반 미니 SQL 처리기 (Java 25, Maven, 런타임 의존성 0개). 설계: [study/미니DBMS-설계.md](../study/미니DBMS-설계.md)

## 빌드·테스트

```bash
cd mini-dbms
export JAVA_HOME=~/tools/jdk-25.0.5+8/Contents/Home   # 맥 번들이 아니면 ~/tools/jdk-25.0.5+8
~/tools/apache-maven-3.9.16/bin/mvn -q test                          # 전체
~/tools/apache-maven-3.9.16/bin/mvn -q -o test -Dtest='WalTest,TableTest'   # 일부 (오프라인)
```

- 테스트는 클래스·메서드를 병렬로 돌린다 (`src/test/resources/junit-platform.properties`). 그래서 파일을 쓰는 테스트는 저마다 `@TempDir`를 쓰고, 전역 상태(`System.setOut`, 기본 시간대 등)를 바꾸지 않는다. 실패 주입(`Faults`)도 인스턴스마다 따로다.
- `CrashTest`는 자식 JVM을 띄워 UPDATE 도중 강제 종료하고, 다시 열어 문장 단위로 전부-아니면-없음인지 확인한다.

## 실행

```bash
~/tools/apache-maven-3.9.16/bin/mvn -q compile
java -cp target/classes minidb.cli.Main [--data-dir DIR] [--fsync=off] [file.sql]
```

`--data-dir` 기본값은 `./data`. 파일을 안 주면 표준 입력을 읽는다.

```bash
$ cat demo.sql
CREATE TABLE users (name VARCHAR(20) NOT NULL, age INT);
INSERT INTO users VALUES ('kim', 30), ('lee', NULL);
SELECT * FROM users WHERE age IS NOT NULL ORDER BY age DESC LIMIT 5;
$ java -cp target/classes minidb.cli.Main demo.sql
OK
OK, 2 rows affected
id	name	age
1	kim	30
(1 rows)
```

전체를 먼저 파싱하므로 문법 오류가 있으면 아무것도 실행하지 않고 `ERROR 줄:칸: 메시지`를 stderr에 찍고 종료 코드 1. 실행 중 에러는 그 문장에서 멈추고(앞 문장은 이미 저장됨) 종료 코드 1.

## 지원하는 SQL

- `CREATE TABLE t (칸 타입 [NOT NULL], …)`: INT, BIGINT, DOUBLE, DECIMAL(p,s), BOOLEAN, VARCHAR(n), DATE, DATETIME, TIMESTAMP. `id`(INT, 자동 증가)는 맨 앞에 자동으로 붙는다.
- `INSERT INTO t VALUES (…), (…)`: 값은 id를 뺀 모든 칸 순서대로. 여러 행이면 전부 들어가거나 전부 안 들어간다.
- `SELECT 칸, … | * FROM t [WHERE …] [ORDER BY 칸 [DESC], …] [LIMIT n]`
- `UPDATE t SET 칸 = 값, … [WHERE …]`, `DELETE FROM t [WHERE …]`
- WHERE: `= <> != < <= > >=`, `AND OR NOT`, `BETWEEN`, `IN`, `LIKE`(`%` `_`, 이스케이프 없음), `IS [NOT] NULL`. NULL은 3값 논리(`NULL = NULL`은 참이 아님).

## 저장과 복구, `--fsync=off`

테이블 하나 = `<이름>.db`(8KB 페이지) + `<이름>.wal`(쓰기 전 로그). 문장 하나가 커밋 하나이고, 로그에 먼저 쓴 뒤 데이터 파일에 쓰므로 어디서 끊겨도 다시 열 때 로그로 복구된다.

`--fsync=off`는 커밋마다의 로그 fsync만 끈다. 프로세스가 죽어도(kill, 예외) OS 캐시에 남아 있어 문장 단위로 안전하지만, **정전·OS 크래시에서는 최근 커밋을 잃을 수 있다.** 체크포인트(종료 시, 로그 16MB 초과 시)와 복구의 fsync는 항상 한다.

## Redis SELECT 캐시 (team-phase1)

`jiho/dbms-phase1`의 SQL·타입·페이지·WAL 엔진에 `sohee/dbms`의 HTTP 캐시 연동을 이식했다.
소희의 별도 `minidbms` SQL/저장 엔진은 포함하지 않는다. Mini Redis 서버 코드는 두 브랜치가 동일하다.

```bash
# 서버가 이미 실행 중일 때
MINIDBMS_REDIS=http://127.0.0.1:8090 java -cp target/classes minidb.cli.Main --data-dir ./data examples/read-users.sql

# 저장소 루트에서 새 데이터 폴더로 데모 실행 (스크립트가 Redis 시작·종료)
DBMS_DATA=/tmp/team-phase1-demo ./scripts/run-dbms-with-redis.sh \
  mini-dbms/examples/phase1.sql mini-dbms/examples/read-users.sql mini-dbms/examples/mutations.sql
```

- JDK 25 이상과 Maven을 사용한다. 스크립트는 `JAVA_HOME`, `MVN`(실행 파일 경로), `REDIS_PORT`(기본 8090), `DBMS_DATA`, `REDIS_DATA`를 받는다.
- `MINIDBMS_REDIS`가 없으면 캐시를 사용하지 않는다. 인증을 켜려면 서버와 DBMS에 같은 `MINIREDIS_PASSWORD`를 설정한다.
- SQL 결과와 종료 코드는 유지하며, 캐시 통계는 stderr에 `cache: hits=N misses=N`으로 출력한다.
- TTL은 60초. 저장할 결과가 1MiB를 넘으면 해당 결과 저장만 생략하고 다음 쿼리의 캐시는 계속 사용한다.
- 연결·HTTP·손상된 캐시 응답 오류 시 해당 DB 인스턴스의 캐시를 끄고 DB에서 조회한다. SQL/저장소 오류는 그대로 전달한다.
- `Result`의 NULL, INT, BIGINT, DOUBLE, DECIMAL(scale 포함), BOOLEAN, VARCHAR, DATE, DATETIME, TIMESTAMP를 보존한다. 이전 캐시와 섞이지 않도록 `dbms:v2` 키를 쓴다.

### 캐시 일관성과 비용

키는 실제 데이터 경로·시간대, 테이블 이름, 스키마와 데이터 페이지의 SHA-256, 조회 AST로 구성한다.
AST에는 SELECT 칼럼, 모든 WHERE 식, ORDER BY 방향과 순서, LIMIT를 포함하고 소스 위치는 제외한다.
테이블을 연 뒤 WAL 복구를 마친 내용을 해시하므로 캐시 없이 실행한 UPDATE/DELETE, 같은 크기·수정시각의 변경,
프로세스 재시작과 복구도 구분한다. 이전 키는 TTL로 만료된다. 같은 DB를 **여러 프로세스에서 동시에 여는 것은 지원하지 않는다**.
프로세스를 순차 실행할 때는 캐시를 공유한다.

정확성을 위해 캐시 hit에서도 테이블의 전체 페이지를 읽어 해시한다. hit는 행 디코딩·WHERE 평가·정렬을 생략하지만,
페이지 I/O를 없애지는 않으며 작은 조회는 HTTP 왕복 때문에 느릴 수 있다. 성능 향상을 측정했다고 주장하지 않는다.
영속 커밋 버전 도입은 WAL/파일 형식 변경을 요구하므로 이번 이식 범위에서 제외했다.
기존 `minidbms`의 데이터 파일과 새 `minidb`의 8KB 페이지 형식은 호환되지 않는다. 새 데이터 폴더를 사용한다.

### 검증

```bash
mvn -f mini-dbms/pom.xml test
mvn -f mini-redis/pom.xml test
```

기존 엔진·크래시 복구 테스트에 캐시 hit/miss, 프로세스 재오픈, 캐시 없는 변경, WAL 복구,
조회 조건·DB 격리, 전체 SQL 타입, 손상 응답·서버 장애·저장 실패, 1MiB 초과 처리를 추가했다.
캐시 통합 테스트는 HTTP 서버만 대역으로 두고 Parser/Database/Table/WAL/코덱을 실제로 연결한다.
실제 서버 연결은 위 데모 스크립트로 재현할 수 있다. 예제 CREATE TABLE은 새 폴더에서 한 번만 실행한다.
