package minidbms.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

public final class SqlParser {
    private enum Kind { WORD, STRING, INTEGER, OPEN, CLOSE, COMMA, STAR, EQUALS, SEMICOLON, END }

    private record Token(Kind kind, String text, int offset) {
        SqlException error(String message) {
            return new SqlException(message + " at character " + (offset + 1) + ".");
        }
    }

    private final Lexer lexer;
    private Token current;

    private SqlParser(String sql) {
        lexer = new Lexer(sql);
        current = lexer.next();
    }

    public static List<Statement> parse(String sql) {
        return new SqlParser(sql).script();
    }

    private List<Statement> script() {
        var statements = new ArrayList<Statement>();
        while (current.kind() != Kind.END) {
            if (take(Kind.SEMICOLON)) {
                continue;
            }

            if (takeWord("insert")) {
                statements.add(insert());
            } else if (takeWord("select")) {
                statements.add(select());
            } else {
                throw current.error("Expected INSERT or SELECT");
            }

            if (current.kind() != Kind.END) {
                expect(Kind.SEMICOLON, "';'");
            }
        }
        return List.copyOf(statements);
    }

    private Statement.Insert insert() {
        expectWord("into");
        String table = identifier();
        expect(Kind.OPEN, "'('");
        List<String> columns = commaSeparated(this::identifier);
        expect(Kind.CLOSE, "')'");
        expectWord("values");
        expect(Kind.OPEN, "'('");
        List<Object> values = commaSeparated(this::literal);
        expect(Kind.CLOSE, "')'");
        return new Statement.Insert(table, columns, values);
    }

    private Statement.Select select() {
        List<String> columns = take(Kind.STAR) ? List.of() : commaSeparated(this::identifier);
        expectWord("from");
        String table = identifier();
        Optional<Statement.Condition> where = Optional.empty();
        if (takeWord("where")) {
            String column = identifier();
            expect(Kind.EQUALS, "'='");
            where = Optional.of(new Statement.Condition(column, literal()));
        }
        return new Statement.Select(table, columns, where);
    }

    private <T> List<T> commaSeparated(Supplier<T> nextItem) {
        var items = new ArrayList<T>();
        do {
            items.add(nextItem.get());
        } while (take(Kind.COMMA));
        return items;
    }

    private String identifier() {
        return expect(Kind.WORD, "an identifier").text().toLowerCase(Locale.ROOT);
    }

    private Object literal() {
        Token token = current;
        if (take(Kind.STRING)) {
            return token.text();
        }
        if (token.kind() == Kind.INTEGER) {
            long value;
            try {
                value = Long.parseLong(token.text());
            } catch (NumberFormatException exception) {
                throw token.error("Integer outside the BIGINT range");
            }
            advance();
            return value;
        }
        throw token.error("Expected a string or integer");
    }

    private void expectWord(String word) {
        if (!takeWord(word)) {
            throw current.error("Expected " + word.toUpperCase(Locale.ROOT));
        }
    }

    private boolean takeWord(String word) {
        if (current.kind() != Kind.WORD || !current.text().equalsIgnoreCase(word)) {
            return false;
        }
        advance();
        return true;
    }

    private Token expect(Kind kind, String description) {
        Token token = current;
        if (!take(kind)) {
            throw token.error("Expected " + description);
        }
        return token;
    }

    private boolean take(Kind kind) {
        if (current.kind() != kind) {
            return false;
        }
        advance();
        return true;
    }

    private void advance() {
        current = lexer.next();
    }

    private static final class Lexer {
        private final String sql;
        private int position;

        private Lexer(String sql) {
            this.sql = sql;
        }

        private Token next() {
            skipTrivia();
            int start = position;
            if (position == sql.length()) {
                return new Token(Kind.END, "", start);
            }

            char character = sql.charAt(position++);
            Kind kind = switch (character) {
                case '(' -> Kind.OPEN;
                case ')' -> Kind.CLOSE;
                case ',' -> Kind.COMMA;
                case '*' -> Kind.STAR;
                case '=' -> Kind.EQUALS;
                case ';' -> Kind.SEMICOLON;
                default -> null;
            };
            if (kind != null) {
                return new Token(kind, "", start);
            }
            if (character == '\'') {
                return string(start);
            }
            if (Character.isLetter(character) || character == '_') {
                while (position < sql.length()) {
                    char next = sql.charAt(position);
                    if (!Character.isLetterOrDigit(next) && next != '_') {
                        break;
                    }
                    position++;
                }
                return new Token(Kind.WORD, sql.substring(start, position), start);
            }
            if (isDigit(character) || (character == '-' && position < sql.length() && isDigit(sql.charAt(position)))) {
                while (position < sql.length() && isDigit(sql.charAt(position))) {
                    position++;
                }
                return new Token(Kind.INTEGER, sql.substring(start, position), start);
            }
            throw new SqlException("Unexpected character '" + character + "' at character " + (start + 1) + ".");
        }

        private Token string(int start) {
            var value = new StringBuilder();
            while (position < sql.length()) {
                char character = sql.charAt(position++);
                if (character == '\'') {
                    if (position == sql.length() || sql.charAt(position) != '\'') {
                        return new Token(Kind.STRING, value.toString(), start);
                    }
                    position++;
                }
                value.append(character);
            }
            throw new SqlException("Unterminated string at character " + (start + 1) + ".");
        }

        private void skipTrivia() {
            while (position < sql.length()) {
                if (Character.isWhitespace(sql.charAt(position))) {
                    position++;
                } else if (sql.startsWith("--", position)) {
                    while (position < sql.length() && sql.charAt(position) != '\n' && sql.charAt(position) != '\r') {
                        position++;
                    }
                } else {
                    return;
                }
            }
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }
    }
}
