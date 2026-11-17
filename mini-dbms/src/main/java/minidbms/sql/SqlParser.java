package minidbms.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** SQL 문자열을 토큰으로 읽고 INSERT와 SELECT 문장 객체로 조립한다. */
public final class SqlParser {
    // enum은 토큰 종류를 정한다. 렉서는 키워드와 식별자를 모두 WORD로 읽는다.
    private enum Kind { WORD, STRING, INTEGER, OPEN, CLOSE, COMMA, STAR, EQUALS, SEMICOLON, END }

    // 토큰의 offset은 원문에서 0부터 센다. 오류 메시지에는 1부터 센 위치를 표시한다.
    private record Token(Kind kind, String text, int offset) {
        SqlException error(String message) {
            return new SqlException(message + " at character " + (offset + 1) + ".");
        }
    }

    private final Lexer lexer;
    // 아직 소비하지 않은 토큰 하나를 보관한다. 읽을 토큰의 종류에 따라 다음 파싱 단계가 결정된다.
    private Token current;

    private SqlParser(String sql) {
        lexer = new Lexer(sql);
        current = lexer.next();
    }

    // static 진입점이라 SqlParser.parse(sql)로 호출한다. 호출마다 새 파서가 읽기 위치를 관리한다.
    public static List<Statement> parse(String sql) {
        return new SqlParser(sql).script();
    }

    private List<Statement> script() {
        // var의 타입은 오른쪽 식에서 ArrayList<Statement>로 정해진다.
        var statements = new ArrayList<Statement>();
        while (current.kind() != Kind.END) {
            // 문장 시작 위치에서 세미콜론을 만나면 빈 문장으로 보고 다음 토큰으로 넘어간다.
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

            // 문장 사이에는 구분자가 필요하다. 마지막 문장은 세미콜론 없이 끝나도 된다.
            if (current.kind() != Kind.END) {
                expect(Kind.SEMICOLON, "';'");
            }
        }
        return List.copyOf(statements);
    }

    // INSERT 키워드를 소비한 뒤 괄호로 구분된 열 목록과 값 목록을 순서대로 읽는다.
    private Statement.Insert insert() {
        expectWord("into"); // INTO 키워드 확인
        String table = identifier(); // 테이블 이름 추출
        expect(Kind.OPEN, "'('"); // 여는 괄호 ( 확인
        // this::identifier는 메서드 참조다. 목록의 항목을 읽을 때마다 identifier()가 호출된다.
        List<String> columns = commaSeparated(this::identifier); // 쉼표로 구분된 컬럼 추출
        expect(Kind.CLOSE, "')'"); // 닫는 괄호 ) 확인
        expectWord("values"); // VALUES 키워드 확인
        expect(Kind.OPEN, "'('");
        List<Object> values = commaSeparated(this::literal); // 저장할 값 추출
        expect(Kind.CLOSE, "')'");
        // SQL에 나온 순서대로 열과 값을 담는다. 실제 스키마와의 대조는 실행기가 맡는다.
        return new Statement.Insert(table, columns, values);
    }

    private Statement.Select select() {
        // commaSeparated: 쉼표로 구분된 열 목록을 선택한다.
        List<String> columns = take(Kind.STAR) ? List.of() : commaSeparated(this::identifier);
        expectWord("from"); // WORD의 내용이 기대한 키워드와 맞을 때만 소비한다. 대소문자 차이는 허용한다.

        String table = identifier();
        // Optional.empty()로 시작하고, WHERE를 읽으면 조건 객체를 Optional.of(...)에 담는다.
        Optional<Statement.Condition> where = Optional.empty();
        if (takeWord("where")) {
            String column = identifier();
            expect(Kind.EQUALS, "'='");
            where = Optional.of(new Statement.Condition(column, literal()));
        }
        return new Statement.Select(table, columns, where);
    }

    // Supplier<T>는 인자 없이 항목 하나를 읽는 함수다. 넘긴 메서드의 반환형이 T를 결정한다.
    private <T> List<T> commaSeparated(Supplier<T> nextItem) {
        var items = new ArrayList<T>();
        // do-while은 첫 항목을 반드시 읽고, 쉼표를 읽을 때마다 다음 항목을 요구한다.
        do {
            items.add(nextItem.get());
        } while (take(Kind.COMMA));
        return items;
    }

    // 식별자를 소문자로 통일해 카탈로그와 비교한다. 문자열 리터럴의 대소문자는 값의 일부다.
    private String identifier() {
        return expect(Kind.WORD, "an identifier").text().toLowerCase(Locale.ROOT);
    }

    private Object literal() {
        // take가 성공하면 current가 다음 토큰으로 바뀐다. 읽은 값은 token에 따로 보관한다.
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
            // 반환 타입이 Object라서 기본형 long이 Long 객체로 변환된다.
            return value;
        }
        throw token.error("Expected a string or integer");
    }

    private void expectWord(String word) {
        if (!takeWord(word)) {
            throw current.error("Expected " + word.toUpperCase(Locale.ROOT));
        }
    }

    // WORD의 내용이 기대한 키워드와 맞을 때만 소비한다. 대소문자 차이는 허용한다.
    private boolean takeWord(String word) {
        if (current.kind() != Kind.WORD || !current.text().equalsIgnoreCase(word)) {
            return false;
        }
        advance();
        return true;
    }

    // 필수 토큰은 expect로 오류를 보고한다. 선택 요소는 take를 써서 일치할 때만 토큰을 소비한다.
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

    // 렉서는 문자 위치를 이동하면서 토큰의 종류, 값, 시작 위치를 만든다.
    private static final class Lexer {
        private final String sql;
        private int position;

        private Lexer(String sql) {
            this.sql = sql;
        }

        private Token next() {
            // 토큰을 시작하기 전에 공백과 줄 주석을 건너뛴다. 따옴표 안에서는 string()이 문자 전체를 읽는다.
            skipTrivia();
            int start = position;
            // 입력이 끝나면 END를 반환해 파서가 문장 읽기를 마칠 수 있게 한다.
            if (position == sql.length()) {
                return new Token(Kind.END, "", start);
            }

            // position++는 증가하기 전의 값을 charAt에 넘기고 position을 1 늘린다.
            char character = sql.charAt(position++);
            // switch 표현식은 괄호, 쉼표 등 한 글자 기호에 대응하는 Kind를 돌려준다.
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
            // 숫자로 시작하거나 - 뒤에 숫자가 오면 정수 토큰으로 읽는다.
            if (isDigit(character) || (character == '-' && position < sql.length() && isDigit(sql.charAt(position)))) {
                while (position < sql.length() && isDigit(sql.charAt(position))) {
                    position++;
                }
                return new Token(Kind.INTEGER, sql.substring(start, position), start);
            }
            throw new SqlException("Unexpected character '" + character + "' at character " + (start + 1) + ".");
        }

        // 여는 따옴표를 읽은 상태에서 시작해 닫는 따옴표까지 실제 문자열 값을 모은다.
        private Token string(int start) {
            var value = new StringBuilder();
            while (position < sql.length()) {
                char character = sql.charAt(position++);
                if (character == '\'') {
                    if (position == sql.length() || sql.charAt(position) != '\'') {
                        return new Token(Kind.STRING, value.toString(), start);
                    }
                    // 따옴표가 연달아 나오면 SQL의 이스케이프다. O''Brien은 O'Brien으로 읽힌다.
                    position++;
                }
                value.append(character);
            }
            throw new SqlException("Unterminated string at character " + (start + 1) + ".");
        }

        private void skipTrivia() {
            while (position < sql.length()) {
                if (Character.isWhitespace(sql.charAt(position))) { // 공백, 탭, 줄바꿈을 건너뛴다.
                    position++;
                } else if (sql.startsWith("--", position)) {
                    // 줄 주석은 줄바꿈 직전까지 읽고, 줄바꿈 자체는 공백 처리에 맡긴다.
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
