# Mini Redis + Mini DBMS

크래프톤 정글 팀 프로젝트입니다. 직접 만든 키-값 저장소(Mini Redis)와 파일 기반 SQL 처리기(Mini DBMS)가 한 레포에 있습니다.

## 폴더 구조

```
.
├── mini-redis/   Mini Redis 서버 (Maven, JDK 25). 해시 테이블·TTL·AOF/RDB·HTTP API
├── mini-dbms/    Mini DBMS (Maven, JDK 25). SQL 처리기 CLI, 8KB 페이지·WAL·복구·WHERE
├── scripts/      Redis와 DBMS 동시 실행
└── study/        설계 문서와 학습 자료
```

- 지호의 Phase 1 엔진(`minidb`)에 소희의 Redis HTTP 연동을 이식했습니다. `MINIDBMS_REDIS`를 설정하면 SELECT 결과를 캐시하며, Redis 장애 시 DB에서 직접 조회합니다.
- 사용 예: `./scripts/run-dbms-with-redis.sh mini-dbms/examples/phase1.sql mini-dbms/examples/read-users.sql mini-dbms/examples/mutations.sql` (JDK 25+, Maven 필요).

## 실행·테스트

각 프로젝트 README에 명령이 있습니다: [mini-redis/README.md](mini-redis/README.md), [mini-dbms/README.md](mini-dbms/README.md)

| 대상 | 명령 | 결과 |
|---|---|---|
| Mini Redis | `cd mini-redis && mvn test` | 57개 통과 |
| Mini DBMS | `cd mini-dbms && mvn test` | 220개 통과 (엔진·캐시, JUnit 병렬 실행) |
