package example.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import miniredis.store.Store;

/**
 * 엔트리 1개의 JVM 고정 비용 C를 잰다 (기획서 5.4). 키 100만 개를 넣고 힙 증가분에서 키·값 바이트를 뺀다.
 * java -Xms2g -Xmx2g -cp target/classes example.bench.MeasureEntryCost
 */
public final class MeasureEntryCost {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 1_000_000;
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        Store store = new Store(new byte[16], new SplittableRandom(1), System::nanoTime, System::currentTimeMillis, 0,
            Store.Policy.NOEVICTION);
        long before = heapAfterGc(mem);
        long payload = 0;
        byte[] set = "SET".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < n; i++) {
            byte[] k = String.format("key:%07d", i).getBytes(StandardCharsets.UTF_8);
            byte[] v = new byte[16];
            store.execute(new byte[][] {set, k, v});
            payload += k.length + v.length;
        }
        store.rehashFor(5_000_000_000L); // 리해싱 중인 옛 테이블이 남지 않게 (최대 5초)
        long after = heapAfterGc(mem);
        double perEntry = (after - before) / (double) n;
        System.out.printf("entries=%d heapDelta=%dMB perEntry=%.1fB payload/entry=%.1fB → C ≈ %.0f%n",
            n, (after - before) >> 20, perEntry, payload / (double) n, perEntry - payload / (double) n);
        System.out.println("store estimate memory=" + store.stats().get("memory") + " (C=" + Store.ENTRY_OVERHEAD + " 기준)");
    }

    private static long heapAfterGc(MemoryMXBean mem) throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        return mem.getHeapMemoryUsage().getUsed();
    }
}
