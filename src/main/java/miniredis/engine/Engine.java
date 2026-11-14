package miniredis.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import miniredis.persist.Persistence;
import miniredis.persist.Records;
import miniredis.store.Store;
import miniredis.store.StoreException;

/**
 * 데이터를 만지는 유일한 스레드(executor)와 그 앞의 명령 큐.
 *
 * 핸들러 스레드들 ──put──▶ [명령 큐] ──▶ executor 1개: 꺼내서 하나씩 실행 → AOF write 1번 → future 완료
 *
 * 락 없이 데이터 경쟁을 없애는 방법: 데이터에 접근하는 스레드를 처음부터 하나로 만든다.
 */
public final class Engine implements AutoCloseable {
    public record Config(Path dir, long maxmemory, Store.Policy policy) { }

    private record Request(byte[][] argv, CompletableFuture<Object> future) { }

    static final int QUEUE_SIZE = 1024;
    static final int MAX_BATCH = 128;
    private static final long MS = 1_000_000L;
    static final long WORK_TICK = 10 * MS, EXPIRE_TICK = 100 * MS, ADMIN_TICK = 1000 * MS;

    private final ArrayBlockingQueue<Request> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final Config config;
    private final Persistence persistence;
    private final Thread thread;
    private volatile boolean running = true;

    // 아래는 executor 스레드만 쓴다
    private Store store;
    private final List<byte[]> pendingRecords = new ArrayList<>();

    private Engine(Config config) throws IOException {
        this.config = config;
        this.persistence = new Persistence(config.dir()); // 잠금 실패는 여기서 바로 드러난다
        this.thread = Thread.ofPlatform().name("executor").start(this::run);
    }

    /** executor 스레드를 띄우고 복구가 끝날 때까지 기다린다. 복구 실패(손상 등)면 예외. */
    public static Engine start(Config config) throws IOException {
        Engine e = new Engine(config);
        try {
            e.started.join();
        } catch (CompletionException x) {
            throw new IOException("시작 실패: " + x.getCause().getMessage(), x.getCause());
        }
        return e;
    }

    /** 아무 스레드에서나 호출. 큐가 가득 차면 자리가 날 때까지 기다린다 (가상 스레드라 비용이 작다). */
    public CompletableFuture<Object> submit(byte[][] argv) throws InterruptedException {
        CompletableFuture<Object> f = new CompletableFuture<>();
        if (!running) {
            f.completeExceptionally(new StoreException(503, "서버 종료 중"));
            return f;
        }
        queue.put(new Request(argv, f));
        return f;
    }

    /** 편의 함수: 문자열 인자로 실행하고 결과를 기다린다. 실패는 StoreException으로 던진다. */
    public Object call(String... args) throws InterruptedException {
        byte[][] argv = new byte[args.length][];
        for (int i = 0; i < args.length; i++) argv[i] = args[i].getBytes(StandardCharsets.UTF_8);
        return await(submit(argv));
    }

    public static Object await(CompletableFuture<Object> f) throws InterruptedException {
        try {
            return f.get();
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }

    private void run() {
        try {
            // Store를 이 스레드에서 만든다 → owner가 executor가 된다
            byte[] seed = new byte[16]; // 새 바이트가 들어올 곳
            new SecureRandom().nextBytes(seed); // 랜덤한 16바이트 생성
            long processStart = System.nanoTime(); // 시작 시각을 기록함
            store = new Store(seed, new SplittableRandom(), () -> System.nanoTime() - processStart,
                System::currentTimeMillis, config.maxmemory(), config.policy()); // 시작 후 지난 시간
            persistence.recover(store); // AOF에 적힌 명령 처음부터 다시 실행, 메모리를 재시작전으로 복구
            store.setLog(r -> pendingRecords.add(Records.encode(r))); // 쓰기마다 기록을 pendingRecords에 담음. recover뒤에 연결해야 복구 중 명령이 AOF에 또 안 적힘
            started.complete(null); // 준비가 끝났다는 신호
        } catch (Throwable t) {
            started.completeExceptionally(t); // 시작 실패 신호
            closePersistence(); // 열어둔 파일과 lock 정리
            return;
        }
        // OOM(Out of Memory 메모리 바닥남)은 여기서 잡지 않는다. 힙이 꽉 차면 정리 코드도 메모리를 못 얻는다 → JVM 옵션으로 즉시 종료 (run.sh, 트러블슈팅.md)
        loop();
        // 남은 요청은 실패로 끝낸다
        for (Request r; (r = queue.poll()) != null; ) r.future.completeExceptionally(new StoreException(503, "서버 종료 중"));
        closePersistence();
    }

    private void loop() {
        long now = System.nanoTime();
        long nextWork = now + WORK_TICK, nextExpire = now + EXPIRE_TICK, nextAdmin = now + ADMIN_TICK;
        while (running) {
            // Java에는 Go의 select가 없다. 다음 틱까지 남은 시간만큼만 큐에서 기다린다
            long wait = Math.min(nextWork, Math.min(nextExpire, nextAdmin)) - System.nanoTime();
            Request r;
            try {
                r = wait > 0 ? queue.poll(wait, TimeUnit.NANOSECONDS) : queue.poll();
            } catch (InterruptedException e) {
                continue;
            }
            if (r != null) processBatch(r);

            now = System.nanoTime();
            if (now >= nextWork) {
                store.rehashFor(MS);           // 리해싱 1ms
                persistence.workTick(2 * MS);  // 진행 중인 스냅샷·압축 2ms
                flushRecords();
                nextWork = now + WORK_TICK;
            }
            if (now >= nextExpire) {
                store.activeExpireCycle();     // 주기적 청소. 지운 키는 DEL 레코드가 된다
                flushRecords();
                nextExpire = now + EXPIRE_TICK;
            }
            if (now >= nextAdmin) {
                persistence.adminTick(store);
                flushRecords();
                nextAdmin = now + ADMIN_TICK;
            }
        }
    }

    /** ③ 1개 + 최대 127개 더 → ④ 하나씩 실행 → ⑤ AOF write 1번 → ⑥ future 완료. ⑤가 ⑥보다 먼저다. */
    private void processBatch(Request first) {
        List<Request> batch = new ArrayList<>(MAX_BATCH);
        batch.add(first);
        queue.drainTo(batch, MAX_BATCH - 1);
        Object[] results = new Object[batch.size()];
        boolean[] wrote = new boolean[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            int before = pendingRecords.size();
            try {
                results[i] = handle(batch.get(i).argv);
            } catch (RuntimeException e) {
                results[i] = e;
            }
            wrote[i] = pendingRecords.size() > before;
        }
        boolean ok = flushRecords();
        for (int i = 0; i < batch.size(); i++) {
            CompletableFuture<Object> f = batch.get(i).future;
            if (results[i] instanceof RuntimeException e) f.completeExceptionally(e);
            else if (wrote[i] && !ok) f.completeExceptionally(new StoreException(503, "디스크 기록 실패"));
            else f.complete(results[i]);
        }
    }

    private boolean flushRecords() {
        if (pendingRecords.isEmpty()) return true;
        boolean ok = persistence.append(pendingRecords);
        pendingRecords.clear();
        return ok;
    }

    private Object handle(byte[][] argv) {
        String cmd = new String(argv[0], StandardCharsets.UTF_8).toUpperCase();
        switch (cmd) {
            case "SAVE" -> { persistence.save(store); return "OK"; }
            case "BGSAVE" -> { persistence.startBgsave(store); return "STARTED"; }
            case "REWRITEAOF" -> { persistence.startRewrite(store); return "STARTED"; }
            case "STATS" -> {
                Map<String, Object> m = new LinkedHashMap<>(store.stats());
                m.putAll(persistence.stats());
                m.put("queue", (long) queue.size());
                return m;
            }
            default -> {
                if (Store.isWrite(cmd) && persistence.isRejected()) throw new StoreException(503, "디스크 기록 실패로 쓰기 거부 중");
                return store.execute(argv);
            }
        }
    }

    private void closePersistence() {
        try {
            persistence.close();
        } catch (IOException e) {
            System.err.println("종료 중 오류: " + e);
        }
    }

    /** 큐를 멈추고 executor가 끝날 때까지 기다린다. 마지막에 AOF를 force하고 잠금을 푼다. */
    @Override
    public void close() throws InterruptedException {
        running = false; // interrupt는 쓰지 않는다: FileChannel 작업 중 interrupt되면 채널이 닫혀 버린다
        thread.join();
    }
}
