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

public final class SqlEngine {
    private static final Map<String, List<String>> CATALOG = Map.of("users", List.of("id", "name", "email"));

    public sealed interface Result permits Inserted, Selected {}

    public record Inserted(long id) implements Result {}

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
        List<Statement> statements = SqlParser.parse(sql);
        if (statements.isEmpty()) {
            throw new SqlException("SQL file is empty.");
        }
        for (Statement statement : statements) {
            List<String> schema = CATALOG.get(statement.table());
            if (schema == null) {
                throw new SqlException("Unknown table '" + statement.table() + "'.");
            }
            TableFile tableFile = tableFiles.computeIfAbsent(statement.table(),
                    name -> new TableFile(dataDirectory.resolve(name + ".tbl"), schema.size() - 1));
            Result result = switch (statement) {
                case Statement.Insert insert -> {
                    Result inserted = insert(insert, schema, tableFile);
                    if (cache != null) {
                        cache.inserted(insert.table()); // 파일에 쓴 뒤 버전을 올린다
                    }
                    yield inserted;
                }
                case Statement.Select select -> cache == null
                        ? select(select, schema, tableFile)
                        : cache.select(select, () -> select(select, schema, tableFile));
            };
            onResult.accept(result);
        }
    }

    private static Result insert(Statement.Insert insert, List<String> schema, TableFile tableFile) throws IOException {
        if (insert.columns().size() != insert.values().size()) {
            throw new SqlException("INSERT column and value counts differ.");
        }

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

    private static Selected select(Statement.Select select, List<String> schema, TableFile tableFile) throws IOException {
        List<String> columns = select.columns().isEmpty() ? schema : select.columns();
        int[] projection = columns.stream().mapToInt(column -> columnIndex(schema, column)).toArray();

        int filterColumn = -1;
        Object filterValue = null;
        if (select.where().isPresent()) {
            Statement.Condition condition = select.where().orElseThrow();
            filterColumn = columnIndex(schema, condition.column());
            filterValue = condition.value();
            boolean validType = filterColumn == 0 ? filterValue instanceof Long : filterValue instanceof String;
            if (!validType) {
                String type = filterColumn == 0 ? "BIGINT" : "TEXT";
                throw new SqlException("Column '" + condition.column() + "' requires " + type + ".");
            }
        }

        int matchColumn = filterColumn;
        Object matchValue = filterValue;
        var rows = new ArrayList<List<Object>>();
        tableFile.forEachRow(row -> {
            if (matchColumn < 0 || valueAt(row, matchColumn).equals(matchValue)) {
                var projected = new ArrayList<Object>(projection.length);
                for (int position : projection) {
                    projected.add(valueAt(row, position));
                }
                rows.add(List.copyOf(projected));
            }
        });
        return new Selected(columns, List.copyOf(rows));
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
