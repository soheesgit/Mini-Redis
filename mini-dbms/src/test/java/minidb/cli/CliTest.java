package minidb.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    @TempDir
    Path dir;

    private record Out(int code, String out, String err) {}

    private Out run(String sql, String... extra) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(), e = new ByteArrayOutputStream();
        String[] args = new String[extra.length + 2];
        args[0] = "--data-dir";
        args[1] = dir.resolve("data").toString();
        System.arraycopy(extra, 0, args, 2, extra.length);
        int code = Main.run(args, new StringReader(sql), new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Out(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    @Test
    void scriptThenSecondRunSeesPersistedData() {
        Out a = run("""
                CREATE TABLE users (name VARCHAR(10) NOT NULL, born DATE, ok BOOLEAN);
                INSERT INTO users VALUES ('kim', '2000-01-02', TRUE), ('lee', NULL, FALSE), ('park', '1999-12-31', NULL);
                UPDATE users SET name = 'kim2' WHERE id = 1;
                DELETE FROM users WHERE name = 'park';
                """, "--fsync=off");
        assertEquals(0, a.code(), a.err());
        assertEquals("OK\nOK, 3 rows affected\nOK, 1 rows affected\nOK, 1 rows affected\n", a.out());
        Out b = run("SELECT * FROM users ORDER BY id; SELECT name FROM users WHERE id = 99;");
        assertEquals(0, b.code(), b.err());
        assertEquals("id\tname\tborn\tok\n1\tkim2\t2000-01-02\tTRUE\n2\tlee\tNULL\tFALSE\n(2 rows)\nname\n(0 rows)\n", b.out());
    }

    @Test
    void syntaxErrorExecutesNothing() {
        Out a = run("CREATE TABLE t (a INT); INSERT INTO t VALUES (1; ");
        assertEquals(1, a.code());
        assertTrue(a.err().startsWith("ERROR 1:"), a.err());
        assertEquals("", a.out());
        assertTrue(Files.notExists(dir.resolve("data").resolve("t.db"))); // 앞 문장도 실행 안 됨
    }

    @Test
    void runtimeErrorStopsButKeepsEarlierStatements() {
        Out a = run("CREATE TABLE t (a INT);\nINSERT INTO t VALUES (1);\nSELECT * FROM nope;\nINSERT INTO t VALUES (2);");
        assertEquals(1, a.code());
        assertEquals("OK\nOK, 1 rows affected\n", a.out());
        assertTrue(a.err().startsWith("ERROR 3:15: 없는 테이블: nope"), a.err());
        assertEquals("id\ta\n1\t1\n(1 rows)\n", run("SELECT * FROM t;").out());
    }

    @Test
    void readsScriptFileAndRejectsBadArguments() throws IOException {
        Path f = dir.resolve("s.sql");
        Files.writeString(f, "CREATE TABLE t (a INT); INSERT INTO t VALUES (7); SELECT a FROM t;");
        Out a = run("", f.toString());
        assertEquals(0, a.code(), a.err());
        assertEquals("OK\nOK, 1 rows affected\na\n7\n(1 rows)\n", a.out());
        assertEquals(1, run("", "--nope").code());
        assertEquals(1, run("", dir.resolve("missing.sql").toString()).code());
    }
}
