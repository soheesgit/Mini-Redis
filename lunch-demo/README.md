# 오늘 점심 뭐 먹지?

Mini React의 점심 투표 화면에 실제 Mini DBMS와 Mini Redis를 연결한 로컬 발표 데모입니다. [Mini-React의 main 커밋](https://github.com/soheesgit/Mini-React/tree/0d77de2ce491daca2cf7743ff188c46301f1ce9f)에서 브라우저 모듈과 스타일을 가져왔습니다. 투표 순위는 Mini React가 갱신하며, 오른쪽 시스템 로그에는 SQL과 Redis 요청이 실행 순서대로 표시됩니다.

## 발표 전 준비

저장소 루트에서 Node.js 22 이상과 JDK 25 이상으로 실행합니다. macOS는 JDK 25를 자동 탐색하고, 다른 환경은 `JAVA_HOME`이나 `PATH`를 사용합니다.

```bash
node lunch-demo/demo.mjs prepare
node lunch-demo/demo.mjs start
```

`prepare`는 기존 Java 서비스를 컴파일하고 초기 메뉴와 합성 투표 100만 건을 Mini DBMS에 미리 삽입합니다. `start`는 루프백에서 두 Java 서비스를 실행하고 데이터 범위를 확인하며, 인덱스와 전체 스캔을 각각 한 번 예열합니다. 데이터는 Git이 추적하지 않는 `lunch-demo/.local/`에 저장되고 서버를 다시 켜도 득표수가 유지됩니다.

발표 전에 [http://127.0.0.1:4173/](http://127.0.0.1:4173/)를 열어둡니다. 기본 포트가 사용 중이면 `PORT=4180 node lunch-demo/demo.mjs start`로 실행할 수 있습니다. 리허설이 끝나면 화면의 **초기화**로 메뉴, 득표수, 순위 캐시를 복구합니다. 과거 투표는 그대로 재사용합니다.

## 1분 발표 순서

| 시간 | 동작 | 결과 |
| --- | --- | --- |
| 0~20초 | **페이지 열기** | 캐시가 비어 있으면 Redis MISS → DBMS SELECT → 캐시 저장. TTL 안에 새로고침하면 Redis HIT |
| 20~40초 | **김치찌개 투표** | SQL UPDATE → Redis 키 삭제 → 새 순위 조회 MISS와 재저장. 초기 상태에서는 김치찌개가 12표에서 13표로 1위가 됩니다. |
| 40~60초 | **과거 투표 찾기** | 100만 건에서 500000번 투표를 두 방식으로 찾습니다. 응답 시간 중앙값과 반환 행의 일치 여부를 표시합니다. |

오른쪽 **시스템 로그**는 동작별로 실제 SQL 또는 Redis 명령, HTTP 상태, 응답, 실측 시간을 순서대로 표시합니다.

## 실제 연결

- **순위 조회** `GET /api/ranking`은 페이지를 열 때 `lunch:ranking`을 읽습니다. Redis의 HTTP 404는 MISS입니다. Mini DBMS의 SELECT 결과를 Mini Redis에 TTL 60초로 저장합니다. 캐시가 있으면 HIT와 함께 저장된 결과를 응답합니다.
- **투표** `POST /api/vote`는 메뉴 ID로 현재 표를 읽고 `UPDATE lunch_menus SET votes = <새 득표수> WHERE id = <메뉴 ID>;`를 실행합니다. 쓰기가 성공하면 Redis의 `DELETE /keys/lunch:ranking`을 호출하고 새 순위를 읽어 캐시를 채웁니다.
- **메뉴 추가·삭제·초기화**는 각각 `POST /api/add`, `POST /api/remove`, `POST /api/reset`이며 DBMS의 실제 INSERT와 DELETE를 실행합니다.
- 모든 SQL을 Mini DBMS의 `POST /query`로 한 문장씩 보냅니다. DBMS의 기본 SQL 워커는 8개입니다. 데모의 순위 읽기와 쓰기는 캐시 무효화 직후 이전 순위가 다시 저장되는 일을 막기 위해 Node 서버에서 순서대로 처리합니다. 과거 투표 조회는 독립적으로 실행합니다.
- 브라우저는 같은 출처의 Node 서버에 접속합니다. Node 서버가 Mini Redis HTTP API에 직접 보낸 요청에는 브라우저 Origin 헤더가 없습니다.

### 인덱스 비교

`POST /api/history`로 다음 SQL을 번갈아 5회씩 실행합니다.

```sql
SELECT * FROM vote_history WHERE id = 500000;
SELECT * FROM vote_history WHERE id = 500000 AND TRUE;
```

현재 DBMS의 `Where.indexRange`는 최상위의 단순 ID 비교를 인식합니다. 두 번째 쿼리의 최상위 `AND`는 인덱스 범위를 만들지 않아서 `Database.Pages.matching`이 전체 페이지를 순회합니다. 두 경로 모두 Mini Redis를 거치지 않으며, 화면의 시간은 각각 DBMS HTTP 요청부터 응답 본문을 읽기까지의 실측 중앙값입니다.

## 검증

```bash
node --test lunch-demo/demo.test.mjs
```

새 데이터 폴더에 100만 건을 준비한 뒤 테스트 7개가 통과했습니다. 실제 캐시 MISS/HIT, UPDATE와 키 삭제, 메뉴 INSERT·DELETE, 동시 투표 보존, 인덱스와 스캔의 반환 행 일치를 확인했습니다. 브라우저 응답에서 `pipeThrough` 또는 응답 스트림이 제공되지 않는 경우도 테스트합니다.
