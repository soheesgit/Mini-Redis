package minidb.sql;

/** SQL 처리 중 사용자에게 보여 줄 에러. 위치를 알면 "줄:칸"을 앞에 붙인다 */
public class SqlException extends RuntimeException {

    public SqlException(String message) {
        super(message);
    }

    public SqlException(int line, int col, String message) {
        super(line + ":" + col + ": " + message);
    }
}
