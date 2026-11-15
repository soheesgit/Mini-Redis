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
import miniredis.debug.Trace;
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
    public record Config(Path dir, long maxmemory, Store.Policy policy, Debug debug) {
        public Config(Path dir, long maxmemory, Store.Policy policy) {
            this(dir, maxmemory, policy, null); // 기존 호출부 그대로: 관찰 기능 꺼짐
        }
    }

    /** 관찰 기능(데모 전용). trace가 있으면 단계별 이벤트를 남기고 DEBUG 명령을 받는다. fixedSeed면 seed를 0으로 고정. */
    public record Debug(Trace trace, boolean fixedSeed) { }

    private record Request(byte[][] argv, CompletableFuture<Object> future, long traceId) { }

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

    private final Trace trace; // null이면 관찰 꺼짐 (기본)

    // 아래는 executor 스레드만 쓴다
    private Store store;
    private final List<byte[]> pendingRecords = new ArrayList<>();
    private final List<Long> pendingOwners = new ArrayList<>(); // 관찰용: 레코드마다 만든 요청 ID
    private long batchNo;
    private boolean pauseWork, pauseExpire; // 관찰용 틱 제어 (DEBUG TICKS)

    private Engine(Config config) throws IOException {
        this.config = config;
        this.trace = config.debug() == null ? null : config.debug().trace();
        this.persistence = new Persistence(config.dir()); // 잠금 실패는 여기서 바로 드러난다
        persistence.setTrace(trace);
        this.thread = Thread.ofPlatform().name("executor").start(this::run);
    }

    /** 관찰 기능이 켜졌으면 Trace, 아니면 null */
    public Trace trace() {
        return trace;
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
        long id = trace == null ? 0 : trace.bound();
        if (trace != null && id >= 0) trace.emit("queue.put", "cmd", word(argv, 0), "key", word(argv, 1), "queueBefore", queue.size());
        queue.put(new Request(argv, f, id));
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
            boolean fixedSeed = config.debug() != null && config.debug().fixedSeed(); // 데모 전용: 0으로 고정해 버킷 배치를 재현
            if (!fixedSeed) new SecureRandom().nextBytes(seed); // 랜덤한 16바이트 생성
            long processStart = System.nanoTime(); // 시작 시각을 기록함
            store = new Store(seed, new SplittableRandom(), () -> System.nanoTime() - processStart,
                System::currentTimeMillis, config.maxmemory(), config.policy()); // 시작 후 지난 시간
            persistence.recover(store); // AOF에 적힌 명령 처음부터 다시 실행, 메모리를 재시작전으로 복구
            store.setLog(r -> { // 쓰기마다 기록을 pendingRecords에 담음. recover뒤에 연결해야 복구 중 명령이 AOF에 또 안 적힘
                pendingRecords.add(Records.encode(r));
                if (trace != null) pendingOwners.add(trace.bound());
            });
            if (trace != null) {
                store.setTrace(trace); // 복구 뒤에 연결: 복구 중 재실행은 리해싱 이벤트를 남기지 않는다
                trace.emit("server.start", "req", 0, "fixedSeed", fixedSeed, "maxmemory", config.maxmemory(),
                    "policy", config.policy().name(), "snap", new Trace.Raw(Trace.json(store.snapshot())));
            }
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
                if (!pauseWork) workTick();
                nextWork = now + WORK_TICK;
            }
            if (now >= nextExpire) {
                if (!pauseExpire) expireTick();
                nextExpire = now + EXPIRE_TICK;
            }
            if (now >= nextAdmin) {
                persistence.adminTick(store);
                flushRecords();
                nextAdmin = now + ADMIN_TICK;
            }
        }
    }

    private void workTick() {
        long s = trace == null ? 0 : trace.seq();
        store.rehashFor(MS);           // 리해싱 1ms
        persistence.workTick(2 * MS);  // 진행 중인 스냅샷·압축 2ms
        flushRecords();
        tickDone("tick.work", s);
    }

    private void expireTick() {
        long s = trace == null ? 0 : trace.seq();
        store.activeExpireCycle();     // 주기적 청소. 지운 키는 DEL 레코드가 된다
        flushRecords();
        tickDone("tick.expire", s);
    }

    /** 관찰용: 틱 안에서 무언가 바뀌었으면(이벤트가 생겼으면) 그 뒤 상태 사진을 남긴다 */
    private void tickDone(String type, long seqBefore) {
        if (trace != null && trace.seq() != seqBefore) trace.emit(type, "req", 0, "snap", new Trace.Raw(Trace.json(store.snapshot())));
    }

    /** ③ 1개 + 최대 127개 더 → ④ 하나씩 실행 → ⑤ AOF write 1번 → ⑥ future 완료. ⑤가 ⑥보다 먼저다. */
    private void processBatch(Request first) {
        List<Request> batch = new ArrayList<>(MAX_BATCH);
        batch.add(first);
        queue.drainTo(batch, MAX_BATCH - 1);
        long bno = ++batchNo;
        if (trace != null) {
            List<Long> ids = new ArrayList<>();
            for (Request r : batch) if (r.traceId >= 0) ids.add(r.traceId);
            if (!ids.isEmpty()) trace.emit("batch.start", "req", 0, "batch", bno, "reqs", ids, "size", batch.size(),
                "observe", batch.size() - ids.size(), "queueLeft", queue.size());
        }
        Object[] results = new Object[batch.size()];
        boolean[] wrote = new boolean[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            Request r = batch.get(i);
            boolean watch = trace != null && r.traceId >= 0;
            if (trace != null) trace.bind(r.traceId); // 실행 중 Store·HashTable에서 생기는 이벤트가 이 요청 ID를 갖는다
            if (watch) trace.emit("exec.start", "batch", bno, "cmd", word(r.argv, 0), "key", word(r.argv, 1));
            int before = pendingRecords.size();
            try {
                results[i] = handle(r.argv);
            } catch (RuntimeException e) {
                results[i] = e;
            }
            wrote[i] = pendingRecords.size() > before;
            if (watch) traceExecEnd(bno, r.argv, results[i], pendingRecords.size() - before);
        }
        if (trace != null) trace.bind(0);
        boolean ok = flushRecords();
        for (int i = 0; i < batch.size(); i++) {
            CompletableFuture<Object> f = batch.get(i).future;
            long id = batch.get(i).traceId;
            if (trace != null && id >= 0) trace.emit("future.done", "req", id, "batch", bno,
                "ok", !(results[i] instanceof RuntimeException) && !(wrote[i] && !ok));
            if (results[i] instanceof RuntimeException e) f.completeExceptionally(e);
            else if (wrote[i] && !ok) f.completeExceptionally(new StoreException(503, "디스크 기록 실패"));
            else f.complete(results[i]);
        }
        if (trace != null) trace.unbind();
    }

    private boolean flushRecords() {
        if (pendingRecords.isEmpty()) return true;
        String file = trace == null ? null : persistence.currentFile();
        long offset = trace == null ? 0 : persistence.currentSize();
        boolean ok = persistence.append(pendingRecords);
        if (trace != null) {
            List<Map<String, Object>> recs = new ArrayList<>();
            for (int i = 0; i < pendingRecords.size(); i++) {
                byte[] b = pendingRecords.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("req", i < pendingOwners.size() ? pendingOwners.get(i) : 0L);
                m.put("len", b.length);
                m.put("hex", Trace.hex(b, 0, Math.min(b.length, 512)));
                recs.add(m);
            }
            trace.emit("aof.write", "req", 0, "file", file, "offset", offset, "ok", ok, "records", recs);
        }
        pendingRecords.clear();
        pendingOwners.clear();
        return ok;
    }

    // ---------------- 관찰용 (데모, 기본 꺼짐) ----------------

    private void traceExecEnd(long bno, byte[][] argv, Object result, int records) {
        String cmd = word(argv, 0).toUpperCase();
        Object loc = null;
        if (argv.length >= 2 && !cmd.equals("KEYS") && !cmd.equals("DEBUG")) {
            loc = new Trace.Raw(Trace.json(store.debug(new byte[][] {bytes("DEBUG"), bytes("LOCATE"), argv[1]})));
        }
        trace.emit("exec.end", "batch", bno, "cmd", cmd, "key", word(argv, 1), "result", summary(result),
            "error", result instanceof RuntimeException, "records", records, "loc", loc,
            "snap", new Trace.Raw(Trace.json(store.snapshot())));
    }

    private Map<String, Object> debug(byte[][] argv) {
        String sub = argv.length > 1 ? word(argv, 1).toUpperCase() : "";
        switch (sub) {
            case "AOF" -> {
                try {
                    return persistence.debugAof(64 * 1024);
                } catch (IOException e) {
                    throw new StoreException(500, "AOF 읽기 실패: " + e.getMessage());
                }
            }
            case "TICKS" -> { // DEBUG TICKS work|expire on|off
                boolean pause = argv.length > 3 && word(argv, 3).equalsIgnoreCase("off");
                if (word(argv, 2).equalsIgnoreCase("work")) pauseWork = pause; else pauseExpire = pause;
                trace.emit("tick.control", "req", 0, "work", !pauseWork, "expire", !pauseExpire);
                return ticks();
            }
            case "TICK" -> { // DEBUG TICK work|expire: 그 틱 본문을 1회 실행
                trace.bind(0); // 틱에서 생긴 일은 요청 없는 일로 기록
                if (word(argv, 2).equalsIgnoreCase("work")) workTick(); else expireTick();
                return ticks();
            }
            default -> {
                Map<String, Object> m = new LinkedHashMap<>(store.debug(argv));
                if (sub.equals("TABLE")) {
                    m.put("queue", queue.size());
                    m.putAll(ticks());
                    m.putAll(persistence.stats());
                    m.put("aofFile", persistence.currentFile());
                }
                return m;
            }
        }
    }

    private Map<String, Object> ticks() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("workTick", !pauseWork);
        m.put("expireTick", !pauseExpire);
        return m;
    }

    private static String summary(Object r) {
        if (r == null) return "(nil)";
        if (r instanceof RuntimeException e) return e.getMessage();
        if (r instanceof byte[] b) return new String(b, 0, Math.min(b.length, 64), StandardCharsets.UTF_8);
        String s = r.toString();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static String word(byte[][] argv, int i) {
        return argv.length > i ? new String(argv[i], 0, Math.min(argv[i].length, 128), StandardCharsets.UTF_8) : "";
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Object handle(byte[][] argv) {
        String cmd = new String(argv[0], StandardCharsets.UTF_8).toUpperCase();
        switch (cmd) {
            case "SAVE" -> { persistence.save(store); return "OK"; }
            case "BGSAVE" -> { persistence.startBgsave(store); if (trace != null) trace.emit("job.start", "kind", "bgsave"); return "STARTED"; }
            case "REWRITEAOF" -> {
                persistence.startRewrite(store);
                if (trace != null) trace.emit("job.start", "kind", "rewrite", "file", persistence.currentFile());
                return "STARTED";
            }
            case "DEBUG" -> {
                if (trace == null) return store.execute(argv); // 꺼져 있으면 지금처럼 "알 수 없는 명령" 400
                return Trace.json(debug(argv));
            }
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
