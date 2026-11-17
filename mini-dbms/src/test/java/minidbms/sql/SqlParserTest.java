package minidbms.sql;

import java.util.List;

/** 파일을 다루기 전에 SQL이 어떤 문장 객체와 값으로 바뀌는지 검증한다. */
public final class SqlParserTest {
    private SqlParserTest() {}

    public static void main(String[] args) {
        // 세미콜론과 주석 기호가 문자열 안에도 등장하는 입력으로 렉서가 문맥을 구분하는지 확인한다.
        List<Statement> parsed = SqlParser.parse("""
                -- A comment with a quoted string and a semicolon: 'skip';
                InSeRt INTO USERS (EMAIL, NAME) VALUES ('x@example.com', '한글; O''Brien
                -- still part of the string');
                SeLeCt name, ID FROM Users WHERE id = -9223372036854775808;
                SELECT * FROM users
                """);
        assertEquals(List.of(
                new Statement.Insert("users", List.of("email", "name"),
                        List.of("x@example.com", "한글; O'Brien\n-- still part of the string")),
                new Statement.Select("users", List.of("name", "id"),
                        List.of(new Statement.Condition("id", Statement.Operator.EQ, Long.MIN_VALUE))),
                new Statement.Select("users", List.of(), List.of())), parsed);

        assertEquals(List.of(), SqlParser.parse("  ; ;; -- no statements\r\n ;"));
        assertEquals(List.of(new Statement.Select("users", List.of("id"),
                        List.of(new Statement.Condition("email", Statement.Operator.EQ, "")))),
                SqlParser.parse("SELECT id FROM users WHERE email = '';"));

        // 지원하지 않는 문법, 빠진 구분자, 닫히지 않은 문자열, 정수 범위 초과를 검사한다.
        rejects("CREATE TABLE users;", "Expected INSERT, SELECT, UPDATE or DELETE at character 1.");
        rejects("SELECT * FROM users SELECT id FROM users;", "Expected ';'");
        rejects("SELECT * FROM users WHERE name = 'unfinished", "Unterminated string");
        rejects("SELECT * FROM users WHERE id = 9223372036854775808;", "outside the BIGINT range");
        rejects("SELECT * FROM users WHERE id = -9223372036854775809;", "outside the BIGINT range");
        rejects("SELECT name, FROM users;", "Expected FROM");
        rejects("SELECT * FROM users WHERE id @ 1;", "Unexpected character");

        assertEquals(List.of(
                new Statement.Update("users", "name", "O'Brien", 2),
                new Statement.Delete("users", 3),
                new Statement.Select("users", List.of("id"), List.of(
                        new Statement.Condition("id", Statement.Operator.GE, 2L),
                        new Statement.Condition("name", Statement.Operator.NE, ""),
                        new Statement.Condition("id", Statement.Operator.LT, 4L)))),
                SqlParser.parse("""
                        UPDATE USERS SET NAME = 'O''Brien' WHERE ID = 2;
                        DELETE FROM users WHERE id = 3;
                        SELECT id FROM users WHERE id >= 2 AND name <> '' AND id < 4;
                        """));
        rejects("UPDATE users SET name = 'x';", "Expected WHERE");
        rejects("DELETE FROM users WHERE name = 'x';", "Expected ID");
        rejects("DELETE FROM users WHERE id > 1;", "Expected '='");
        rejects("DELETE FROM users WHERE id = '1';", "Expected an integer ID");
        rejects("SELECT id FROM users WHERE id >= 1 AND;", "Expected an identifier");

        System.out.println("Passed 16 parser checks.");
    }

    private static void rejects(String sql, String expectedMessage) {
        try {
            SqlParser.parse(sql);
        } catch (SqlException exception) {
            if (exception.getMessage().contains(expectedMessage)) {
                return;
            }
            throw new AssertionError("Unexpected message for: " + sql, exception);
        }
        throw new AssertionError("Should have rejected: " + sql);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
