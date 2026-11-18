package minidb.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import minidb.sql.Ast.Statement;
import minidb.sql.Parser;
import minidb.sql.SqlException;
import minidb.storage.Faults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutorTest {

    @TempDir
    Path dir;

    static Result run(Database db, String sql) throws IOException {
        Result r = null;
        for (Statement s : Parser.parse(sql)) r = db.execute(s);
        return r;
    }

    /** 결과 행을 "1|a|NULL" 꼴 글자로 */
    static List<String> text(Result r) {
        return r.rows().stream().map(row -> {
            List<String> cells = new ArrayList<>();
            for (Object v : row) cells.add(String.valueOf(v));
            return String.join("|", cells);
        }).collect(Collectors.toList());
    }

    static SqlException sqlError(Database db, String sql) {
        return assertThrows(SqlException.class, () -> run(db, sql));
    }

    @Test
    void createInsertSelectRoundTrip() throws IOException {
        try (Database db = Database.open(dir, true)) {
            Result c = run(db, "CREATE TABLE Users (name VARCHAR(10) NOT NULL, age INT);");
            assertNull(c.columns());
            assertEquals("users", c.changedTable());
            Result i = run(db, "INSERT INTO users VALUES ('kim', 30), ('lee', NULL), ('park', 25);");
            assertEquals(3, i.affected());
            assertEquals("users", i.changedTable());
            Result s = run(db, "SELECT * FROM USERS;");
            assertEquals(List.of("id", "name", "age"), s.columns());
            assertEquals(List.of("1|kim|30", "2|lee|null", "3|park|25"), text(s));
            assertEquals(3, s.affected());
            assertNull(s.changedTable());
            Result p = run(db, "SELECT age, Name FROM users WHERE age >= 25 ORDER BY age;");
            assertEquals(List.of("age", "name"), p.columns());
            assertEquals(List.of("25|park", "30|kim"), text(p));
        }
    }

    @Test
    void createExistingAndUnknownNamesAreErrors() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (a INT);");
            SqlException e = sqlError(db, "CREATE TABLE T (b INT);");
            assertTrue(e.getMessage().contains("이미 있는 테이블: T"), e.getMessage());
            e = sqlError(db, "SELECT * FROM nope;");
            assertTrue(e.getMessage().contains("없는 테이블: nope"), e.getMessage());
            assertEquals(1, e.line());
            assertEquals(15, e.col());
            sqlError(db, "INSERT INTO nope VALUES (1);");
            e = sqlError(db, "SELECT zzz FROM t;");
            assertTrue(e.getMessage().contains("없는 칸: zzz"), e.getMessage());
            assertEquals(8, e.col());
            sqlError(db, "SELECT * FROM t WHERE zzz = 1;");
            sqlError(db, "SELECT * FROM t ORDER BY zzz;");
            sqlError(db, "UPDATE t SET zzz = 1;");
            sqlError(db, "DELETE FROM t WHERE zzz = 1;");
        }
    }

    @Test
    void whereErrorsEvenOnEmptyTable() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (a INT);");
            sqlError(db, "SELECT * FROM t WHERE a = 'x';");
            sqlError(db, "DELETE FROM t WHERE nope = 1;");
        }
    }

    @Test
    void insertSpanningPagesAddsNewPages() throws IOException {
        StringBuilder sql = new StringBuilder("INSERT INTO t VALUES ");
        String big = "x".repeat(200);
        for (int i = 0; i < 100; i++) sql.append(i == 0 ? "" : ", ").append("('").append(big).append("')");
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (s VARCHAR(200));");
            assertEquals(100, run(db, sql + ";").affected());
            assertEquals(100, run(db, "SELECT * FROM t;").rows().size());
        }
        long pages = Files.size(dir.resolve("t.db")) / 8192;
        assertTrue(pages >= 1 + 3, "페이지 " + pages); // 행 ~205B → 페이지당 ~39개
        try (Database db = Database.open(dir, true)) {
            assertEquals(100, run(db, "SELECT * FROM t;").rows().size());
            assertEquals(List.of("100"), text(run(db, "SELECT id FROM t ORDER BY id DESC LIMIT 1;")));
        }
    }

    @Test
    void reopenKeepsDataAndNextIdIsMaxPlusOne() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (a INT);");
            run(db, "INSERT INTO t VALUES (10), (20), (30);");
            run(db, "DELETE FROM t WHERE id = 3;");
        }
        try (Database db = Database.open(dir, true)) {
            assertEquals(List.of("1|10", "2|20"), text(run(db, "SELECT * FROM t;")));
            run(db, "INSERT INTO t VALUES (40);");
            assertEquals(List.of("1|10", "2|20", "3|40"), text(run(db, "SELECT * FROM t;")));
        }
    }

    @Test
    void emptyTableStartsAtIdOne() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (a INT);");
            assertEquals(0, run(db, "SELECT * FROM t;").rows().size());
        }
        try (Database db = Database.open(dir, true)) {
            run(db, "INSERT INTO t VALUES (7);");
            assertEquals(List.of("1|7"), text(run(db, "SELECT * FROM t;")));
        }
    }

    @Test
    void updateGrowingVarcharMovesRowsAcrossPages() throws IOException {
        String grown = "y".repeat(1000);
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (s VARCHAR(1500), k INT);");
            StringBuilder sql = new StringBuilder("INSERT INTO t VALUES ");
            for (int i = 1; i <= 30; i++) sql.append(i == 1 ? "" : ", ").append("('s', ").append(i).append(")");
            run(db, sql + ";");
            Result u = run(db, "UPDATE t SET s = '" + grown + "' WHERE k <= 20;");
            assertEquals(20, u.affected());
            assertEquals("t", u.changedTable());
            Result all = run(db, "SELECT k, s FROM t ORDER BY k;");
            assertEquals(30, all.rows().size());
            for (int i = 0; i < 30; i++) {
                assertEquals(i + 1, all.rows().get(i)[0]);
                assertEquals(i < 20 ? grown : "s", all.rows().get(i)[1]);
            }
        }
        assertTrue(Files.size(dir.resolve("t.db")) / 8192 >= 1 + 3); // 20KB 넘게 커졌다
        try (Database db = Database.open(dir, true)) {
            assertEquals(30, run(db, "SELECT * FROM t;").rows().size());
            assertEquals(20, run(db, "SELECT * FROM t WHERE s = '" + grown + "';").rows().size());
        }
    }

    @Test
    void updateKeepsIdAndShrinksInPlace() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (s VARCHAR(50), n INT);");
            run(db, "INSERT INTO t VALUES ('hello world', 1), ('b', 2);");
            assertEquals(1, run(db, "UPDATE t SET s = 'hi', n = NULL WHERE id = 1;").affected());
            assertEquals(List.of("1|hi|null", "2|b|2"), text(run(db, "SELECT * FROM t;")));
            assertEquals(0, run(db, "UPDATE t SET n = 5 WHERE n = 99;").affected());
            assertEquals(2, run(db, "UPDATE t SET n = 5;").affected());
        }
    }

    @Test
    void invalidStatementsChangeNothing() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (n INT NOT NULL, s VARCHAR(3));");
            run(db, "INSERT INTO t VALUES (1, 'a'), (2, 'b');");
            sqlError(db, "UPDATE t SET n = 'x';");
            sqlError(db, "UPDATE t SET n = NULL;");
            sqlError(db, "UPDATE t SET s = 'toolong';");
            sqlError(db, "UPDATE t SET id = 5;");
            sqlError(db, "INSERT INTO t VALUES (3, 'c'), (4, 'dddd');");
            sqlError(db, "INSERT INTO t VALUES (5);");
            assertEquals(List.of("1|1|a", "2|2|b"), text(run(db, "SELECT * FROM t;")));
            run(db, "INSERT INTO t VALUES (9, 'z');");
            assertEquals("3|9|z", text(run(db, "SELECT * FROM t WHERE n = 9;")).get(0)); // 실패한 INSERT가 id를 쓰지 않음
        }
    }

    @Test
    void deleteThenSelect() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (n INT);");
            run(db, "INSERT INTO t VALUES (1), (2), (3), (4), (5);");
            Result d = run(db, "DELETE FROM t WHERE n > 3;");
            assertEquals(2, d.affected());
            assertEquals("t", d.changedTable());
            assertEquals(List.of("1|1", "2|2", "3|3"), text(run(db, "SELECT * FROM t;")));
            assertEquals(0, run(db, "DELETE FROM t WHERE n = 99;").affected());
            assertEquals(3, run(db, "DELETE FROM t;").affected());
            assertEquals(0, run(db, "SELECT * FROM t;").rows().size());
        }
        try (Database db = Database.open(dir, true)) {
            assertEquals(0, run(db, "SELECT * FROM t;").rows().size());
        }
    }

    @Test
    void orderByAndLimitWithNulls() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (a INT, b VARCHAR(5));");
            run(db, "INSERT INTO t VALUES (3, 'x'), (NULL, 'y'), (1, 'z'), (2, 'x'), (1, 'a');");
            assertEquals(List.of("null", "1", "1", "2", "3"), firstCol(run(db, "SELECT a FROM t ORDER BY a;")));
            assertEquals(List.of("3", "2", "1", "1", "null"), firstCol(run(db, "SELECT a FROM t ORDER BY a DESC;")));
            assertEquals(List.of("5|1|a", "3|1|z", "4|2|x"),
                    text(run(db, "SELECT * FROM t WHERE a IS NOT NULL ORDER BY a, b LIMIT 3;")));
            assertEquals(List.of("1|z", "null|y", "3|x"),
                    text(run(db, "SELECT a, b FROM t ORDER BY b DESC, a DESC LIMIT 3;")));
            assertEquals(List.of("null", "1"), firstCol(run(db, "SELECT a FROM t ORDER BY a LIMIT 2;")));
            assertEquals(0, run(db, "SELECT a FROM t LIMIT 0;").rows().size());
            assertEquals(5, run(db, "SELECT a FROM t LIMIT 100;").rows().size());
            // 같은 키는 저장 순서 유지 (안정 정렬)
            assertEquals(List.of("1|x", "4|x"), text(run(db, "SELECT id, b FROM t WHERE b = 'x' ORDER BY b;")));
        }
    }

    static List<String> firstCol(Result r) {
        return r.rows().stream().map(row -> String.valueOf(row[0])).toList();
    }

    @Test
    void tableFileNamesAreEncoded() throws IOException {
        assertEquals("t_1", Database.fileBase("t_1"));
        assertEquals("%EA%B0%80", Database.fileBase("가"));
        assertEquals("a%2E%2E%2Fb", Database.fileBase("a../b"));
    }

    @Test
    void brokenTableIsDroppedAndRecoveredOnNextAccess() throws IOException {
        boolean[] armed = {false};
        Faults f = (p, i) -> {
            if (armed[0] && p.equals("data.write") && i == 1) throw new IOException("끊김");
        };
        try (Database db = Database.open(dir, true, f)) {
            run(db, "CREATE TABLE t (s VARCHAR(1500));");
            run(db, "INSERT INTO t VALUES ('a');");
            armed[0] = true;
            // 두 페이지를 건드리는 문장: 1번 페이지를 채우고 새 페이지 추가 (둘째 페이지 쓰기에서 끊김)
            StringBuilder sql = new StringBuilder("INSERT INTO t VALUES ('z')");
            for (int i = 0; i < 6; i++) sql.append(", ('").append("z".repeat(1500)).append("')");
            assertThrows(IOException.class, () -> run(db, sql + ";"));
            armed[0] = false;
            // 다음 접근: 테이블을 다시 열며 복구 → 문장이 통째로 적용돼 있다
            assertEquals(8, run(db, "SELECT * FROM t;").rows().size());
            run(db, "INSERT INTO t VALUES ('b');");
            assertEquals(9, run(db, "SELECT * FROM t;").rows().size());
        }
    }
}
