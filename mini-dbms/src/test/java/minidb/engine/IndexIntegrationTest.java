package minidb.engine;

import static minidb.engine.ExecutorTest.run;
import static minidb.engine.ExecutorTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Ast.Select;
import minidb.sql.Parser;
import minidb.sql.SqlException;
import minidb.type.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void pointAndRangePlansMatchTheScanWithIdenticalPredicates() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (k INT);");
            assertEquals(List.of(), text(run(db, "SELECT id FROM t WHERE id = 1;")));
            StringBuilder rows = new StringBuilder("INSERT INTO t VALUES (1)");
            for (int i = 2; i <= 150; i++) rows.append(", (").append(i).append(')');
            run(db, rows.append(';').toString());

            for (String where : List.of("id = 65", "65 = ID", "id = 66.0", "id < 65", "id <= 65",
                    "id > 65", "id >= 65", "65 < id", "65 <= id", "65 > id", "65 >= id",
                    "id BETWEEN 63 AND 67", "id BETWEEN 67 AND 63", "id = 200",
                    "id > 2147483647", "id < -2147483648")) {
                String select = "SELECT id FROM t WHERE ";
                assertEquals(text(run(db, select + "(" + where + ") AND TRUE;")),
                        text(run(db, select + where + ";")), where);
            }
            assertEquals(List.of("65|65"), text(run(db, "SELECT * FROM t WHERE 65 = ID;")));
            assertEquals(List.of("63", "64", "65", "66"),
                    text(run(db, "SELECT id FROM t WHERE id BETWEEN 63 AND 67 ORDER BY id LIMIT 4;")));
            assertEquals(List.of("2", "1"), text(run(db, "SELECT id FROM t WHERE id < 2.5 ORDER BY id DESC;")));
            assertEquals(List.of(), text(run(db, "SELECT id FROM t WHERE id = NULL;")));
            assertEquals(150, run(db, "SELECT id FROM t WHERE id < 2147483648;").affected());
            assertThrows(SqlException.class, () -> run(db, "SELECT id FROM t WHERE id = '65';"));
        }
    }

    @Test
    void plannerUsesClosedIntBoundsAndLeavesOtherConditionsToScan() {
        Schema schema = Schema.define((CreateTable) Parser.parse("CREATE TABLE t (k INT);").getFirst());
        assertEquals(new Where.IdRange(65, 65), where("65 = ID", schema).idRange());
        assertEquals(new Where.IdRange(66, Integer.MAX_VALUE), where("65 < id", schema).idRange());
        assertEquals(new Where.IdRange(Integer.MIN_VALUE, 64), where("65 > id", schema).idRange());
        assertEquals(new Where.IdRange(1, 3), where("id BETWEEN 1 AND 3", schema).idRange());
        assertEquals(new Where.IdRange(2147483648L, Integer.MAX_VALUE),
                where("id > 2147483647", schema).idRange());
        for (String condition : List.of("id NOT BETWEEN 1 AND 3", "id = 1 AND TRUE",
                "id != 2", "id < 2.5", "id = NULL", "id = k", "k = 1", "id = 2147483648")) {
            assertNull(where(condition, schema).idRange(), condition);
        }
    }

    @Test
    void ascendingIdLimitCountsOnlyRowsThatPassWhere() throws IOException {
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (k INT);");
            run(db, "INSERT INTO t VALUES (0), (NULL), (30), (0), (50), (60);");

            assertEquals(List.of("1", "2"), text(run(db, "SELECT id FROM t ORDER BY id LIMIT 2;")));
            assertEquals(List.of("30", "50"),
                    text(run(db, "SELECT k FROM t WHERE k > 0 ORDER BY ID ASC LIMIT 2;")));
            assertEquals(List.of("3", "5", "6"),
                    text(run(db, "SELECT id FROM t WHERE k > 0 ORDER BY id LIMIT 9223372036854775807;")));
            assertEquals(List.of(),
                    text(run(db, "SELECT id FROM t WHERE id > 2147483647 ORDER BY id LIMIT 1;")));
            assertEquals(List.of(),
                    text(run(db, "SELECT id FROM t WHERE id BETWEEN 4 AND 3 ORDER BY id LIMIT 1;")));
            assertEquals(List.of(), text(run(db, "SELECT id FROM t ORDER BY id LIMIT 0;")));
            assertThrows(SqlException.class, () -> run(db, "SELECT missing FROM t ORDER BY id LIMIT 0;"));
        }
    }

    @Test
    void movedRowsKeepTheirIdAndPhysicalOrderAcrossReopen() throws IOException {
        String big = "x".repeat(1500);
        try (Database db = Database.open(dir, true)) {
            run(db, "CREATE TABLE t (s VARCHAR(1500), k INT);");
            StringBuilder rows = new StringBuilder("INSERT INTO t VALUES ('s', 1)");
            for (int i = 2; i <= 7; i++) rows.append(", ('").append(big).append("', ").append(i).append(')');
            run(db, rows.append(';').toString());
            assertEquals(1, run(db, "UPDATE t SET s = '" + big + "' WHERE 1 = id;").affected());
            assertEquals(List.of("1|" + big), text(run(db, "SELECT id, s FROM t WHERE id = 1;")));
            assertEquals(List.of("2", "3", "4", "5", "6", "7", "1"),
                    text(run(db, "SELECT id FROM t WHERE id BETWEEN 1 AND 7;")));
            assertEquals(List.of("1", "2"), text(run(db, "SELECT id FROM t ORDER BY id LIMIT 2;")));

            assertEquals(2, run(db, "UPDATE t SET k = 14 WHERE id BETWEEN 2 AND 3;").affected());
            assertEquals(List.of("2", "3"), text(run(db, "SELECT id FROM t WHERE k = 14;")));
            assertEquals(2, run(db, "DELETE FROM t WHERE id BETWEEN 2 AND 3;").affected());
            assertEquals(List.of(), text(run(db, "SELECT id FROM t WHERE id = 2;")));
        }
        try (Database db = Database.open(dir, true)) {
            assertEquals(List.of("1|" + big), text(run(db, "SELECT id, s FROM t WHERE id = 1;")));
            assertEquals(List.of(), text(run(db, "SELECT id FROM t WHERE id = 2;")));
            assertEquals(List.of("4", "5", "6", "7", "1"), text(run(db, "SELECT id FROM t WHERE id BETWEEN 1 AND 7;")));
            assertEquals(List.of("1", "4"),
                    text(run(db, "SELECT id FROM t WHERE id BETWEEN 1 AND 7 ORDER BY id LIMIT 2;")));
            run(db, "INSERT INTO t VALUES ('new', 8);");
            assertEquals(List.of("8|new"), text(run(db, "SELECT id, s FROM t WHERE id = 8;")));
        }
    }

    @Test
    void walFailureKeepsOldLocationsAndRecoveryRebuildsCommittedChanges() throws IOException {
        String[] failAt = {""};
        try (Database db = Database.open(dir, true, (point, i) -> {
            if (point.equals(failAt[0])) throw new IOException("실패 주입");
        })) {
            run(db, "CREATE TABLE t (k INT);");
            run(db, "INSERT INTO t VALUES (10), (20);");
            failAt[0] = "wal.half";
            assertThrows(IOException.class, () -> run(db, "INSERT INTO t VALUES (30);"));
            assertThrows(IOException.class, () -> run(db, "DELETE FROM t WHERE id = 1;"));
            assertEquals(List.of(), text(run(db, "SELECT id FROM t WHERE id = 3;")));
            assertEquals(List.of("1|10"), text(run(db, "SELECT * FROM t WHERE id = 1;")));

            failAt[0] = "data.write";
            assertThrows(IOException.class, () -> run(db, "DELETE FROM t WHERE id = 1;"));
            failAt[0] = "";
            assertEquals(List.of(), text(run(db, "SELECT * FROM t WHERE id = 1;")));
            assertEquals(List.of("2|20"), text(run(db, "SELECT * FROM t WHERE id = 2;")));
            run(db, "INSERT INTO t VALUES (30);");
            assertEquals(List.of("3|30"), text(run(db, "SELECT * FROM t WHERE id = 3;")));
        }
    }

    private static Where where(String condition, Schema schema) {
        Select select = (Select) Parser.parse("SELECT id FROM t WHERE " + condition + ";").getFirst();
        return Where.compile(select.where(), schema, ZoneOffset.UTC);
    }
}
