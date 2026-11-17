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
    void operatorBoundariesAreFixedByTests() {
        assertEquals(List.of(IDENT, LT, GT, IDENT, EOF), types("a<  >b")); // 공백이 끼면 <> 가 아님
        assertEquals(List.of(LE, GT, EOF), types("<=>")); // MySQL의 <=> 는 없음: <= 다음 >
        assertEquals(List.of(NE, EQ, EOF), types("!==")); // != 다음 =
        assertEquals(List.of(SEMICOLON, SEMICOLON, EOF), types(";;")); // 빈 문장은 파서가 판단
    }

    @Test
    void backslashIsAPlainCharacterInStrings() {
        // ANSI 방식: 이스케이프는 ''만. 'a\' 는 문자열 a\ 로 닫힘 (MySQL 기본이면 안 닫힌 따옴표)
        List<Token> ts = Lexer.tokenize("'a\\' x");
        assertEquals(List.of(STRING, IDENT, EOF), ts.stream().map(Token::type).toList());
        assertEquals("a\\", ts.get(0).text());
        assertEquals("50\\%", Lexer.tokenize("'50\\%'").get(0).text());
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

    private static Token tokenAt(String sql, int index) {
        return Lexer.tokenize(sql).get(index);
    }

    private static void assertPos(Token t, int line, int col) {
        assertEquals(line + ":" + col, t.line() + ":" + t.col(), t.toString());
    }

    private static void assertError(String sql, int line, int col, String messagePart) {
        SqlException e = assertThrows(SqlException.class, () -> Lexer.tokenize(sql), sql);
        assertEquals(line + ":" + col, e.line() + ":" + e.col(), e.getMessage());
        assertTrue(e.getMessage().startsWith(line + ":" + col + ": "), e.getMessage());
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    @Test
    void positionsAreLineAndColumn() {
        List<Token> ts = Lexer.tokenize("SELECT *\n  FROM t");
        assertEquals(2, ts.get(2).line());
        assertEquals(3, ts.get(2).col());
    }

    @Test
    void positionAfterStringWithNewline() {
        List<Token> ts = Lexer.tokenize("'a\nb' x");
        assertEquals("a\nb", ts.get(0).text()); // 줄바꿈이 든 문자열 값이 그대로 보존됨
        assertPos(ts.get(0), 1, 1);
        assertPos(ts.get(1), 2, 4);
    }

    @Test
    void tabCountsAsOneColumn() {
        assertPos(tokenAt("\tx", 0), 1, 2);
    }

    @Test
    void crlfIsOneLineBreak() {
        assertPos(tokenAt("a\r\nb", 1), 2, 1);
        assertPos(tokenAt("SELECT\r\n\r\n  *", 1), 3, 3);
    }

    @Test
    void positionAfterTwoCharacterOperator() {
        assertPos(tokenAt("<= x", 1), 1, 4);
        assertPos(tokenAt("a<>b", 2), 1, 4);
        assertPos(tokenAt("a!=b", 2), 1, 4);
    }

    @Test
    void eofPosition() {
        assertPos(tokenAt("", 0), 1, 1);
        assertPos(tokenAt("SELECT *\nFROM", 3), 2, 5);
        assertPos(tokenAt("a\n", 1), 2, 1);
    }

    @Test
    void columnsCountCodePointsNotUtf16Units() {
        assertPos(tokenAt("'\uD83D\uDE00' x", 1), 1, 5); // 😀 는 한 칸
        assertPos(tokenAt("\uD835\uDC00b", 0), 1, 1); // 𝐀(수학 굵은 A)는 글자라서 IDENT
        assertEquals("\uD83D\uDE00", tokenAt("'\uD83D\uDE00'", 0).text());
    }

    @Test
    void unknownNonAsciiCharacterShowsCodePoint() {
        assertError("a \uD83D\uDE00 b", 1, 3, "U+1F600");
        assertError("x\n\u200B", 2, 1, "U+200B"); // 안 보이는 글자
        assertError("\u201Cabc\u201D", 1, 1, "U+201C"); // 스마트 따옴표
    }

    @Test
    void nbspIsWhitespace() {
        List<Token> ts = Lexer.tokenize("SELECT\u00A0*");
        assertEquals(List.of(KEYWORD, STAR, EOF), ts.stream().map(Token::type).toList());
        assertPos(ts.get(1), 1, 8);
    }

    @Test
    void unclosedQuoteReportsWhereItOpened() {
        SqlException e = assertThrows(SqlException.class, () -> Lexer.tokenize("SELECT *\nWHERE name = 'kim"));
        assertTrue(e.getMessage().startsWith("2:14:"), e.getMessage());
        assertTrue(e.getMessage().contains("닫히지 않은 따옴표"));
        assertEquals(2, e.line());
        assertEquals(14, e.col());
    }

    @Test
    void unknownCharacterReportsPosition() {
        assertError("SELECT # FROM t", 1, 8, "모르는 글자: '#'");
        assertError("a ! b", 1, 3, "모르는 글자: '!'");
        assertError("\"double\"", 1, 1, "모르는 글자: '\"'");
        assertError("SELECT *\n  # x", 2, 3, "모르는 글자: '#'"); // 두 번째 줄
    }

    @Test
    void numberGluedToLettersIsAnError() {
        assertError("12abc", 1, 1, "숫자 뒤에 글자");
        assertError("x\n  12abc", 2, 3, "숫자 뒤에 글자");
    }

    @Test
    void sqlExceptionCarriesPosition() {
        SqlException withPos = new SqlException(3, 7, "boom");
        assertEquals("3:7: boom", withPos.getMessage());
        assertEquals(3, withPos.line());
        assertEquals(7, withPos.col());

        SqlException fromToken = new SqlException(new Token(IDENT, "x", 4, 2), "boom");
        assertEquals("4:2: boom", fromToken.getMessage());
        assertEquals(4, fromToken.line());
        assertEquals(2, fromToken.col());

        SqlException noPos = new SqlException("boom");
        assertEquals("boom", noPos.getMessage());
        assertEquals(0, noPos.line());
        assertEquals(0, noPos.col());
    }

    @Test
    void emptyInputIsJustEof() {
        assertEquals(List.of(EOF), types(""));
        assertEquals(List.of(EOF), types("   \n\t"));
    }
}
