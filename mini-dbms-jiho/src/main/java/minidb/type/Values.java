package minidb.type;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import minidb.sql.Ast.Literal;
import minidb.sql.Ast;

/**
 * 값 표현 (설계 1.3). 값은 JDK 객체 그대로 쓰고, SQL NULL은 Java {@code null}이다
 * (그래서 행은 {@code List.of}가 아닌 {@code Object[]}).
 *
 * <pre>
 * INT Integer · BIGINT Long · DOUBLE Double · DECIMAL(p,s) BigDecimal(scale = s) · BOOLEAN Boolean
 * VARCHAR String · DATE LocalDate · DATETIME LocalDateTime(시간대 없음) · TIMESTAMP Instant(UTC)
 * </pre>
 *
 * DECIMAL은 equals가 아닌 {@link #compare}로 비교한다: BigDecimal.equals는 1.0과 1.00을 다르다고 본다.
 */
public final class Values {

    private Values() {
    }

    private static final DateTimeFormatter DATETIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE).appendLiteral(' ').append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter().withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter OUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    /**
     * 칸 타입을 모를 때(WHERE의 값)의 리터럴 값. NUMBER는 '.' 없고 BIGINT 안이면 Long, 아니면 BigDecimal (double을 거치지 않음).
     * STRING은 String, BOOLEAN은 Boolean, NULL은 null. 날짜 칸과 비교할 문자열은 {@link #parseDate} 등으로 바꾼다
     */
    public static Object of(Literal lit) {
        String t = lit.text();
        return switch (lit.kind()) {
            case NUMBER -> {
                if (t.indexOf('.') < 0) {
                    try {
                        yield Long.parseLong(t);
                    } catch (NumberFormatException e) {
                        // BIGINT 밖: 아래 BigDecimal로
                    }
                }
                yield new BigDecimal(t);
            }
            case STRING -> t;
            case BOOLEAN -> Ast.Name.key(t).equals("true");
            case NULL -> null;
        };
    }

    /** 비교할 수 있나: 숫자형끼리(INT·BIGINT·DOUBLE·DECIMAL 섞여도 됨), 아니면 같은 타입끼리. null은 넣지 않는다 */
    public static boolean comparable(Object a, Object b) {
        return (a instanceof Number && b instanceof Number) || a.getClass() == b.getClass();
    }

    /**
     * 음수·0·양수. INT·BIGINT·DECIMAL끼리는 정확하게, 한쪽이 DOUBLE이면 double로 비교한다. 문자열은 대소문자 구분.
     * null(SQL NULL)은 받지 않는다: 3값 논리는 평가기(#37)가 한다.
     *
     * @throws IllegalArgumentException {@link #comparable}이 거짓일 때
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            if (isIntegral(x) && isIntegral(y)) return Long.compare(x.longValue(), y.longValue());
            // 한쪽이 DOUBLE이면 double로 (MySQL과 같음). 정확히 비교하면 DOUBLE 칸 0.1과 WHERE의 0.1이 다르다고 나온다
            if (x instanceof Double || y instanceof Double) return Double.compare(x.doubleValue(), y.doubleValue());
            return decimal(x).compareTo(decimal(y));
        }
        if (a.getClass() != b.getClass()) {
            throw new IllegalArgumentException("비교할 수 없는 타입: " + typeName(a) + ", " + typeName(b));
        }
        // shortcut: String.compareTo는 UTF-16 순서라 U+FFFF 밖 글자는 UTF-8(코드 포인트) 순서와 다를 수 있음. 정렬 순서를 바이트와 맞춰야 하면 코드 포인트 비교로
        return ((Comparable) a).compareTo(b);
    }

    private static boolean isIntegral(Number x) {
        return x instanceof Integer || x instanceof Long;
    }

    private static BigDecimal decimal(Number x) {
        return x instanceof BigDecimal d ? d : BigDecimal.valueOf(x.longValue());
    }

    private static String typeName(Object v) {
        return switch (v) {
            case Integer i -> "INT";
            case Long l -> "BIGINT";
            case Double d -> "DOUBLE";
            case BigDecimal d -> "DECIMAL";
            case Boolean b -> "BOOLEAN";
            case String s -> "VARCHAR";
            case LocalDate d -> "DATE";
            case LocalDateTime d -> "DATETIME";
            case Instant i -> "TIMESTAMP";
            default -> v.getClass().getSimpleName();
        };
    }

    /** 'YYYY-MM-DD', 연도 1~9999. 없는 날짜(2026-02-30)는 에러 */
    public static LocalDate parseDate(String text) {
        try {
            return checkYear(LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE), text);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("날짜 형식은 'YYYY-MM-DD': '" + text + "'");
        }
    }

    /** 'YYYY-MM-DD HH:MM[:SS[.ffffff]]' 또는 'YYYY-MM-DD'(00:00:00). 소수 초는 마이크로초(6자리)까지 */
    public static LocalDateTime parseDateTime(String text) {
        LocalDateTime v;
        try {
            v = text.indexOf(' ') < 0
                    ? LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
                    : LocalDateTime.parse(text, DATETIME);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("날짜·시각 형식은 'YYYY-MM-DD HH:MM:SS[.ffffff]': '" + text + "'");
        }
        if (v.getNano() % 1000 != 0) throw new IllegalArgumentException("소수 초는 6자리까지: '" + text + "'");
        checkYear(v.toLocalDate(), text);
        return v;
    }

    /**
     * 서버 시간대의 'YYYY-MM-DD HH:MM:SS' → UTC 순간. 서머타임으로 없는 시각은 그 틈만큼 뒤로 밀고,
     * 두 번 있는 시각은 앞의 것을 고른다 (ZonedDateTime 규칙)
     */
    public static Instant parseTimestamp(String text, ZoneId zone) {
        return parseDateTime(text).atZone(zone).toInstant();
    }

    private static LocalDate checkYear(LocalDate d, String text) {
        if (d.getYear() < 1 || d.getYear() > 9999) throw new IllegalArgumentException("연도는 1~9999: '" + text + "'");
        return d;
    }

    /**
     * 결과 출력용 글자. NULL은 "NULL", BOOLEAN은 TRUE/FALSE, DECIMAL은 소수 s자리 그대로,
     * DATETIME·TIMESTAMP는 'YYYY-MM-DD HH:MM:SS' (마이크로초가 있으면 .ffffff), TIMESTAMP는 zone 시각으로
     */
    public static String format(Object v, ZoneId zone) {
        return switch (v) {
            case null -> "NULL";
            case Boolean b -> b ? "TRUE" : "FALSE";
            case BigDecimal d -> d.toPlainString();
            case LocalDateTime t -> formatDateTime(t);
            case Instant i -> formatDateTime(LocalDateTime.ofInstant(i, zone));
            default -> v.toString();
        };
    }

    private static String formatDateTime(LocalDateTime t) {
        String s = OUT.format(t);
        return t.getNano() == 0 ? s : s + String.format(".%06d", t.getNano() / 1000);
    }
}
