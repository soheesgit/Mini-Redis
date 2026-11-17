package minidb.sql;

public enum TokenType {
    KEYWORD,     // text = 대문자로 맞춘 키워드 (SELECT, NULL, ...)
    IDENT,       // text = 쓴 그대로 (테이블·칸 이름, 타입 이름 INT/VARCHAR 등도 여기)
    NUMBER,      // text = 글자 그대로 ("3.14"). 변환은 값 검사 단계에서
    STRING,      // text = 따옴표를 벗긴 값 ('' 는 ' 하나로)
    EQ, NE, LT, LE, GT, GE,
    COMMA, LPAREN, RPAREN, SEMICOLON, STAR, MINUS,
    EOF
}
