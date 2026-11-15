# Mini DBMS

Java로 구현하는 파일 기반 미니 DBMS입니다. SQL 파일을 실행하는 CLI에서 시작해 ID 인덱스와 병렬 HTTP 서버를 순서대로 추가합니다. 각 단계가 같은 SQL 처리기를 사용합니다.

현재 CLI가 UTF-8 SQL 파일을 읽습니다. SQL 파일을 정상적으로 읽으면 Phase 1 구현 대기 메시지를 표시하고 종료 코드 `1`을 반환합니다.

## 실행

JDK 21 이상이 필요합니다. 저장소 루트에서 다음 명령을 실행합니다.

```sh
cd mini-dbms
mkdir -p out/main out/test
find src/main/java -name '*.java' -print > out/main-sources.txt
javac --release 21 -encoding UTF-8 -d out/main @out/main-sources.txt

java -cp out/main minidbms.cli.Main --help
java -cp out/main minidbms.cli.Main examples/phase1.sql
```

## 테스트

위에서 `cd mini-dbms`와 메인 소스 컴파일을 마친 후 실행합니다.

```sh
find src/test/java -name '*.java' -print > out/test-sources.txt
javac --release 21 -encoding UTF-8 -cp out/main -d out/test @out/test-sources.txt
java -cp out/main:out/test minidbms.cli.MainTest
```

## 구현 단계

| 단계 | 결과물 |
| --- | --- |
| 1 | SQL 파일 입력, `INSERT`와 `SELECT` 파싱·실행, 테이블별 파일 저장 |
| 2 | 자동 ID를 사용하는 메모리 B+ 트리, 100만 건 이상 삽입, 인덱스와 선형 탐색 성능 비교 |
| 3 | 기존 엔진을 재사용하는 HTTP API, 고정 크기 스레드 풀, 동시 요청 검증 |

지원할 SQL 문법, 저장 구조, 단계별 검증 기준은 [구현 계획](PLAN.md)에 있습니다. [예제 SQL](examples/phase1.sql)은 Phase 1 완료 후 실행할 입력입니다.
