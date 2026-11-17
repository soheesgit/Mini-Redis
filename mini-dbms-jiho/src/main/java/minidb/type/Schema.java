package minidb.type;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import minidb.sql.Ast.Assignment;
import minidb.sql.Ast.ColumnDef;
import minidb.sql.Ast.ColumnType;
import minidb.sql.Ast.CreateTable;
import minidb.sql.Ast.Literal;
import minidb.sql.Ast.Name;
import minidb.sql.SqlException;

/**
 * 테이블 스키마: 칸 목록, 0번은 언제나 자동 id 칸 (설계 1.1). 정의 검사, INSERT·UPDATE 값 검사,
 * 행 인코딩(값 배열 ↔ 바이트, 설계 1.6)을 맡는다. 바이트 행을 페이지 슬롯에 넣고 빼는 일은 #25.
 *
 * @param columns id 칸을 포함한 전체 칸. 메타 페이지에서 읽어 만들 때는 검사하지 않는다 (define이 검사한 것을 저장했으므로)
 */
public record Schema(List<Column> columns) {

    /** 행 최대 크기 = 8192 − 16(헤더) − 4(슬롯) (설계 1.6) */
    public static final int MAX_ROW_SIZE = 8172;
    public static final Column ID = new Column("id", ColumnType.INT, 0, 0, true);
    /** DECIMAL은 unscaled 값을 8B long에 담으므로 p ≤ 18 (MySQL ER 1427과 같은 s ≤ p 규칙도 같이) */
    public static final int MAX_DECIMAL_PRECISION = 18;

    public Schema {
        columns = List.copyOf(columns);
    }

    /**
     * CREATE TABLE을 검사하고 맨 앞에 id 칸을 붙인다. 에러 위치는 문제의 칸 이름.
     * 거부: id 칸 선언(대소문자 무관), 같은 이름 칸, VARCHAR(0), DECIMAL p ∉ [1, 18] 또는 s > p,
     * 행 최대 크기(비트맵 + Σ칸 최대 크기) > 8172B (그 크기를 처음 넘긴 칸에서)
     */
    public static Schema define(CreateTable ct) {
        List<ColumnDef> defs = ct.columns();
        List<Column> cols = new ArrayList<>();
        cols.add(ID);
        long size = bitmapSize(defs.size() + 1) + ID.maxSize();
        Set<String> seen = new HashSet<>();
        for (ColumnDef d : defs) {
            Name name = d.name();
            if (name.key().equals("id")) throw err(name, "id 칸은 자동으로 붙으므로 선언할 수 없음");
            if (!seen.add(name.key())) throw err(name, "같은 이름의 칸이 이미 있음: " + name.text());
            int n = 0, s = 0;
            if (d.type() == ColumnType.VARCHAR) {
                n = d.args().get(0);
                if (n < 1) throw err(name, "VARCHAR 글자 수는 1 이상: VARCHAR(" + n + ")");
            } else if (d.type() == ColumnType.DECIMAL) {
                n = d.args().get(0);
                s = d.args().get(1);
                if (n < 1 || n > MAX_DECIMAL_PRECISION) {
                    throw err(name, "DECIMAL 전체 자릿수는 1~" + MAX_DECIMAL_PRECISION + ": DECIMAL(" + n + "," + s + ")");
                }
                if (s > n) throw err(name, "DECIMAL 소수 자릿수는 전체 자릿수 이하: DECIMAL(" + n + "," + s + ")");
            }
            Column c = new Column(name.text(), d.type(), n, s, d.notNull());
            size += c.maxSize();
            if (size > MAX_ROW_SIZE) {
                throw err(name, "행 최대 크기가 " + MAX_ROW_SIZE + "B를 넘음 (이 칸까지 " + size + "B)");
            }
            cols.add(c);
        }
        return new Schema(cols);
    }

    /** 이름(대소문자 무관)으로 칸 번호. 없으면 -1. id는 0 */
    public int indexOf(String name) {
        String key = Name.key(name);
        for (int i = 0; i < columns.size(); i++) {
            if (Name.key(columns.get(i).name()).equals(key)) return i;
        }
        return -1;
    }

    /** 비트맵 + Σ칸 최대 크기. define을 거친 스키마면 ≤ 8172 */
    public int maxRowSize() {
        return (int) (bitmapSize(columns.size()) + columns.stream().mapToLong(Column::maxSize).sum());
    }

    /**
     * INSERT 값 행 하나 검사 (설계 1.7①). 값은 id를 뺀 칸 순서대로.
     *
     * @return 칸 개수 길이의 행. [0](id)은 null로 두니 실행기가 nextId를 채운다
     */
    public Object[] checkInsert(List<Literal> values) {
        int need = columns.size() - 1;
        if (values.size() != need) {
            String msg = "값 개수가 칸 개수와 다름: 칸 " + need + "개(id 제외), 값 " + values.size() + "개";
            if (values.isEmpty()) throw new SqlException(msg);
            Literal at = values.size() > need ? values.get(need) : values.get(0); // 남으면 첫 남는 값, 모자라면 행 첫 값
            throw new SqlException(at.line(), at.col(), msg);
        }
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < need; i++) row[i + 1] = columns.get(i + 1).convert(values.get(i));
        return row;
    }

    /**
     * UPDATE SET 검사 (설계 1.9②). 없는 칸, id 칸, 같은 칸 두 번은 그 칸 이름 위치로 에러.
     *
     * @return 칸 번호 → 새 값 (SET에 쓴 순서, 값은 NULL이면 null)
     */
    public Map<Integer, Object> checkUpdate(List<Assignment> sets) {
        Map<Integer, Object> out = new LinkedHashMap<>();
        for (Assignment a : sets) {
            Name name = a.column();
            int i = indexOf(name.text());
            if (i < 0) throw err(name, "없는 칸: " + name.text());
            if (i == 0) throw err(name, "id 칸은 고칠 수 없음");
            if (out.containsKey(i)) throw err(name, "같은 칸을 두 번 SET: " + name.text());
            out.put(i, columns.get(i).convert(a.value()));
        }
        return out;
    }

    private static SqlException err(Name at, String msg) {
        return new SqlException(at.line(), at.col(), msg);
    }

    private static int bitmapSize(int columnCount) {
        return (columnCount + 7) / 8;
    }

    // ───── 행 인코딩 (설계 1.6) ─────
    // [NULL 비트맵][칸0][칸1]… 빅엔디언. i번 칸이 NULL이면 비트맵 i/8번 바이트의 (i % 8)번 비트(1 << i%8) = 1

    /** 검사를 거친 값 행(id 포함, {@link Values}의 표현)을 바이트로. 길이는 maxRowSize() 이하 */
    public byte[] encode(Object[] row) {
        if (row.length != columns.size()) {
            throw new IllegalArgumentException("행 길이 " + row.length + " ≠ 칸 개수 " + columns.size());
        }
        int bitmap = bitmapSize(row.length);
        ByteBuffer buf = ByteBuffer.allocate(maxRowSize()); // 기본이 빅엔디언
        buf.position(bitmap);
        for (int i = 0; i < row.length; i++) {
            Object v = row[i];
            if (v == null) {
                buf.put(i / 8, (byte) (buf.get(i / 8) | 1 << (i % 8)));
                continue;
            }
            Column c = columns.get(i);
            switch (c.type()) {
                case INT -> buf.putInt((Integer) v);
                case BIGINT -> buf.putLong((Long) v);
                case DOUBLE -> buf.putDouble((Double) v);
                case DECIMAL -> buf.putLong(((BigDecimal) v).setScale(c.s()).unscaledValue().longValueExact());
                case BOOLEAN -> buf.put((byte) ((Boolean) v ? 1 : 0));
                case VARCHAR -> {
                    byte[] b = ((String) v).getBytes(StandardCharsets.UTF_8);
                    buf.putShort((short) b.length).put(b);
                }
                case DATE -> buf.putInt(Math.toIntExact(((LocalDate) v).toEpochDay()));
                case DATETIME -> {
                    LocalDateTime t = (LocalDateTime) v;
                    buf.putLong(micros(t.toEpochSecond(ZoneOffset.UTC), t.getNano()));
                }
                case TIMESTAMP -> {
                    Instant t = (Instant) v;
                    buf.putLong(micros(t.getEpochSecond(), t.getNano()));
                }
            }
        }
        return Arrays.copyOf(buf.array(), buf.position());
    }

    public Object[] decode(byte[] row) {
        return decode(row, 0, row.length);
    }

    /** 바이트 행을 값 행으로. 페이지 배열의 (offset, length) 조각을 복사 없이 읽는다 */
    public Object[] decode(byte[] bytes, int offset, int length) {
        ByteBuffer buf = ByteBuffer.wrap(bytes, offset, length).slice();
        int count = columns.size();
        buf.position(bitmapSize(count));
        Object[] row = new Object[count];
        for (int i = 0; i < count; i++) {
            if ((buf.get(i / 8) & 1 << (i % 8)) != 0) continue;
            Column c = columns.get(i);
            row[i] = switch (c.type()) {
                case INT -> buf.getInt();
                case BIGINT -> buf.getLong();
                case DOUBLE -> buf.getDouble();
                case DECIMAL -> BigDecimal.valueOf(buf.getLong(), c.s());
                case BOOLEAN -> buf.get() != 0;
                case VARCHAR -> {
                    byte[] b = new byte[buf.getShort() & 0xFFFF];
                    buf.get(b);
                    yield new String(b, StandardCharsets.UTF_8);
                }
                case DATE -> LocalDate.ofEpochDay(buf.getInt());
                case DATETIME -> {
                    long m = buf.getLong();
                    yield LocalDateTime.ofEpochSecond(Math.floorDiv(m, 1_000_000), nanos(m), ZoneOffset.UTC);
                }
                case TIMESTAMP -> {
                    long m = buf.getLong();
                    yield Instant.ofEpochSecond(Math.floorDiv(m, 1_000_000), nanos(m));
                }
            };
        }
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException("행 바이트가 스키마보다 " + buf.remaining() + "B 김");
        }
        return row;
    }

    /** 1970-01-01 00:00:00부터 마이크로초. 1970년 이전은 음수 */
    private static long micros(long epochSecond, int nano) {
        return Math.addExact(Math.multiplyExact(epochSecond, 1_000_000L), nano / 1000);
    }

    private static int nanos(long micros) {
        return (int) Math.floorMod(micros, 1_000_000L) * 1000;
    }
}
