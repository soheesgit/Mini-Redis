package minidb.sql;

/** line, col은 1부터. 에러 메시지에서 위치를 알려 주는 데 씀 */
public record Token(TokenType type, String text, int line, int col) {

    public boolean isKeyword(String kw) {
        return type == TokenType.KEYWORD && text.equals(kw);
    }

    @Override
    public String toString() {
        return type == TokenType.EOF ? "EOF" : type + "(" + text + ")";
    }
}
