# Mini Redis + Mini DBMS

크래프톤 정글 팀 프로젝트입니다. 직접 만든 키-값 저장소(Mini Redis)와 파일 기반 SQL 처리기(Mini DBMS)가 한 레포에 있고, DBMS가 Redis를 SELECT 결과 캐시로 씁니다.

## 폴더 구조

```
.
├── mini-redis/    Mini Redis 서버 (Maven, JDK 25). 해시 테이블·TTL·AOF/RDB·HTTP API
├── mini-dbms/     Mini DBMS CLI (javac, JDK 21+). INSERT·SELECT, 테이블 파일 저장
│   └── src/main/java/minidbms/cache/RedisCache.java   ← Redis HTTP API를 부르는 연결 코드
└── scripts/
    └── run-dbms-with-redis.sh   ← Redis 서버를 띄우고 DBMS를 캐시와 함께 실행
```

## 두 프로젝트를 잇는 방법

```
mini-dbms (CLI 프로세스)
  └ RedisCache ──HTTP GET/PUT /keys/{key}, POST /keys/{key}/incr──▶ mini-redis 서버 (별도 프로세스)
```

- 두 프로젝트는 서로의 소스를 import하지 않고 **HTTP로만** 연결됩니다. 빌드 도구와 JDK 버전이 달라도 각자 빌드하면 됩니다.
- Redis는 라이브러리가 아니라 서버라서 **먼저 떠 있어야** 합니다. DBMS는 `MINIDBMS_REDIS` 환경변수가 있을 때만 캐시를 씁니다.
- **캐시 키**: `dbms:{데이터 폴더 해시}:users:v{버전}:{SELECT 문 해시}`
- **무효화**: INSERT 때 테이블 버전 키를 `INCR`로 올립니다. 그러면 다음 SELECT는 새 키를 보고, 옛 결과는 TTL(60초)이 지나 사라집니다.
- **Redis가 꺼져 있으면**: 그 실행 동안 캐시를 끄고 파일에서 바로 읽습니다. 결과는 항상 파일 기준입니다.

## 실행

```bash
./scripts/run-dbms-with-redis.sh mini-dbms/examples/phase1.sql mini-dbms/examples/read-users.sql
```

SQL 파일마다 DBMS 프로세스를 따로 띄웁니다. 첫 실행은 `cache: hits=0 misses=2`, 다음 실행은 `cache: hits=2 misses=0`이 stderr에 나옵니다. JDK 25가 필요하고, 스크립트는 `JAVA_HOME`이 없으면 `~/tools/jdk-25.0.5+8`을 씁니다.

각 프로젝트를 따로 실행하는 방법은 [mini-redis/README.md](mini-redis/README.md)와 [mini-dbms/README.md](mini-dbms/README.md)에 있습니다.

## 테스트

| 대상 | 명령 | 결과 |
|---|---|---|
| Mini Redis | `cd mini-redis && mvn test` | 57개 통과 |
| Mini DBMS | [mini-dbms/README.md](mini-dbms/README.md)의 테스트 순서 + `java -cp out/main:out/test minidbms.engine.QueryCacheTest` | 4종 통과 (캐시 검사 5개 포함) |
| 연결 (실제 서버) | Mini Redis를 띄운 뒤 `MINIDBMS_TEST_REDIS=http://127.0.0.1:<포트> java -cp out/main:out/test minidbms.engine.QueryCacheTest` | 5개 통과 |

`QueryCacheTest`는 `MINIDBMS_TEST_REDIS`가 없으면 같은 HTTP 규칙을 흉내 내는 가짜 서버로 돌아서, Redis 서버 없이도 실행됩니다.
