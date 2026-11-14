# 03. AOF와 복구 ★★★ (1.5시간)

**한 줄 답**: 데이터를 바꿀 때마다 그 변경을 파일 끝에 붙입니다(AOF). 재시작하면 그 파일을 처음부터 다시 실행해서 상태를 되살립니다. DBMS의 WAL과 같은 생각입니다.

> 흐름: Store.execute가 레코드 생성 → **[AOF write → 응답 → (1초 뒤) fsync]** → 재시작 시 재실행

## 꼭 알 것 4가지

**1. 응답보다 기록이 먼저 (⑤ → ⑥)**

```
executor:  실행 → write (OS 메모리로) → future 완료(응답)
fsync 스레드:                     ...1초마다 → force (디스크로)
```

| 상황 | 응답 받은 쓰기는? | 이유 |
|---|---|---|
| `kill -9` (프로세스만 죽음) | **남는다** | write가 끝나면 데이터는 커널의 페이지 캐시에 있고, 커널이 나중에 디스크에 쓴다 |
| 정전 | 최대 약 1초 잃는다 | 커널 메모리도 날아간다. 마지막 fsync 이후만 위험 |

**2. 멱등 레코드: 두 번 실행해도 결과가 같게 기록**

| 실행 | 기록 | 그대로 기록하면 깨지는 것 |
|---|---|---|
| `SET k v EX 10` | `SET k v PXAT <절대시각>` | 재시작 시점부터 다시 10초가 된다 |
| `INCR k` → 6 | `SET k 6` | 재실행이나 압축 중 중복 적용으로 두 번 오른다 |
| 만료 삭제, LRU 제거 | `DEL k` | 재시작하면 지운 키가 되살아난다 |

**3. 레코드 = [길이][본문][CRC]: 잘린 꼬리와 손상을 구분**

| 발견 | 판단 | 처리 |
|---|---|---|
| 마지막 파일의 마지막 레코드가 짧거나 CRC 불일치 | 쓰다가 죽은 꼬리 (응답 못 받은 쓰기) | 그 앞까지 자르고 시작 |
| 그 밖의 위치에서 CRC 불일치 | 손상 | **시작 거부** (뒤의 레코드를 믿을 수 없다) |

**4. 데이터 폴더 잠금**
두 프로세스가 같은 AOF에 쓰면 레코드가 섞입니다. 그래서 시작할 때 `lock` 파일에 잠금을 겁니다. 프로세스가 죽으면 OS가 잠금을 풀어 주므로 크래시 뒤에도 다시 시작할 수 있습니다.

## 코드에서 볼 곳

| 위치 | 볼 것 |
|---|---|
| [Engine.java:150](../src/main/java/miniredis/engine/Engine.java) `processBatch` | 실행 → `flushRecords`(:165) → `complete`(:170). 이 순서가 1번 |
| [Store.java:263](../src/main/java/miniredis/store/Store.java) | `SET`을 `PXAT` 절대 시각으로 기록 |
| [Persistence.java:149](../src/main/java/miniredis/persist/Persistence.java) `append` | 배치를 write 1번으로 (group commit) |
| [Persistence.java:177](../src/main/java/miniredis/persist/Persistence.java) | fsync 스레드 (1초마다 `force`) |
| [Records.java:39](../src/main/java/miniredis/persist/Records.java) `parse` | 꼬리(:47, :53)와 손상을 가르는 조건 |
| [Persistence.java:123](../src/main/java/miniredis/persist/Persistence.java) `replayAof` | 마지막 파일만 꼬리를 허용, 자르기(:132) |

**직접 해보기**: `ConcurrencyAndProcessTest.killDash9ThenRestartKeepsAcknowledgedWrites`는 실제 서버 프로세스를 띄우고 SIGKILL로 죽인 뒤 다시 띄워서 데이터를 확인합니다.

## 예상 질문과 답

**Q. 요청마다 fsync하면 더 안전한데 왜 1초마다 하나요?**
fsync는 디스크까지 기다리므로 ms 단위로 걸립니다. 요청마다 하면 처리량이 크게 떨어집니다. Redis의 기본값(`appendfsync everysec`)과 같은 절충입니다.

**Q. 배치로 모아 쓰면 무엇이 좋은가요?**
명령 128개를 시스템 콜 1번으로 씁니다. DBMS의 group commit과 같은 원리입니다.

**Q. AOF write가 실패하면(디스크 꽉 참) 어떻게 되나요?**
쓰기 요청은 503으로 거부하고 읽기는 계속 받습니다. 못 쓴 바이트는 보관해 두었다가 1초마다 다시 씁니다. 이 경로는 테스트하지 않았습니다(디스크 실패를 재현하기 어려움).

**Q. 왜 `BufferedOutputStream`을 쓰지 않나요?**
fsync 스레드와 같은 파일을 다루기 때문입니다. `FileChannel`은 여러 스레드가 동시에 써도 안전하다고 문서에 명시되어 있지만, 버퍼 스트림은 그렇지 않습니다.

**Q. DBMS의 WAL과 다른 점은요?**
생각은 같습니다(로그 먼저, 재실행으로 복구). 다른 점은 두 가지입니다. 데이터 페이지가 따로 없고 메모리가 전부라서 undo가 필요 없습니다. 레코드도 페이지 단위가 아니라 명령 단위입니다.

## 건너뛰어도 되는 것
레코드의 바이트 배치(빅엔디언 등)와 manifest 파일 형식은 건너뛰어도 됩니다.

## 확인 질문
1. `processBatch`에서 ⑥(응답)을 ⑤(write)보다 먼저 하면, `kill -9` 때 어떤 일이 생길 수 있나요?
2. 첫 번째 incr 파일의 마지막 레코드가 잘려 있고 뒤에 두 번째 incr 파일이 있습니다. 시작할까요, 거부할까요? 이유는?
