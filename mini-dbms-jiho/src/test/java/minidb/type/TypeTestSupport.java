package minidb.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import minidb.sql.Ast.Assignment;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Ast.Insert;
import minidb.sql.Ast.Literal;
import minidb.sql.Ast.Update;
import minidb.sql.Parser;
import minidb.sql.SqlException;
import org.junit.jupiter.api.function.Executable;

/** 테스트 입력은 진짜 파서로 만든다: 에러 위치(line, col)가 파서가 준 AST 위치와 같은지까지 보려고 */
final class TypeTestSupport {

    private TypeTestSupport() {
    }

    /** "CREATE TABLE t (" + cols + ");" */
    static Schema schema(String cols) {
        return Schema.define((CreateTable) Parser.parse("CREATE TABLE t (" + cols + ");").get(0));
    }

    /** "INSERT INTO t VALUES " + row + ";"의 첫 행 */
    static List<Literal> values(String row) {
        return ((Insert) Parser.parse("INSERT INTO t VALUES " + row + ";").get(0)).rows().get(0);
    }

    static Literal literal(String value) {
        return values("(" + value + ")").get(0);
    }

    /** "UPDATE t SET " + sets + ";" */
    static List<Assignment> sets(String sets) {
        return ((Update) Parser.parse("UPDATE t SET " + sets + ";").get(0)).sets();
    }

    static void assertError(Executable run, int line, int col, String part) {
        SqlException e = assertThrows(SqlException.class, run);
        assertEquals(line, e.line(), e.getMessage());
        assertEquals(col, e.col(), e.getMessage());
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }
}
