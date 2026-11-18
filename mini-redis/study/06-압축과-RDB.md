# 06. AOF 압축과 RDB ★★ (1시간)

**한 줄 답**: AOF는 계속 커집니다. 그래서 가끔 "현재 상태 전체"를 base 파일로 다시 쓰고 옛 로그를 지웁니다(압축). 순회하는 동안에도 서버가 멈추지 않도록 **작업 틱마다 2ms씩 조금씩** 순회합니다.

> 흐름: AOF가 커짐 → **[① 새 incr로 전환 → ② 조금씩 base 작성 → ③ manifest 교체 → ④ 옛 파일 삭제]**

## 꼭 알 것

**1. 왜 ①을 먼저 하나: "base가 어느 시점이든 상관없게" 만든다**

```
시간 ──────────────────────────────────────▶
①전환    ②순회 (여러 틱, 그 사이 SET·DEL 계속 들어옴)    ③교체
  │◀──────── 이 사이의 모든 변경은 새 incr에 있다 ────────▶│
```

base에는 어떤 키는 옛 값, 어떤 키는 새 값이 섞여 들어갑니다. 그래도 그 뒤의 변경이 모두 새 incr에 멱등 레코드로 남아 있습니다. 그래서 base 다음에 incr을 적용하면 최종 상태가 정확히 나옵니다.

**2. BGSAVE도 같은 방식 + 변경 로그**
dump.rdb는 파일 하나뿐입니다. 그래서 순회하는 동안 생긴 변경을 같은 파일 끝에 `0x02` 레코드로 붙입니다. 읽을 때는 엔트리를 다 넣은 **뒤에** 변경을 적용합니다.

**3. 파일 교체 4단계: 중간에 죽어도 옛 파일이나 새 파일 중 하나는 온전히 남는다**
임시 파일에 쓰기 → `force` → 원자적 이름 변경 → **디렉터리 `force`**

## 코드에서 볼 곳

| 위치 | 볼 것 |
|---|---|
| [Persistence.java:271](../src/main/java/miniredis/persist/Persistence.java) `startRewrite` | ①: manifest에 새 incr 추가 → 채널 교체 → 옛 채널 force 후 닫기(:284) |
| [Persistence.java:224](../src/main/java/miniredis/persist/Persistence.java) `workTick` | ②: 2ms 동안 커서 한 칸씩 |
| [Persistence.java:354](../src/main/java/miniredis/persist/Persistence.java) `finish` | ③④ (압축) 또는 dump 교체 (BGSAVE) |
| [Persistence.java:337](../src/main/java/miniredis/persist/Persistence.java) `onChange` | BGSAVE 중 변경 로그 수집 |
| [Rdb.java:104](../src/main/java/miniredis/persist/Rdb.java) | 엔트리 다음에 변경 적용 |
| [Persistence.java:442](../src/main/java/miniredis/persist/Persistence.java) `replaceAtomically` | 교체 4단계 |

**증명**: `rewriteWithInterleavedWrites`와 `bgsaveWithInterleavedWrites`는 키 2만 개를 넣고, 압축이나 BGSAVE가 여러 틱에 걸쳐 도는 동안 무작위 SET·DEL을 섞습니다. 그 뒤 재시작해서 상태가 정확히 같은지 봅니다.

## 예상 질문과 답

**Q. 실제 Redis는 어떻게 하나요?**
`fork()`로 자식 프로세스를 만들고, copy-on-write로 fork 순간의 메모리를 자식이 그대로 봅니다(CS:APP의 fork와 COW). Java는 fork를 쓸 수 없어서 커서로 조금씩 도는 방식을 택했습니다.

**Q. 이 방식의 대가는요?**
순회하는 동안 리해싱이 멈추므로(01) 체인이 길어집니다. 그 길이를 `/admin/stats`의 `maxChain`으로 봅니다.

**Q. 디렉터리까지 force하는 이유는요?**
이름 변경은 파일 내용이 아니라 디렉터리 내용을 바꾸는 일입니다. 디렉터리를 디스크에 내리지 않으면, 정전 뒤에 이름 변경이 없었던 일이 될 수 있습니다. macOS에서 `FileChannel`로 디렉터리를 열어 force하는 방법이 동작하는 것도 확인했습니다(기획서 13절의 미확인 항목).

**Q. manifest 파일은 왜 있나요?**
"base 하나 + incr 여러 개"라는 파일 목록과 순서를 한 번에 원자적으로 바꾸려는 것입니다. 파일 여러 개를 따로 바꾸면 중간 상태가 생깁니다.

**Q. 압축과 BGSAVE를 동시에 할 수 있나요?**
이 구현은 한 번에 하나만 합니다. 진행 중에 또 요청하면 409입니다. 기획서는 이 부분을 정하지 않았습니다.

## 건너뛰어도 되는 것
RDB의 바이트 배치, 자동 실행 조건의 정확한 숫자(2배 & 64MB, 60초 1000회)는 건너뛰어도 됩니다.

## 확인 질문
1. ①(새 incr로 전환)과 ②(순회)의 순서를 바꾸면, 순회 도중 들어온 `SET k 9`는 어디에 남을까요?
