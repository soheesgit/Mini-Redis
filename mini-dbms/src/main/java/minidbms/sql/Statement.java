package minidbms.sql;

import java.util.List;

/** sealed와 permits는 Statement를 구현할 타입을 아래 네 가지 문장으로 제한한다. */
public sealed interface Statement permits Statement.Insert, Statement.Select, Statement.Update, Statement.Delete {
    // 모든 문장에 공통인 테이블 이름 조회 메서드다.
    String table();

    // record는 필드와 table() 같은 접근 메서드를 만든다.
    // implements Statement 덕분에 Insert 객체를 Statement 변수에 담을 수 있다.
    // 두 리스트에서 같은 위치의 열과 값이 한 쌍이다. 실행기는 열 이름으로 스키마 위치를 찾는다.
    record Insert(String table, List<String> columns, List<Object> values) implements Statement {
        // 간결 생성자에서 리스트를 복사해 원본이 수정되어도 이 문장을 보호한다.
        public Insert {
            columns = List.copyOf(columns);
            values = List.copyOf(values);
        }
    }

    // 빈 columns는 SELECT *, 빈 where는 WHERE 조건이 없는 문장을 뜻한다.
    record Select(String table, List<String> columns, List<Condition> where) implements Statement {
        public Select {
            columns = List.copyOf(columns);
            where = List.copyOf(where);
        }
    }

    // 수정과 삭제는 필수 ID 조건으로 최대 한 행을 지정한다.
    record Update(String table, String column, Object value, long id) implements Statement {}
    record Delete(String table, long id) implements Statement {}

    enum Operator { EQ, NE, LT, LE, GT, GE }

    // SELECT의 WHERE는 이런 비교를 AND로 연결한다.
    record Condition(String column, Operator operator, Object value) {}
}
