package minidbms.engine;

import minidbms.cache.RedisCache;
import minidbms.sql.SqlException;
import minidbms.sql.SqlParser;
import minidbms.sql.Statement;
import minidbms.storage.TableFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 파싱된 문장을 스키마에 맞게 검증하고 테이블 파일을 조회하거나 변경한다. */
public final class SqlEngine {
    // 카탈로그는 미리 정의한 테이블과 파일의 열 순서를 기록한다. 첫 열은 BIGINT ID이고 이후 열은 TEXT다.
    private static final Map<String, List<String>> CATALOG =
        Map.of("users", List.of("id", "name", "email"));

    // 실행 결과를 구조화해 콜백으로 전달한다. 터미널에 어떻게 표시할지는 호출자가 결정한다.
    public sealed interface Result permits Inserted, Selected, Updated, Deleted {}

    public record Inserted(long id) implements Result {}

    public record Updated(int count) implements Result {}

    public record Deleted(int count) implements Result {}

    public record Selected(List<String> columns, List<List<Object>> rows) implements Result {}

    private final Path dataDirectory;
    private final Map<String, TableFile> tableFiles = new HashMap<>();
    private final QueryCache cache; // null이면 캐시 없이 파일만 쓴다

    public SqlEngine(Path dataDirectory) {
        this(dataDirectory, null);
    }

    /** redis가 null이 아니면 SELECT 결과를 Mini Redis에 캐시한다 */
    public SqlEngine(Path dataDirectory, RedisCache redis) {
        this.dataDirectory = dataDirectory;
        this.cache = redis == null ? null : new QueryCache(redis, dataDirectory);
    }

    /** 캐시를 쓰면 "cache: hits=.. misses=..", 안 쓰면 null */
    public String cacheSummary() {
        return cache == null ? null : cache.summary();
    }

    public void execute(String sql, Consumer<Result> onResult) throws IOException {
        // 전체 스크립트의 구문 검사를 먼저 끝내므로 뒤 문장에 구문 오류가 있어도 파일에 쓰지 않는다.
        List<Statement> statements = SqlParser.parse(sql);
        if (statements.isEmpty()) {
            throw new SqlException("SQL file is empty.");
        }
        for (Statement statement : statements) {
            List<String> schema = CATALOG.get(statement.table());
            if (schema == null) {
                throw new SqlException("Unknown table '" + statement.table() + "'.");
            }
            // 같은 실행기에서 테이블 객체를 재사용하면 다음 삽입 때 파일을 다시 훑을 필요가 없다.
            TableFile tableFile = tableFiles.computeIfAbsent(statement.table(),
                    name -> new TableFile(dataDirectory.resolve(name + ".tbl"), schema.size() - 1));
            Result result = switch (statement) {
                case Statement.Insert insert -> insert(insert, schema, tableFile);
                case Statement.Update update -> update(update, schema, tableFile);
                case Statement.Delete delete -> new Deleted(tableFile.delete(delete.id()) ? 1 : 0);
                case Statement.Select select -> cache == null
                        ? select(select, schema, tableFile)
                        : cache.select(select, tableFile.revision(), () -> select(select, schema, tableFile));
            };
            // 문장별로 결과를 전달한다. 이후 문장의 스키마 오류가 이미 저장한 행을 되돌리지는 않는다.
            onResult.accept(result);
        }
    }

    private static Result insert(Statement.Insert insert, List<String> schema, TableFile tableFile) throws IOException {
        if (insert.columns().size() != insert.values().size()) {
            throw new SqlException("INSERT column and value counts differ.");
        }

        // INSERT에 적힌 순서와 관계없이 TEXT 값을 스키마의 저장 순서에 맞춘다. ID는 저장소가 채운다.
        String[] textValues = new String[schema.size() - 1];
        for (int item = 0; item < insert.columns().size(); item++) {
            String column = insert.columns().get(item);
            int position = columnIndex(schema, column);
            if (position == 0) {
                throw new SqlException("Column 'id' is generated automatically.");
            }
            if (textValues[position - 1] != null) {
                throw new SqlException("Duplicate INSERT column '" + column + "'.");
            }
            if (!(insert.values().get(item) instanceof String text)) {
                throw new SqlException("Column '" + column + "' requires TEXT.");
            }
            textValues[position - 1] = text;
        }
        for (int index = 0; index < textValues.length; index++) {
            if (textValues[index] == null) {
                throw new SqlException("Missing INSERT column '" + schema.get(index + 1) + "'.");
            }
        }
        return new Inserted(tableFile.append(List.of(textValues)));
    }

    private static Result update(Statement.Update update, List<String> schema, TableFile tableFile) throws IOException {
        int position = columnIndex(schema, update.column());
        if (position == 0) {
            throw new SqlException("Column 'id' cannot be updated.");
        }
        if (!(update.value() instanceof String text)) {
            throw new SqlException("Column '" + update.column() + "' requires TEXT.");
        }
        return new Updated(tableFile.update(update.id(), position - 1, text) ? 1 : 0);
    }

    private static Selected select(Statement.Select select, List<String> schema, TableFile tableFile) throws IOException {
        List<String> columns = select.columns().isEmpty() ? schema : select.columns();
        // 출력할 열의 위치를 한 번 계산한다. 배열 순서가 SELECT에 적힌 출력 순서다.
        int[] projection = columns.stream().mapToInt(column -> columnIndex(schema, column)).toArray();

        var filters = new ArrayList<Filter>();
        for (Statement.Condition condition : select.where()) {
            int position = columnIndex(schema, condition.column());
            boolean validType = position == 0 ? condition.value() instanceof Long : condition.value() instanceof String;
            if (!validType) {
                String type = position == 0 ? "BIGINT" : "TEXT";
                throw new SqlException("Column '" + condition.column() + "' requires " + type + ".");
            }
            if (position != 0 && condition.operator() != Statement.Operator.EQ
                    && condition.operator() != Statement.Operator.NE) {
                throw new SqlException("TEXT comparisons support only =, != and <>.");
            }
            filters.add(new Filter(position, condition));
        }

        var rows = new ArrayList<List<Object>>();
        // WHERE의 모든 비교를 통과한 행에서 요청한 열만 꺼낸다.
        tableFile.forEachRow(row -> {
            for (Filter filter : filters) {
                if (!filter.matches(row)) {
                    return;
                }
            }
            var projected = new ArrayList<Object>(projection.length);
            for (int position : projection) {
                projected.add(valueAt(row, position));
            }
            rows.add(List.copyOf(projected));
        });
        return new Selected(columns, List.copyOf(rows));
    }

    private record Filter(int position, Statement.Condition condition) {
        boolean matches(TableFile.Row row) {
            Object actual = valueAt(row, position);
            return switch (condition.operator()) {
                case EQ -> actual.equals(condition.value());
                case NE -> !actual.equals(condition.value());
                case LT -> row.id() < (Long) condition.value();
                case LE -> row.id() <= (Long) condition.value();
                case GT -> row.id() > (Long) condition.value();
                case GE -> row.id() >= (Long) condition.value();
            };
        }
    }

    private static int columnIndex(List<String> schema, String column) {
        int position = schema.indexOf(column);
        if (position < 0) {
            throw new SqlException("Unknown column '" + column + "'.");
        }
        return position;
    }

    private static Object valueAt(TableFile.Row row, int position) {
        return position == 0 ? row.id() : row.textValues().get(position - 1);
    }
}
