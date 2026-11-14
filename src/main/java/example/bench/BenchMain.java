package example.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 부하 생성·측정. 동시 요청 수, 시간, 키 분포를 받아 처리량, p50/p95/p99, 적중률, 원본 호출 수를 출력한다.
 *
 * --url URL          요청 대상. 끝에 키가 붙는다 (예: http://127.0.0.1:8082/products/)
 * --mode get|put     put이면 본문 16바이트로 PUT (시나리오 F의 쓰기)
 * --concurrency N    동시 요청 수 (가상 스레드 N개)          기본 50
 * --duration S       측정 시간(초)                           기본 10
 * --warmup S         워밍업(초). 결과에서 뺀다 (JIT 때문)    기본 10
 * --keys N           키 0..N-1 균등 분포                      기본 1000
 * --miss-ratio R     이 확률로 처음 보는 키를 요청 (적중률 조절) 기본 0
 * --same-key K       모든 요청이 키 K 하나만 (시나리오 E)
 * --cache-stats URL  캐시 API의 /stats (적중률 계산)
 * --origin-stats URL 원본의 /stats (원본 호출 수 계산)
 * --repeat N         N회 반복 후 중앙값도 출력               기본 1
 */
public final class BenchMain {
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final AtomicLong UNIQUE = new AtomicLong();
    private static final java.util.Map<String, AtomicLong> ERROR_KINDS = new java.util.concurrent.ConcurrentHashMap<>();

    record Result(long requests, long errors, double rps, double p50, double p95, double p99, Double hitRate, Long originCalls) { }

    public static void main(String[] a) throws Exception {
        String url = null, mode = "get", sameKey = null, cacheStats = null, originStats = null;
        int conc = 50, duration = 10, warmup = 10, keys = 1000, repeat = 1;
        double missRatio = 0;
        for (int i = 0; i + 1 < a.length; i += 2) {
            switch (a[i]) {
                case "--url" -> url = a[i + 1];
                case "--mode" -> mode = a[i + 1];
                case "--concurrency" -> conc = Integer.parseInt(a[i + 1]);
                case "--duration" -> duration = Integer.parseInt(a[i + 1]);
                case "--warmup" -> warmup = Integer.parseInt(a[i + 1]);
                case "--keys" -> keys = Integer.parseInt(a[i + 1]);
                case "--miss-ratio" -> missRatio = Double.parseDouble(a[i + 1]);
                case "--same-key" -> sameKey = a[i + 1];
                case "--cache-stats" -> cacheStats = a[i + 1];
                case "--origin-stats" -> originStats = a[i + 1];
                case "--repeat" -> repeat = Integer.parseInt(a[i + 1]);
                default -> throw new IllegalArgumentException(a[i]);
            }
        }
        if (url == null) throw new IllegalArgumentException("--url 필요");
        List<Result> results = new ArrayList<>();
        for (int r = 0; r < repeat; r++) {
            Result res = run(url, mode, conc, duration, warmup, keys, missRatio, sameKey, cacheStats, originStats);
            print("run " + (r + 1), res);
            results.add(res);
        }
        if (repeat > 1) {
            results.sort((x, y) -> Double.compare(x.rps, y.rps));
            print("median", results.get(results.size() / 2));
        }
        System.out.printf("env: %s %s, JDK %s, %d cores, maxHeap %dMB%n", System.getProperty("os.name"),
            System.getProperty("os.arch"), System.getProperty("java.version"),
            Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20);
    }

    static Result run(String url, String mode, int conc, int duration, int warmup, int keys, double missRatio,
                      String sameKey, String cacheStats, String originStats) throws Exception {
        long start = System.nanoTime();
        long measureFrom = start + warmup * 1_000_000_000L;
        long end = measureFrom + duration * 1_000_000_000L;
        long[][] lat = new long[conc][];
        int[] count = new int[conc];
        long[] errors = new long[conc];
        Thread[] threads = new Thread[conc];
        // 워밍업이 없으면 첫 요청보다 먼저 기준값을 잡아야 한다 (시나리오 E: 첫 순간의 원본 호출을 세야 한다)
        long[] cs0 = warmup == 0 ? stats(cacheStats, "hits", "misses") : null;
        long[] os0 = warmup == 0 ? stats(originStats, "calls") : null;
        for (int t = 0; t < conc; t++) {
            int id = t;
            lat[t] = new long[1024];
            threads[t] = Thread.ofVirtual().start(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (true) {
                    long s = System.nanoTime();
                    if (s >= end) return;
                    String key = sameKey != null ? sameKey
                        : rnd.nextDouble() < missRatio ? "u" + UNIQUE.incrementAndGet() + "-" + start
                        : Integer.toString(rnd.nextInt(keys));
                    boolean ok;
                    try {
                        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + key));
                        if (mode.equals("put")) b.PUT(HttpRequest.BodyPublishers.ofString("0123456789abcdef"));
                        int code = HTTP.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                        ok = code == 200 || code == 404;
                        if (!ok) ERROR_KINDS.computeIfAbsent("HTTP " + code, k -> new AtomicLong()).incrementAndGet();
                    } catch (Exception e) {
                        ok = false;
                        ERROR_KINDS.computeIfAbsent(e.getClass().getSimpleName() + ": " + e.getMessage(), k -> new AtomicLong())
                            .incrementAndGet();
                    }
                    long e = System.nanoTime();
                    if (s < measureFrom) continue; // 워밍업 구간은 버린다
                    if (!ok) errors[id]++;
                    if (count[id] == lat[id].length) lat[id] = Arrays.copyOf(lat[id], count[id] * 2);
                    lat[id][count[id]++] = e - s;
                }
            });
        }
        sleepUntil(measureFrom);
        if (warmup > 0) {
            cs0 = stats(cacheStats, "hits", "misses");
            os0 = stats(originStats, "calls");
        }
        for (Thread t : threads) t.join();
        long[] cs1 = stats(cacheStats, "hits", "misses"), os1 = stats(originStats, "calls");

        int total = 0;
        for (int c : count) total += c;
        long[] all = new long[total];
        int p = 0;
        for (int t = 0; t < conc; t++) {
            System.arraycopy(lat[t], 0, all, p, count[t]);
            p += count[t];
        }
        Arrays.sort(all);
        long err = 0;
        for (long e : errors) err += e;
        Double hit = null;
        if (cs0 != null && cs1 != null) {
            long h = cs1[0] - cs0[0], m = cs1[1] - cs0[1];
            hit = h + m == 0 ? 0.0 : (double) h / (h + m);
        }
        Long oc = os0 != null && os1 != null ? os1[0] - os0[0] : null;
        return new Result(total, err, total / (double) duration, pct(all, 0.50), pct(all, 0.95), pct(all, 0.99), hit, oc);
    }

    private static double pct(long[] sorted, double q) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)] / 1e6;
    }

    private static long[] stats(String url, String... fields) {
        if (url == null) return null;
        try {
            String body = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
            long[] out = new long[fields.length];
            for (int i = 0; i < fields.length; i++) {
                Matcher m = Pattern.compile("\"" + fields[i] + "\":(\\d+)").matcher(body);
                if (!m.find()) return null;
                out[i] = Long.parseLong(m.group(1));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void sleepUntil(long nanoTime) throws InterruptedException {
        long ms = (nanoTime - System.nanoTime()) / 1_000_000;
        if (ms > 0) Thread.sleep(ms);
    }

    private static void print(String label, Result r) {
        System.out.printf("%-7s requests=%d errors=%d rps=%.1f p50=%.2fms p95=%.2fms p99=%.2fms%s%s%n", label,
            r.requests, r.errors, r.rps, r.p50, r.p95, r.p99,
            r.hitRate == null ? "" : String.format(" hitRate=%.1f%%", r.hitRate * 100),
            r.originCalls == null ? "" : " originCalls=" + r.originCalls);
        if (!ERROR_KINDS.isEmpty()) {
            System.out.println("        errors by kind (워밍업 포함): " + ERROR_KINDS);
            ERROR_KINDS.clear();
        }
    }
}
