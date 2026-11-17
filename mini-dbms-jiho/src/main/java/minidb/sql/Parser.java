package minidb.sql;

import static minidb.sql.TokenType.*;

import java.util.ArrayList;
import java.util.List;
import minidb.sql.Ast.*;

/**
 * 토큰 목록 → 문장 트리 목록 (설계 1.2). 문법 모양만 본다.
 * 칸 개수·타입·id 칸·같은 이름 칸·DECIMAL 정밀도 같은 스키마 검사는 #24가 한다.
 *
 * <pre>
 * 문장   := (create | insert | select | update | delete) ';'
 * or     := and (OR and)*
 * and    := not (AND not)*
 * not    := NOT not | '(' or ')' | 조건
 * 조건   := 피연산자 [ 비교연산자 피연산자 | IS [NOT] NULL
 *                    | [NOT] BETWEEN 피연산자 AND 피연산자 | [NOT] IN (피연산자, ...) | [NOT] LIKE 문자열 ]
 * 피연산자 := 값 | 칸 이름
 * </pre>
 * 괄호는 조건 자리에만 온다: `(a = 1) = TRUE`, `a IN ((b OR c))`는 에러.
 * 비교가 NOT보다 먼저 묶인다 (MySQL과 같음): `NOT a = 1` → NOT(a = 1).
 * BETWEEN의 양 끝은 피연산자라서 `a BETWEEN 1 AND 5 AND b = 2`의 두 번째 AND는 논리 AND가 된다.
 */
public final class Parser {

    private final List<Token> ts;
    private int i = 0;

    private Parser(List<Token> ts) {
        this.ts = ts;
    }

    /** 전부 파싱한 뒤 돌려준다. 하나라도 틀리면 예외라서 아무 문장도 실행되지 않는다 (설계 1.2) */
    public static List<Statement> parse(String sql) {
        Parser p = new Parser(Lexer.tokenize(sql));
        List<Statement> out = new ArrayList<>();
        while (p.peek().type() != EOF) {
            out.add(p.statement());
        }
        return out;
    }

    // ───── 문장 ─────

    private Statement statement() {
        Token t = peek();
        Statement s;
        if (t.isKeyword("CREATE")) s = createTable();
        else if (t.isKeyword("INSERT")) s = insert();
        else if (t.isKeyword("SELECT")) s = select();
        else if (t.isKeyword("UPDATE")) s = update();
        else if (t.isKeyword("DELETE")) s = delete();
        else if (t.type() == SEMICOLON) throw new SqlException(t, "빈 문장");
        else throw new SqlException(t, "문장은 CREATE, INSERT, SELECT, UPDATE, DELETE로 시작해야 함, 받은 것: " + describe(t));
        expect(SEMICOLON, "';'");
        return s;
    }

    private CreateTable createTable() {
        next();
        keyword("TABLE");
        Name table = ident("테이블 이름");
        expect(LPAREN, "'('");
        List<ColumnDef> cols = new ArrayList<>();
        do {
            cols.add(columnDef());
        } while (accept(COMMA));
        expect(RPAREN, "')'");
        return new CreateTable(table, cols);
    }

    private ColumnDef columnDef() {
        Name name = ident("칸 이름");
        Token typeTok = peek();
        if (typeTok.type() != IDENT) throw new SqlException(typeTok, "타입 이름 필요, 받은 것: " + describe(typeTok));
        next();
        ColumnType type = columnType(typeTok);
        List<Integer> args = new ArrayList<>();
        if (type == ColumnType.VARCHAR) {
            expect(LPAREN, "VARCHAR 뒤 '('");
            args.add((int) unsigned(Integer.MAX_VALUE, "글자 수"));
            expect(RPAREN, "')'");
        } else if (type == ColumnType.DECIMAL) {
            expect(LPAREN, "DECIMAL 뒤 '('");
            args.add((int) unsigned(Integer.MAX_VALUE, "전체 자릿수"));
            expect(COMMA, "','");
            args.add((int) unsigned(Integer.MAX_VALUE, "소수 자릿수"));
            expect(RPAREN, "')'");
        }
        boolean notNull = false;
        if (acceptKeyword("NOT")) {
            keyword("NULL");
            notNull = true;
        }
        return new ColumnDef(name, type, args, notNull);
    }

    /** 타입 이름도 ASCII만 대소문자 무관: toUpperCase를 쓰면 'ınt'가 INT가 된다 */
    private static ColumnType columnType(Token t) {
        for (ColumnType c : ColumnType.values()) {
            if (Name.key(c.name()).equals(Name.key(t.text()))) return c;
        }
        throw new SqlException(t, "모르는 타입: " + t.text());
    }

    private Insert insert() {
        next();
        keyword("INTO");
        Name table = ident("테이블 이름");
        keyword("VALUES");
        List<List<Literal>> rows = new ArrayList<>();
        do {
            expect(LPAREN, "'('");
            List<Literal> row = new ArrayList<>();
            do {
                row.add(literal());
            } while (accept(COMMA));
            expect(RPAREN, "')'");
            rows.add(row);
        } while (accept(COMMA));
        return new Insert(table, rows);
    }

    private Select select() {
        next();
        List<Name> cols = new ArrayList<>();
        if (!accept(STAR)) {
            do {
                cols.add(ident("칸 이름"));
            } while (accept(COMMA));
        }
        keyword("FROM");
        Name table = ident("테이블 이름");
        Expr where = acceptKeyword("WHERE") ? or() : null;
        List<OrderBy> orderBy = new ArrayList<>();
        if (acceptKeyword("ORDER")) {
            keyword("BY");
            do {
                Name col = ident("칸 이름");
                boolean desc = acceptKeyword("DESC");
                if (!desc) acceptKeyword("ASC");
                orderBy.add(new OrderBy(col, desc));
            } while (accept(COMMA));
        }
        Long limit = acceptKeyword("LIMIT") ? unsigned(Long.MAX_VALUE, "LIMIT 개수") : null;
        return new Select(table, cols, where, orderBy, limit);
    }

    private Update update() {
        next();
        Name table = ident("테이블 이름");
        keyword("SET");
        List<Assignment> sets = new ArrayList<>();
        do {
            Name col = ident("칸 이름");
            expect(EQ, "'='");
            sets.add(new Assignment(col, literal()));
        } while (accept(COMMA));
        Expr where = acceptKeyword("WHERE") ? or() : null;
        return new Update(table, sets, where);
    }

    private Delete delete() {
        next();
        keyword("FROM");
        Name table = ident("테이블 이름");
        Expr where = acceptKeyword("WHERE") ? or() : null;
        return new Delete(table, where);
    }

    // ───── WHERE 식 (우선순위: 비교 > NOT > AND > OR) ─────

    private Expr or() {
        Expr left = and();
        while (acceptKeyword("OR")) left = new Or(left, and());
        return left;
    }

    private Expr and() {
        Expr left = not();
        while (acceptKeyword("AND")) left = new And(left, not());
        return left;
    }

    private Expr not() {
        if (acceptKeyword("NOT")) return new Not(not());
        if (accept(LPAREN)) {
            Expr e = or();
            expect(RPAREN, "')'");
            return e;
        }
        return predicate();
    }

    private Expr predicate() {
        Operand left = operand();
        CompareOp op = compareOp(peek());
        if (op != null) {
            next();
            return new Compare(op, left, operand());
        }
        if (acceptKeyword("IS")) {
            boolean not = acceptKeyword("NOT");
            keyword("NULL");
            return new IsNull(left, not);
        }
        Token notTok = peek();
        boolean not = acceptKeyword("NOT");
        if (acceptKeyword("BETWEEN")) {
            Operand low = operand();
            keyword("AND");
            return new Between(left, low, operand(), not);
        }
        if (acceptKeyword("IN")) {
            expect(LPAREN, "IN 뒤 '('");
            List<Operand> items = new ArrayList<>();
            do {
                items.add(operand());
            } while (accept(COMMA));
            expect(RPAREN, "')'");
            return new In(left, items, not);
        }
        if (acceptKeyword("LIKE")) {
            Token p = peek();
            if (p.type() != STRING) throw new SqlException(p, "LIKE 뒤에는 문자열 패턴 필요, 받은 것: " + describe(p));
            next();
            return new Like(left, p.text(), not);
        }
        if (not) throw new SqlException(notTok, "칸 뒤의 NOT은 BETWEEN, IN, LIKE 앞에만 올 수 있음");
        return left; // `WHERE flag`처럼 값 하나도 조건 (참·거짓 판단은 평가기 #37)
    }

    private Operand operand() {
        if (peek().type() == IDENT) return new Column(ident("칸 이름"));
        return literal();
    }

    private static CompareOp compareOp(Token t) {
        return switch (t.type()) {
            case EQ -> CompareOp.EQ;
            case NE -> CompareOp.NE;
            case LT -> CompareOp.LT;
            case LE -> CompareOp.LE;
            case GT -> CompareOp.GT;
            case GE -> CompareOp.GE;
            default -> null;
        };
    }

    /** 값 하나. `-` 바로 뒤 숫자는 음수 리터럴 하나로 묶고 위치는 `-` 자리 (BIGINT 최솟값이 넘치지 않게) */
    private Literal literal() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER -> {
                next();
                return new Literal(LiteralKind.NUMBER, t.text(), t.line(), t.col());
            }
            case STRING -> {
                next();
                return new Literal(LiteralKind.STRING, t.text(), t.line(), t.col());
            }
            case MINUS -> {
                next();
                Token n = peek();
                if (n.type() != NUMBER) throw new SqlException(n, "'-' 뒤에는 숫자 필요, 받은 것: " + describe(n));
                next();
                return new Literal(LiteralKind.NUMBER, "-" + n.text(), t.line(), t.col());
            }
            case KEYWORD -> {
                if (t.isKeyword("TRUE") || t.isKeyword("FALSE")) {
                    next();
                    return new Literal(LiteralKind.BOOLEAN, t.text(), t.line(), t.col());
                }
                if (t.isKeyword("NULL")) {
                    next();
                    return new Literal(LiteralKind.NULL, t.text(), t.line(), t.col());
                }
            }
            default -> {
            }
        }
        throw new SqlException(t, "값 필요, 받은 것: " + describe(t));
    }

    // ───── 토큰 다루기 ─────

    private Name ident(String what) {
        Token t = peek();
        if (t.type() == IDENT) {
            next();
            return new Name(t.text(), t.line(), t.col());
        }
        if (t.type() == KEYWORD) throw new SqlException(t, "예약어 " + t.text() + "는 " + what + "으로 쓸 수 없음");
        throw new SqlException(t, what + " 필요, 받은 것: " + describe(t));
    }

    /** 부호 없는 정수(숫자만, '.' 없음)를 max 이하로 읽는다. VARCHAR(n), DECIMAL(p,s), LIMIT에 씀 */
    private long unsigned(long max, String what) {
        Token t = peek();
        if (t.type() != NUMBER || !t.text().chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new SqlException(t, what + "는 0 이상의 정수여야 함, 받은 것: " + describe(t));
        }
        long v;
        try {
            v = Long.parseLong(t.text());
        } catch (NumberFormatException e) {
            v = -1;
        }
        if (v < 0 || v > max) throw new SqlException(t, what + "가 너무 큼: " + t.text());
        next();
        return v;
    }

    private Token peek() {
        return ts.get(i);
    }

    private void next() {
        if (ts.get(i).type() != EOF) i++;
    }

    private boolean accept(TokenType type) {
        if (peek().type() != type) return false;
        next();
        return true;
    }

    private boolean acceptKeyword(String kw) {
        if (!peek().isKeyword(kw)) return false;
        next();
        return true;
    }

    private void expect(TokenType type, String what) {
        Token t = peek();
        if (t.type() != type) throw new SqlException(t, what + " 필요, 받은 것: " + describe(t));
        next();
    }

    private void keyword(String kw) {
        Token t = peek();
        if (!t.isKeyword(kw)) throw new SqlException(t, kw + " 필요, 받은 것: " + describe(t));
        next();
    }

    private static String describe(Token t) {
        return switch (t.type()) {
            case EOF -> "입력 끝";
            case STRING -> "문자열 '" + t.text() + "'";
            default -> "'" + t.text() + "'";
        };
    }
}
