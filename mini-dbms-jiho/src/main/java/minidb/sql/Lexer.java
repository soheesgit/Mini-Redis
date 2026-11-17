package minidb.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 문자열 → 토큰 목록 (설계 1.1, 1.2).
 * 문법 검사는 하지 않는다. 글자를 단어 단위로 묶는 일만 한다. 마지막 토큰은 항상 EOF.
 */
public final class Lexer {

    static final Set<String> KEYWORDS = Set.of(
            "CREATE", "TABLE", "INSERT", "INTO", "VALUES", "SELECT", "FROM", "WHERE",
            "UPDATE", "SET", "DELETE", "AND", "OR", "NOT", "NULL", "TRUE", "FALSE",
            "IS", "BETWEEN", "IN", "LIKE", "ORDER", "BY", "ASC", "DESC", "LIMIT");

    private final String src;
    private int pos = 0;
    private int line = 1;
    private int col = 1;
    private final List<Token> out = new ArrayList<>();

    private Lexer(String src) {
        this.src = src;
    }

    public static List<Token> tokenize(String sql) {
        Lexer lx = new Lexer(sql);
        lx.run();
        return lx.out;
    }

    private void run() {
        while (true) {
            skipSpacesAndComments();
            if (pos >= src.length()) {
                out.add(new Token(TokenType.EOF, "", line, col));
                return;
            }
            int startLine = line, startCol = col;
            char c = peek(0);
            if (Character.isLetter(c) || c == '_') {
                word(startLine, startCol);
            } else if (isDigit(c)) {
                number(startLine, startCol);
            } else if (c == '\'') {
                string(startLine, startCol);
            } else {
                symbol(startLine, startCol);
            }
        }
    }

    private void skipSpacesAndComments() {
        while (pos < src.length()) {
            char c = peek(0);
            if (Character.isWhitespace(c)) {
                advance();
            } else if (c == '-' && peek(1) == '-' && (pos + 2 >= src.length() || Character.isWhitespace(peek(2)))) {
                // MySQL처럼 "-- " (뒤에 공백)만 주석. "--1"은 MINUS MINUS 1
                while (pos < src.length() && peek(0) != '\n') advance();
            } else {
                return;
            }
        }
    }

    private void word(int l, int c) {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(peek(0)) || peek(0) == '_')) advance();
        String text = src.substring(start, pos);
        // ASCII 단어만 키워드 검사: toUpperCase가 'ſ'(U+017F)→S, 'ı'(U+0131)→I 로 바꿔 ſelect가 키워드가 되는 것을 막음
        String upper = text.chars().allMatch(ch -> ch < 128) ? text.toUpperCase(Locale.ROOT) : null;
        if (upper != null && KEYWORDS.contains(upper)) {
            out.add(new Token(TokenType.KEYWORD, upper, l, c));
        } else {
            out.add(new Token(TokenType.IDENT, text, l, c));
        }
    }

    private void number(int l, int c) {
        int start = pos;
        while (pos < src.length() && isDigit(peek(0))) advance();
        if (peek(0) == '.' && isDigit(peek(1))) {
            advance();
            while (pos < src.length() && isDigit(peek(0))) advance();
        }
        if (pos < src.length() && (Character.isLetter(peek(0)) || peek(0) == '_')) {
            throw new SqlException(l, c, "숫자 뒤에 글자가 붙어 있음: " + src.substring(start, pos + 1));
        }
        out.add(new Token(TokenType.NUMBER, src.substring(start, pos), l, c));
    }

    private void string(int l, int c) {
        advance(); // 여는 '
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw new SqlException(l, c, "닫히지 않은 따옴표");
            }
            char ch = peek(0);
            if (ch == '\'') {
                if (peek(1) == '\'') { // '' 는 따옴표 하나 (SQL 표준)
                    sb.append('\'');
                    advance();
                    advance();
                    continue;
                }
                advance(); // 닫는 '
                break;
            }
            sb.append(ch);
            advance();
        }
        out.add(new Token(TokenType.STRING, sb.toString(), l, c));
    }

    private void symbol(int l, int c) {
        char ch = peek(0);
        char next = peek(1);
        TokenType t;
        int len = 1;
        switch (ch) {
            case ',' -> t = TokenType.COMMA;
            case '(' -> t = TokenType.LPAREN;
            case ')' -> t = TokenType.RPAREN;
            case ';' -> t = TokenType.SEMICOLON;
            case '*' -> t = TokenType.STAR;
            case '-' -> t = TokenType.MINUS;
            case '=' -> t = TokenType.EQ;
            case '<' -> {
                if (next == '=') { t = TokenType.LE; len = 2; }
                else if (next == '>') { t = TokenType.NE; len = 2; }
                else t = TokenType.LT;
            }
            case '>' -> {
                if (next == '=') { t = TokenType.GE; len = 2; }
                else t = TokenType.GT;
            }
            case '!' -> {
                if (next != '=') throw new SqlException(l, c, "모르는 글자: '!' (같지 않음은 != 또는 <>)");
                t = TokenType.NE;
                len = 2;
            }
            default -> throw new SqlException(l, c, "모르는 글자: '" + ch + "'");
        }
        String text = src.substring(pos, pos + len);
        for (int i = 0; i < len; i++) advance();
        out.add(new Token(t, text, l, c));
    }

    private char peek(int ahead) {
        int i = pos + ahead;
        return i < src.length() ? src.charAt(i) : '\0';
    }

    private void advance() {
        if (src.charAt(pos) == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        pos++;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
