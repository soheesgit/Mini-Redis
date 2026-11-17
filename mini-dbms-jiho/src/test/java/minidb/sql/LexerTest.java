package minidb.sql;

import static minidb.sql.TokenType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {

    private static List<TokenType> types(String sql) {
        return Lexer.tokenize(sql).stream().map(Token::type).toList();
    }

    private static List<String> texts(String sql) {
        return Lexer.tokenize(sql).stream().filter(t -> t.type() != EOF).map(Token::text).toList();
    }

    @Test
    void selectStatement() {
        assertEquals(List.of(KEYWORD, STAR, KEYWORD, IDENT, KEYWORD, IDENT, EQ, NUMBER, SEMICOLON, EOF),
                types("SELECT * FROM users WHERE id = 1;"));
    }

    @Test
    void keywordsAreCaseInsensitiveAndUppercased() {
        assertEquals(List.of("SELECT", "NULL", "TRUE", "FALSE", "NOT"), texts("select Null true FaLsE not"));
        assertTrue(Lexer.tokenize("null").get(0).isKeyword("NULL"));
    }

    @Test
    void everyKeywordIsRecognizedInMixedCase() {
        for (String kw : Lexer.KEYWORDS) {
            for (int parity = 0; parity < 2; parity++) {
                StringBuilder mixed = new StringBuilder();
                for (int i = 0; i < kw.length(); i++) {
                    char ch = kw.charAt(i);
                    mixed.append(i % 2 == parity ? Character.toLowerCase(ch) : ch);
                }
                List<Token> ts = Lexer.tokenize(mixed.toString());
                assertEquals(KEYWORD, ts.get(0).type(), mixed.toString());
                assertEquals(kw, ts.get(0).text(), mixed.toString());
            }
        }
    }

    @Test
    void typeNamesAreIdentifiersInAnyCase() {
        for (String name : List.of("INT", "int", "VARCHAR", "varchar", "DECIMAL", "Decimal", "DATE", "date")) {
            assertEquals(List.of(IDENT, EOF), types(name), name);
            assertEquals(List.of(name), texts(name));
        }
    }

    @Test
    void nonAsciiLookalikesOfKeywordsAreIdentifiers() {
        // ſ(U+017F)는 대문자로 S, ı(U+0131)는 I 가 된다. 그래도 키워드가 아님
        assertEquals(List.of(IDENT, IDENT, EOF), types("\u017Felect \u0131nsert"));
        assertEquals(List.of("\u017Felect", "\u0131nsert"), texts("\u017Felect \u0131nsert"));
    }

    @Test
    void identifiersKeepTheirSpellingAndTypeNamesAreIdentifiers() {
        List<Token> ts = Lexer.tokenize("Users user_name2 VARCHAR int");
        assertEquals(List.of(IDENT, IDENT, IDENT, IDENT, EOF), ts.stream().map(Token::type).toList());
        assertEquals("Users", ts.get(0).text());
        assertEquals("int", ts.get(3).text());
    }

    @Test
    void numbersStayAsWrittenText() {
        List<Token> ts = Lexer.tokenize("3.14 007 12.340");
        assertEquals(List.of(NUMBER, NUMBER, NUMBER, EOF), ts.stream().map(Token::type).toList());
        assertEquals(List.of("3.14", "007", "12.340"), texts("3.14 007 12.340"));
    }

    @Test
    void numbersMayStartOrEndWithDot() {
        assertEquals(List.of(NUMBER, NUMBER, NUMBER, EOF), types(".5 1. 0.5"));
        assertEquals(List.of(".5", "1.", "0.5"), texts(".5 1. 0.5"));
        assertEquals(List.of(IDENT, EQ, NUMBER, SEMICOLON, EOF), types("x=1.;"));
    }

    @Test
    void secondDotInNumberIsAnError() {
        for (String sql : List.of("1.5.2", "1..2", ".5.2")) {
            SqlException e = assertThrows(SqlException.class, () -> Lexer.tokenize(sql), sql);
            assertTrue(e.getMessage().startsWith("1:1: 숫자 뒤의 '.'"), e.getMessage());
        }
    }

    @Test
    void unsupportedNumberFormsStayErrors() {
        assertThrows(SqlException.class, () -> Lexer.tokenize("1e3"));
        assertThrows(SqlException.class, () -> Lexer.tokenize("0x1F"));
        SqlException e = assertThrows(SqlException.class, () -> Lexer.tokenize("+5"));
        assertTrue(e.getMessage().startsWith("1:1: 모르는 글자: '+'"), e.getMessage());
        assertThrows(SqlException.class, () -> Lexer.tokenize(".")); // 점 하나는 숫자가 아님
    }

    @Test
    void negativeNumberIsMinusThenNumber() {
        assertEquals(List.of(MINUS, NUMBER, EOF), types("-5"));
    }

    @Test
    void stringsAreUnquotedAndKeepEverythingInside() {
        List<Token> ts = Lexer.tokenize("'kim' 'a, b; (c)' '' 'it''s' '한글 SELECT'");
        assertEquals(List.of(STRING, STRING, STRING, STRING, STRING, EOF), ts.stream().map(Token::type).toList());
        assertEquals(List.of("kim", "a, b; (c)", "", "it's", "한글 SELECT"), texts("'kim' 'a, b; (c)' '' 'it''s' '한글 SELECT'"));
    }

    @Test
    void comparisonOperators() {
        assertEquals(List.of(EQ, NE, NE, LT, LE, GT, GE, EOF), types("= <> != < <= > >="));
        assertEquals(List.of(IDENT, GE, NUMBER, EOF), types("age>=20"));
    }

    @Test
    void punctuation() {
        assertEquals(List.of(LPAREN, IDENT, COMMA, IDENT, RPAREN, SEMICOLON, EOF), types("(a,b);"));
    }

    @Test
    void lineCommentsAreSkipped() {
        assertEquals(List.of(KEYWORD, STAR, EOF), types("SELECT -- 주석입니다\n*"));
        assertEquals(List.of(KEYWORD, EOF), types("SELECT --"));
        assertEquals(List.of(MINUS, MINUS, NUMBER, EOF), types("--1")); // MySQL처럼 공백 없는 --는 주석 아님
    }

    @Test
    void positionsAreLineAndColumn() {
        List<Token> ts = Lexer.tokenize("SELECT *\n  FROM t");
        assertEquals(2, ts.get(2).line());
        assertEquals(3, ts.get(2).col());
    }

    @Test
    void unclosedQuoteReportsWhereItOpened() {
        SqlException e = assertThrows(SqlException.class, () -> Lexer.tokenize("SELECT *\nWHERE name = 'kim"));
        assertTrue(e.getMessage().startsWith("2:14:"), e.getMessage());
        assertTrue(e.getMessage().contains("닫히지 않은 따옴표"));
    }

    @Test
    void unknownCharacterReportsPosition() {
        SqlException e = assertThrows(SqlException.class, () -> Lexer.tokenize("SELECT # FROM t"));
        assertTrue(e.getMessage().startsWith("1:8:"), e.getMessage());
        assertThrows(SqlException.class, () -> Lexer.tokenize("a ! b"));
        assertThrows(SqlException.class, () -> Lexer.tokenize("\"double\""));
    }

    @Test
    void numberGluedToLettersIsAnError() {
        assertThrows(SqlException.class, () -> Lexer.tokenize("12abc"));
    }

    @Test
    void emptyInputIsJustEof() {
        assertEquals(List.of(EOF), types(""));
        assertEquals(List.of(EOF), types("   \n\t"));
    }
}
