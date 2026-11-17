package minidb.engine;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import minidb.sql.Ast;
import minidb.sql.Ast.CompareOp;
import minidb.sql.Ast.LiteralKind;
import minidb.sql.SqlException;
import minidb.type.Column;
import minidb.type.Schema;
import minidb.type.Values;

/**
 * WHERE 식 평가기 (설계 1.2, #37). {@link #compile}이 칸 이름 찾기와 타입 검사, 리터럴 변환을 한 번에 끝내고,
 * {@link #test}는 행마다 3값 논리(TRUE / FALSE / unknown=null)로 계산만 한다.
 * 검사를 compile에서 하는 까닭: 테이블이 비어 있어도 틀린 WHERE가 에러로 드러나고, 행마다 파싱·검사를 되풀이하지 않는다.
 *
 * <p>NULL이 낀 비교는 unknown이고, 호출하는 쪽은 TRUE인 행만 남긴다 (SQL 표준과 같음).
 * LIKE에는 이스케이프 문자가 없다: {@code \}도 평범한 글자다 (설계 결정).
 */
public final class Where {

    private final Function<Object[], Boolean> node;

    private Where(Function<Object[], Boolean> node) {
        this.node = node;
    }

    /**
     * @param e 조건. null이면 조건 없음이라 언제나 TRUE
     * @param zone TIMESTAMP 칸과 비교할 문자열을 읽는 시간대 (서버 OS 시간대)
     * @throws SqlException 없는 칸, 비교할 수 없는 타입, 잘못된 날짜 글자, TRUE/FALSE가 아닌 조건
     */
    public static Where compile(Ast.Expr e, Schema s, ZoneId zone) {
        if (e == null) return new Where(row -> Boolean.TRUE);
        return new Where(new Binder(s, zone).cond(e));
    }

    /** @param row 스키마 순서의 값 (id가 0번). @return TRUE, FALSE, 또는 unknown(null) */
    public Boolean test(Object[] row) {
        return node.apply(row);
    }

    // ───── compile ─────

    private interface Fn {
        Object get(Object[] row);
    }

    /** 비교할 두 쪽. 타입 검사와 리터럴 변환이 끝난 값 읽개 */
    private record Pair(Fn l, Fn r) {
    }

    private static final class Binder {
        private final Schema schema;
        private final ZoneId zone;

        Binder(Schema schema, ZoneId zone) {
            this.schema = schema;
            this.zone = zone;
        }

        Function<Object[], Boolean> cond(Ast.Expr e) {
            return switch (e) {
                case Ast.Operand o -> bare(o);
                case Ast.Compare c -> compare(c);
                case Ast.And a -> {
                    var l = cond(a.left());
                    var r = cond(a.right());
                    yield row -> {
                        Boolean x = l.apply(row);
                        if (x == Boolean.FALSE) return Boolean.FALSE;
                        Boolean y = r.apply(row);
                        if (y == Boolean.FALSE) return Boolean.FALSE;
                        return x == null || y == null ? null : Boolean.TRUE;
                    };
                }
                case Ast.Or o -> {
                    var l = cond(o.left());
                    var r = cond(o.right());
                    yield row -> {
                        Boolean x = l.apply(row);
                        if (x == Boolean.TRUE) return Boolean.TRUE;
                        Boolean y = r.apply(row);
                        if (y == Boolean.TRUE) return Boolean.TRUE;
                        return x == null || y == null ? null : Boolean.FALSE;
                    };
                }
                case Ast.Not n -> not(cond(n.expr()), true);
                case Ast.Between b -> between(b);
                case Ast.In in -> in(in);
                case Ast.Like l -> like(l);
                case Ast.IsNull n -> {
                    Fn v = single(n.value());
                    yield row -> (v.get(row) == null) != n.not();
                }
            };
        }

        /** NOT. unknown은 NOT해도 unknown. on이 false면 그대로 돌려준다 (NOT BETWEEN 같은 말 줄이기) */
        private static Function<Object[], Boolean> not(Function<Object[], Boolean> f, boolean on) {
            if (!on) return f;
            return row -> {
                Boolean x = f.apply(row);
                return x == null ? null : !x;
            };
        }

        /** `WHERE active`, `WHERE TRUE`: 조건 자리의 값은 BOOLEAN이어야 한다 */
        private Function<Object[], Boolean> bare(Ast.Operand o) {
            Column c = column(o);
            boolean ok = c != null ? c.type() == Ast.ColumnType.BOOLEAN
                    : ((Ast.Literal) o).kind() == LiteralKind.BOOLEAN || ((Ast.Literal) o).kind() == LiteralKind.NULL;
            if (!ok) throw err(o, "조건에는 TRUE/FALSE 값이 와야 함");
            Fn v = single(o);
            return row -> (Boolean) v.get(row);
        }

        private Function<Object[], Boolean> compare(Ast.Compare c) {
            Pair p = pair(c.left(), c.right());
            return cmp(p, c.op());
        }

        private static Function<Object[], Boolean> cmp(Pair p, CompareOp op) {
            return row -> {
                Object a = p.l().get(row);
                Object b = p.r().get(row);
                if (a == null || b == null) return null;
                int d = Values.compare(a, b);
                return switch (op) {
                    case EQ -> d == 0;
                    case NE -> d != 0;
                    case LT -> d < 0;
                    case LE -> d <= 0;
                    case GT -> d > 0;
                    case GE -> d >= 0;
                };
            };
        }

        /** v BETWEEN lo AND hi = (v >= lo) AND (v <= hi). 한쪽이 FALSE면 다른 쪽이 NULL이어도 FALSE */
        private Function<Object[], Boolean> between(Ast.Between b) {
            var ge = cmp(pair(b.value(), b.low()), CompareOp.GE);
            var le = cmp(pair(b.value(), b.high()), CompareOp.LE);
            Function<Object[], Boolean> both = row -> {
                Boolean x = ge.apply(row);
                if (x == Boolean.FALSE) return Boolean.FALSE;
                Boolean y = le.apply(row);
                if (y == Boolean.FALSE) return Boolean.FALSE;
                return x == null || y == null ? null : Boolean.TRUE;
            };
            return not(both, b.not());
        }

        /** 같은 항목이 하나라도 있으면 TRUE. 없고 NULL이 끼었으면 unknown (`1 NOT IN (2, NULL)`이 TRUE가 되면 안 됨) */
        private Function<Object[], Boolean> in(Ast.In in) {
            List<Pair> ps = new ArrayList<>();
            for (Ast.Operand item : in.items()) ps.add(pair(in.value(), item));
            Function<Object[], Boolean> any = row -> {
                boolean unknown = false;
                for (Pair p : ps) {
                    Object a = p.l().get(row);
                    Object b = p.r().get(row);
                    if (a == null || b == null) unknown = true;
                    else if (Values.compare(a, b) == 0) return Boolean.TRUE;
                }
                return unknown ? null : Boolean.FALSE;
            };
            return not(any, in.not());
        }

        private Function<Object[], Boolean> like(Ast.Like l) {
            Column c = column(l.value());
            boolean ok = c != null ? c.type() == Ast.ColumnType.VARCHAR
                    : ((Ast.Literal) l.value()).kind() == LiteralKind.STRING
                            || ((Ast.Literal) l.value()).kind() == LiteralKind.NULL;
            if (!ok) throw err(l.value(), "LIKE는 문자열에만 쓸 수 있음: " + describe(l.value(), c));
            Fn v = single(l.value());
            int[] p = l.pattern().codePoints().toArray();
            Function<Object[], Boolean> m = row -> {
                Object s = v.get(row);
                return s == null ? null : matches(((String) s).codePoints().toArray(), p);
            };
            return not(m, l.not());
        }

        // ───── 피연산자 묶기·변환 ─────

        /** 칸이면 그 칸 정의, 리터럴이면 null. 없는 칸은 이름 자리에서 에러 */
        private Column column(Ast.Operand o) {
            if (o instanceof Ast.Column c) {
                int i = schema.indexOf(c.name().text());
                if (i < 0) throw new SqlException(c.name().line(), c.name().col(), "없는 칸: " + c.name().text());
                return schema.columns().get(i);
            }
            return null;
        }

        /** IS NULL·LIKE·조건 자리처럼 혼자 오는 피연산자의 값 읽개 */
        private Fn single(Ast.Operand o) {
            if (o instanceof Ast.Column c) {
                column(o);
                int i = schema.indexOf(c.name().text());
                return row -> row[i];
            }
            Object v = Values.of((Ast.Literal) o);
            return row -> v;
        }

        /** 두 피연산자를 비교할 수 있는지 보고, 리터럴은 상대 칸의 타입 값으로 바꿔 둔다 */
        private Pair pair(Ast.Operand a, Ast.Operand b) {
            Column ca = column(a);
            Column cb = column(b);
            if (ca != null && cb != null) {
                if (!(numeric(ca) && numeric(cb)) && ca.type() != cb.type()) throw mismatch(a, ca, b, cb, b);
                return new Pair(single(a), single(b));
            }
            if (ca != null) {
                if (!fits(ca, (Ast.Literal) b)) throw mismatch(a, ca, b, null, b);
                return new Pair(single(a), literal(ca, (Ast.Literal) b));
            }
            if (cb != null) {
                if (!fits(cb, (Ast.Literal) a)) throw mismatch(a, null, b, cb, a);
                return new Pair(literal(cb, (Ast.Literal) a), single(b));
            }
            Object x = Values.of((Ast.Literal) a);
            Object y = Values.of((Ast.Literal) b);
            if (x != null && y != null && !Values.comparable(x, y)) throw mismatch(a, null, b, null, b);
            return new Pair(row -> x, row -> y);
        }

        /** NULL은 어느 칸과도 비교할 수 있다 (결과는 unknown). 그 밖에는 칸 타입에 맞는 리터럴 종류여야 한다 */
        private static boolean fits(Column c, Ast.Literal lit) {
            return lit.kind() == LiteralKind.NULL || lit.kind() == switch (c.type()) {
                case INT, BIGINT, DOUBLE, DECIMAL -> LiteralKind.NUMBER;
                case BOOLEAN -> LiteralKind.BOOLEAN;
                case VARCHAR, DATE, DATETIME, TIMESTAMP -> LiteralKind.STRING;
            };
        }

        /** 칸과 비교할 리터럴을 그 칸 타입의 값으로. 날짜·시각은 문자열을 읽는다 (compile에서 한 번만) */
        private Fn literal(Column c, Ast.Literal lit) {
            if (lit.kind() == LiteralKind.NULL) return row -> null;
            Object v;
            try {
                v = switch (c.type()) {
                    case DATE -> Values.parseDate(lit.text());
                    case DATETIME -> Values.parseDateTime(lit.text());
                    case TIMESTAMP -> Values.parseTimestamp(lit.text(), zone);
                    default -> Values.of(lit);
                };
            } catch (IllegalArgumentException ex) {
                throw new SqlException(lit.line(), lit.col(), ex.getMessage());
            }
            Object fv = v;
            return row -> fv;
        }

        private static boolean numeric(Column c) {
            return switch (c.type()) {
                case INT, BIGINT, DOUBLE, DECIMAL -> true;
                default -> false;
            };
        }

        // ───── 에러 ─────

        private static SqlException mismatch(Ast.Operand a, Column ca, Ast.Operand b, Column cb, Ast.Operand at) {
            return err(at, "비교할 수 없는 타입: " + describe(a, ca) + "와 " + describe(b, cb));
        }

        /** 에러 메시지용: "칸 age(INT)", "문자열", "숫자", "TRUE/FALSE", "NULL" */
        private static String describe(Ast.Operand o, Column c) {
            if (c != null) return "칸 " + c.name() + "(" + c.typeText() + ")";
            return switch (((Ast.Literal) o).kind()) {
                case NUMBER -> "숫자";
                case STRING -> "문자열";
                case BOOLEAN -> "TRUE/FALSE";
                case NULL -> "NULL";
            };
        }

        private static SqlException err(Ast.Operand o, String msg) {
            return switch (o) {
                case Ast.Column c -> new SqlException(c.name().line(), c.name().col(), msg);
                case Ast.Literal l -> new SqlException(l.line(), l.col(), msg);
            };
        }
    }

    /**
     * LIKE 짝짓기: % 는 0글자 이상, _ 는 코드 포인트 하나, 나머지는 같은 글자. 대소문자 구분.
     * 정규식 대신 직접 짠 까닭: 마지막 %에서만 되돌아가서 `%a%a%a%b` 같은 패턴도 시간이 (글자 수 × 패턴 길이)를 넘지 않는다
     * (정규식은 되돌아가기가 폭발할 수 있음). 글자 단위를 코드 포인트로 맞춰 한글·이모지도 한 글자로 센다.
     */
    private static boolean matches(int[] s, int[] p) {
        int i = 0, j = 0, star = -1, mark = 0;
        while (i < s.length) {
            if (j < p.length && p[j] == '%') {
                star = j++;
                mark = i;
            } else if (j < p.length && (p[j] == '_' || p[j] == s[i])) {
                i++;
                j++;
            } else if (star >= 0) {
                j = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (j < p.length && p[j] == '%') j++;
        return j == p.length;
    }
}
