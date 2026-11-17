# Mini Redis + Mini DBMS

크래프톤 정글 팀 프로젝트입니다. 직접 만든 키-값 저장소(Mini Redis)와 파일 기반 SQL 처리기(Mini DBMS)가 한 레포에 있습니다.

## 폴더 구조

```
.
├── mini-redis/   Mini Redis 서버 (Maven, JDK 25). 해시 테이블·TTL·AOF/RDB·HTTP API
├── mini-dbms/    Mini DBMS (Maven, JDK 25). SQL 처리기 CLI, 8KB 페이지·WAL·복구·WHERE
└── study/        설계 문서와 학습 자료
```

- DBMS가 Redis를 SELECT 결과 캐시로 쓰는 연결은 Phase 3(#39)에서 붙입니다. 이전 구현과 연결 코드는 `team-dbms` 브랜치에 있습니다.

## 실행·테스트

각 프로젝트 README에 명령이 있습니다: [mini-redis/README.md](mini-redis/README.md), [mini-dbms/README.md](mini-dbms/README.md)

| 대상 | 명령 | 결과 |
|---|---|---|
| Mini Redis | `cd mini-redis && mvn test` | 57개 통과 |
| Mini DBMS | `cd mini-dbms && mvn test` | 210개 통과 (JUnit 병렬 실행) |
