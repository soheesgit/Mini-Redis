package minidbms.sql;

import java.io.Serial;

/** 파서가 발견한 구문 오류나 실행기가 발견한 스키마 오류를 호출자에게 전달한다. */
public final class SqlException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public SqlException(String message) {
        super(message);
    }
}
