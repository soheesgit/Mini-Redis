package minidb.sql;

/**
 * SQL 처리 중 사용자에게 보여 줄 에러. 위치를 알면 "줄:칸"을 메시지 앞에 붙이고,
 * line()·col()로도 꺼낼 수 있다 (위치를 모르면 0)
 */
public class SqlException extends RuntimeException {

    private final int line;
    private final int col;

    public SqlException(String message) {
        super(message);
        this.line = 0;
        this.col = 0;
    }

    public SqlException(int line, int col, String message) {
        super(line + ":" + col + ": " + message);
        this.line = line;
        this.col = col;
    }

    /** 파서가 에러 난 토큰 자리를 그대로 넘기는 용도 */
    public SqlException(Token token, String message) {
        this(token.line(), token.col(), message);
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }
}
