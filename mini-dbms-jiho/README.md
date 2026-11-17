# mini-dbms-jiho

파일 기반 미니 SQL 처리기 (Java 25, Maven, 런타임 의존성 0개). 설계: [study/미니DBMS-설계.md](../study/미니DBMS-설계.md)

## 빌드·테스트

```bash
cd mini-dbms-jiho
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
