package minidbms.sql;

import java.util.List;
import java.util.Optional;

public sealed interface Statement permits Statement.Insert, Statement.Select {
    String table();

    record Insert(String table, List<String> columns, List<Object> values) implements Statement {
        public Insert {
            columns = List.copyOf(columns);
            values = List.copyOf(values);
        }
    }

    // An empty projection represents SELECT *.
    record Select(String table, List<String> columns, Optional<Condition> where) implements Statement {
        public Select {
            columns = List.copyOf(columns);
        }
    }

    record Condition(String column, Object value) {}
}
