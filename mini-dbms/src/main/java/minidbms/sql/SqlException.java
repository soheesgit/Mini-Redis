package minidbms.sql;

import java.io.Serial;

public final class SqlException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
