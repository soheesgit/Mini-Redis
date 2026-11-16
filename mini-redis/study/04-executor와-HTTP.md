# 04. executor와 HTTP ★★★ (1.5시간, HTTP는 20분이면 충분)

**한 줄 답**: 요청은 여러 스레드가 동시에 받습니다. 하지만 **데이터를 만지는 스레드는 executor 하나뿐**이라 락 없이도 데이터 경쟁이 생기지 않습니다.

> 흐름: 요청 수신(가상 스레드 여러 개) → **[명령 큐 → executor 1개]** → 응답

## 동시성이 생기는 곳과 없애는 곳

```
요청A → 가상 스레드 A ─┐
요청B → 가상 스레드 B ─┼─put→ [큐 1024칸] ─→ executor 1개 ─→ Store (해시 테이블, LRU, ...)
요청C → 가상 스레드 C ─┘      ↑ 스레드 안전                 ↑ 이 스레드만 접근
        (동시성이 생김)                                    (동시성이 없음)
```

- **동시성이 생기는 곳**: HTTP 서버가 요청마다 가상 스레드를 하나씩 만듭니다. 이건 막을 수 없는 바깥 사정입니다.
- **없애는 방법**: 데이터 쪽 입구를 큐 하나로 좁힙니다. Redis가 "싱글 스레드"라는 말도 이 뜻입니다.
- **싱글 스레드로도 남는 동시성**: 명령 사이에 다른 명령이 끼어드는 것은 막지 못합니다. GET 다음에 SET을 보내는 방식으로 카운터를 올리면 값이 틀릴 수 있습니다. 그래서 읽기와 쓰기를 명령 하나로 묶은 `INCR`이 있습니다.

## 꼭 알 것 3가지

**1. 공유 객체는 딱 5개, 모두 스레드 안전한 것만**

| 공유 대상 | 누가 → 누구에게 | 안전 근거 |
|---|---|---|
| 명령 큐 | 핸들러 → executor | `ArrayBlockingQueue` |
| 응답 future | executor → 핸들러 | `CompletableFuture` |
| AOF `FileChannel` | executor(write)와 fsync 스레드(force) | JDK 문서에 명시 |
| 현재 AOF 채널 참조 | executor → fsync 스레드 | `volatile` |
| fsync 실패 여부 | fsync 스레드 → executor | `volatile` |

**2. 규칙을 코드로 강제: 스레드 소유 검사**
Java에는 Go의 `-race` 같은 표준 검사기가 없습니다. 그래서 `Store`가 자기를 만든 스레드를 기억해 두고, 다른 스레드가 부르면 바로 예외를 던집니다. 비용은 참조 비교 1번입니다.

**3. 타이머 작업도 executor 안에서**
리해싱, 만료 청소, 스냅샷을 다른 스레드에 맡기면 경쟁이 다시 생깁니다. 그래서 executor는 "다음 틱까지 남은 시간"만큼만 큐에서 기다리고, 시간이 되면 틱 작업을 직접 실행합니다.

## 코드에서 볼 곳

| 위치 | 볼 것 |
|---|---|
| [Engine.java:99](../src/main/java/miniredis/engine/Engine.java) | `Store`를 executor 스레드 **안에서** 만든다 → owner가 executor가 된다 |
| [Engine.java:115](../src/main/java/miniredis/engine/Engine.java) `loop`, `:123` | `poll(wait)`: 명령이 오거나 틱 시간이 되면 깨어난다 |
| [Engine.java:150](../src/main/java/miniredis/engine/Engine.java) `processBatch` | 1개 + 최대 127개를 꺼내 한 번에 처리 |
| [Store.java:72](../src/main/java/miniredis/store/Store.java) `check` | 소유 검사 |
| [HttpApi.java:45](../src/main/java/miniredis/httpapi/HttpApi.java) | `setExecutor`를 `start()` 전에. 빠뜨리면 요청이 스레드 하나에서 차례로 처리되는데, 그래도 테스트는 통과한다 |

**증명**: `concurrentIncrIsExact`는 가상 스레드 100개가 HTTP INCR을 1000번씩 보내서 결과가 정확히 100,000인지 봅니다. `otherThreadIsRejected`는 다른 스레드에서 호출하면 예외가 나는지 봅니다.

## HTTP 검사 (20분, 무엇을 왜 막는지만)

| 검사 | 막는 것 | 위치 |
|---|---|---|
| `Origin` 헤더가 있으면 403 | 같은 PC의 웹페이지가 브라우저를 통해 명령 실행 | HttpApi.java:81 |
| SHA-256 후 `MessageDigest.isEqual` | 비교 시간으로 비밀번호 추측 | :189 |
| 1MB+1까지만 읽기 | 거대 본문으로 메모리 고갈 | :202 |
| raw 경로를 먼저 `/`로 나누고 디코딩 | `%2F`가 든 키가 다른 경로로 해석됨 | :219 |
| 키 길이를 UTF-8 바이트로 | `String.length()`는 UTF-16 단위라 한글 길이가 틀림 | :114 |

## 예상 질문과 답

**Q. 싱글 스레드면 동시성 문제가 없는데 왜 동시성 테스트를 하나요?**
요청을 받는 쪽은 멀티 스레드입니다. 큐와 future라는 경계가 맞게 동작하는지, 그리고 INCR이 중간에 끊기지 않는지를 결과값으로 증명하는 것입니다.

**Q. 락을 쓰면 되지 않나요?**
쓸 수는 있습니다. 하지만 명령뿐 아니라 리해싱, 만료 청소, 스냅샷 순회, LRU 이동까지 모든 경로에 빠짐없이 락을 걸어야 하고, 하나만 빠뜨려도 조용히 깨집니다. 스레드를 하나로 만들면 빠뜨릴 곳 자체가 없습니다. 대가는 CPU 코어를 하나만 쓴다는 것입니다.

**Q. 왜 가상 스레드인가요?**
핸들러는 대부분 큐에 넣고 기다리는 시간입니다. 가상 스레드는 기다리는 동안 OS 스레드를 잡고 있지 않습니다. 그래서 요청마다 하나씩 만들어도 비용이 작습니다.

**Q. 큐가 가득 차면요?**
핸들러가 `put()`에서 기다립니다. 서버가 처리할 수 있는 속도 이상으로 요청을 받아 쌓지 않는 것입니다(backpressure).

**Q. 느린 명령이 있으면요?**
`KEYS`, `SAVE`, 키가 많을 때의 `FLUSHALL`은 실행되는 동안 다른 요청이 모두 기다립니다. 이 구조의 한계이고, 실제 Redis도 같습니다.

**Q. 종료할 때 왜 `interrupt`를 안 쓰나요?**
`FileChannel` 작업 중에 interrupt되면 채널이 닫혀 버립니다(`ClosedByInterruptException`). 그래서 `running` 플래그만 내리고, 최대 10ms 뒤에 반복문이 스스로 끝나게 합니다.

## 측정에서 나온 것
JDK 내장 HTTP 서버를 기본 설정으로 쓰면 연결 재사용 오류(`header parser received no bytes`)가 약 1% 났습니다. `run.sh`의 `-D` 설정으로 띄우면 0%였고 처리량도 약 2배였습니다. 4개 설정 중 어느 것이 원인인지는 아직 확인하지 않았습니다.

## 확인 질문
1. 만료 청소를 `ScheduledExecutorService`(별도 스레드)로 옮기면 어떤 일이 생길까요? 소유 검사는 그때 무엇을 할까요?
2. `HttpApi.java:45`의 `setExecutor` 줄을 지워도 `concurrentIncrIsExact`가 통과하는 이유는 무엇일까요?
