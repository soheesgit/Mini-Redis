package minidb.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import minidb.sql.Parser;

/** 실제 SQL과 페이지/WAL 경로를 사용하는 수동 벤치마크. */
public final class IndexBenchmark {

    private static final int ROWS = 1_000_000;
    private static final int BATCH = 1_000;
    private static final int WARMUP = 10;
    private static final int SAMPLES = 31;
    private static final long SEED = 42;

    private IndexBenchmark() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || (!args[0].equals("on") && !args[0].equals("off"))) {
            throw new IllegalArgumentException("Usage: IndexBenchmark on|off");
        }
        boolean fsync = args[0].equals("on");
        Path dir = Files.createTempDirectory("minidb-index-bench-");
        Path table = dir.resolve("users.db");
        try {
            long insert;
            try (Database db = Database.open(dir, fsync)) {
                run(db, "CREATE TABLE users (name VARCHAR(32), email VARCHAR(64))");
                long start = System.nanoTime();
                for (int first = 1; first <= ROWS; first += BATCH) {
                    StringBuilder sql = new StringBuilder("INSERT INTO users VALUES ");
                    int end = Math.min(first + BATCH, ROWS + 1);
                    for (int id = first; id < end; id++) {
                        if (id > first) sql.append(',');
                        sql.append("('user").append(id).append("','").append(email(id)).append("')");
                    }
                    if (run(db, sql.toString()).affected() != end - first) {
                        throw new AssertionError("Wrong batch row count at id=" + first);
                    }
                }
                insert = System.nanoTime() - start;
            }

            long start = System.nanoTime();
            try (Database db = Database.open(dir, fsync)) {
                // Table 열기와 인덱스 빌드는 해당 테이블의 첫 접근에서 수행된다.
                if (run(db, "SELECT id FROM users WHERE id = 0").affected() != 0) {
                    throw new AssertionError("Unexpected row for id=0");
                }
                long reopen = System.nanoTime() - start;
                long stored = run(db, "SELECT id FROM users").affected();
                if (stored != ROWS) throw new AssertionError("Wrong persisted row count: " + stored);
                System.out.printf(Locale.ROOT, "fsync=%s rows=%,d batch=%,d file=%,d bytes%n",
                        args[0], stored, BATCH, Files.size(table));
                System.out.printf(Locale.ROOT, "insert %.3f s, reopen + first lookup %.3f s%n", insert / 1e9, reopen / 1e9);
                benchmarkQueries(db);
            }
        } finally {
            Files.deleteIfExists(dir.resolve("users.wal"));
            Files.deleteIfExists(dir.resolve("users.db.tmp"));
            Files.deleteIfExists(table);
            Files.deleteIfExists(dir);
        }
    }

    private static void benchmarkQueries(Database db) throws IOException {
        long[] indexed = new long[SAMPLES];
        long[] scanned = new long[SAMPLES];
        Random targets = new Random(SEED);
        for (int i = -WARMUP; i < SAMPLES; i++) {
            int id = targets.nextInt(ROWS) + 1;
            String idQuery = "SELECT * FROM users WHERE id = " + id;
            String emailQuery = "SELECT * FROM users WHERE email = '" + email(id) + "'";
            long indexTime, scanTime;
            if (i % 2 == 0) {
                indexTime = measure(db, idQuery, id);
                scanTime = measure(db, emailQuery, id);
            } else {
                scanTime = measure(db, emailQuery, id);
                indexTime = measure(db, idQuery, id);
            }
            if (i >= 0) {
                indexed[i] = indexTime;
                scanned[i] = scanTime;
            }
        }
        System.out.printf(Locale.ROOT, "warmup=%d samples=%d per path seed=%d%n", WARMUP, SAMPLES, SEED);
        report("id index", indexed);
        report("email scan", scanned);
    }

    private static Result run(Database db, String sql) throws IOException {
        return db.execute(Parser.parse(sql + ";").getFirst());
    }

    private static long measure(Database db, String sql, int id) throws IOException {
        long start = System.nanoTime();
        Result result = run(db, sql);
        long elapsed = System.nanoTime() - start;
        Object[] expected = {id, "user" + id, email(id)};
        if (result.rows().size() != 1 || !Arrays.equals(expected, result.rows().getFirst())) {
            throw new AssertionError("Wrong lookup result for " + sql);
        }
        return elapsed;
    }

    private static String email(int id) {
        return "user" + id + "@example.com";
    }

    private static void report(String label, long[] samples) {
        Arrays.sort(samples);
        int p95 = (int) Math.ceil(samples.length * 0.95) - 1;
        System.out.printf(Locale.ROOT, "%s: median %.3f µs, p95 %.3f µs%n",
                label, samples[samples.length / 2] / 1_000.0, samples[p95] / 1_000.0);
    }
}
