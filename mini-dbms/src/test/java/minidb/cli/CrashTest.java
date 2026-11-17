package minidb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import minidb.engine.Database;
import minidb.engine.Result;
import minidb.sql.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 진짜로 프로세스를 죽인다: 문장 하나가 여러 페이지를 건드려도 전부-아니면-없음이어야 한다 */
class CrashTest {

    @TempDir
    Path dir;

    @Test
    @Timeout(60)
    void killedMidUpdateLeavesAllRowsWithSameValue() throws Exception {
        Path data = dir.resolve("data");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CrashChild.class.getName(), data.toString()).redirectErrorStream(true).start();
        int last = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.equals("READY")) continue;
                if (!line.matches("\\d+")) throw new AssertionError("자식 출력: " + line);
                last = Integer.parseInt(line);
                if (last >= 30) break;
            }
        } finally {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
        assertTrue(last >= 30, "자식이 커밋을 충분히 못 함: " + last);
        assertTrue(Files.size(data.resolve("t.db")) / 8192 >= 4, "3페이지 이상 데이터가 아님");

        try (Database db = Database.open(data, true)) {
            Result r = db.execute(Parser.parse("SELECT v FROM t;").get(0));
            assertEquals(CrashChild.ROWS, r.rows().size());
            Set<Object> values = new HashSet<>();
            for (Object[] row : r.rows()) values.add(row[0]);
            assertEquals(1, values.size(), "행마다 v가 다름: " + List.copyOf(values));
            assertTrue((Integer) values.iterator().next() >= last, "이미 출력된 커밋이 사라짐");
        }
    }
}
