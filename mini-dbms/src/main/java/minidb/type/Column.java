package minidb.type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import minidb.sql.Ast.ColumnType;
import minidb.sql.Ast.Literal;
import minidb.sql.Ast.LiteralKind;
import minidb.sql.SqlException;

/**
 * 칸 하나의 정의 (설계 1.3). 0번 메타 페이지(설계 1.4)의 "이름 + 타입 코드 + 인자1(n 또는 p) + 인자2(s)"와 같은 모양.
 *
 * @param name    쓴 모양 그대로. 비교는 {@code Name.key(name)}
 * @param n       VARCHAR(n)의 글자 수, DECIMAL(p,s)의 p. 나머지 타입은 0
 * @param s       DECIMAL(p,s)의 s. 나머지 타입은 0
 */
public record Column(String name, ColumnType type, int n, int s, boolean notNull) {

    /** 값 바이트 최대 크기 (설계 1.6). VARCHAR(n)은 2 + 4n: n이 int 최댓값이어도 넘치지 않게 long */
    public long maxSize() {
        return switch (type) {
            case BOOLEAN -> 1;
            case INT, DATE -> 4;
            case BIGINT, DOUBLE, DECIMAL, DATETIME, TIMESTAMP -> 8;
            case VARCHAR -> 2 + 4L * n;
        };
    }

    /** 에러 메시지용 타입 글자: INT, VARCHAR(20), DECIMAL(5,2) */
    public String typeText() {
        return switch (type) {
            case VARCHAR -> "VARCHAR(" + n + ")";
            case DECIMAL -> "DECIMAL(" + n + "," + s + ")";
            default -> type.name();
        };
    }

    /** {@link #convert(Literal, ZoneId)}를 서버 OS 시간대로 */
    public Object convert(Literal lit) {
        return convert(lit, ZoneId.systemDefault());
    }

    /**
     * INSERT·UPDATE 값 검사: 리터럴을 이 칸 타입의 값으로 바꾼다 (값 표현은 {@link Values}).
     * NULL·NOT NULL, 타입, 범위, VARCHAR 글자 수를 보고, 틀리면 리터럴 위치로 {@link SqlException}.
     * 값을 몰래 바꾸지 않는다: 정수 칸의 1.5, DECIMAL(5,2)의 1.234는 반올림하지 않고 에러.
     *
     * @param zone TIMESTAMP 입력의 시간대 (서버 OS 시간대). 다른 타입은 안 씀
     */
    public Object convert(Literal lit, ZoneId zone) {
        if (lit.kind() == LiteralKind.NULL) {
            if (notNull) throw err(lit, "NOT NULL 칸에 NULL을 넣을 수 없음");
            return null;
        }
        return switch (type) {
            case INT, BIGINT, DOUBLE, DECIMAL -> number(need(lit, LiteralKind.NUMBER));
            case BOOLEAN -> Values.of(need(lit, LiteralKind.BOOLEAN));
            case VARCHAR -> varchar(need(lit, LiteralKind.STRING).text(), lit);
            case DATE, DATETIME, TIMESTAMP -> {
                String text = need(lit, LiteralKind.STRING).text();
                try {
                    yield switch (type) {
                        case DATE -> Values.parseDate(text);
                        case DATETIME -> Values.parseDateTime(text);
                        default -> Values.parseTimestamp(text, zone);
                    };
                } catch (IllegalArgumentException e) {
                    throw err(lit, e.getMessage());
                }
            }
        };
    }

    private Object number(Literal lit) {
        String text = lit.text();
        if (type == ColumnType.DOUBLE) {
            double d = Double.parseDouble(text);
            if (Double.isInfinite(d)) throw err(lit, "범위 밖: " + text);
            return d;
        }
        BigDecimal v = new BigDecimal(text);
        if (type == ColumnType.DECIMAL) {
            try {
                v = v.setScale(s); // 자릿수를 버려야 하면 ArithmeticException
            } catch (ArithmeticException e) {
                throw err(lit, "소수 자릿수 " + s + "자리 초과: " + text);
            }
            if (v.unscaledValue().abs().compareTo(BigInteger.TEN.pow(n)) >= 0) throw err(lit, "범위 밖: " + text);
            return v;
        }
        BigInteger i;
        try {
            i = v.toBigIntegerExact();
        } catch (ArithmeticException e) {
            throw err(lit, "정수만 넣을 수 있음: " + text);
        }
        try {
            return type == ColumnType.INT ? (Object) i.intValueExact() : (Object) i.longValueExact();
        } catch (ArithmeticException e) {
            throw err(lit, "범위 밖: " + text);
        }
    }

    private String varchar(String v, Literal lit) {
        int len = v.codePointCount(0, v.length());
        if (len > n) throw err(lit, n + "글자까지 넣을 수 있음, 받은 값 " + len + "글자");
        // 짝 없는 서로게이트는 UTF-8로 바꿀 때 '?'가 되어 값이 몰래 바뀐다
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(v)) throw err(lit, "UTF-8로 바꿀 수 없는 글자가 있음");
        return v;
    }

    private Literal need(Literal lit, LiteralKind kind) {
        if (lit.kind() != kind) throw err(lit, kindText(lit.kind()) + " 넣을 수 없음");
        return lit;
    }

    private static String kindText(LiteralKind k) {
        return switch (k) {
            case NUMBER -> "숫자를";
            case STRING -> "문자열을";
            case BOOLEAN -> "TRUE/FALSE를";
            case NULL -> "NULL을";
        };
    }

    private SqlException err(Literal lit, String msg) {
        return new SqlException(lit.line(), lit.col(), "칸 " + name + "(" + typeText() + "): " + msg);
    }
}
