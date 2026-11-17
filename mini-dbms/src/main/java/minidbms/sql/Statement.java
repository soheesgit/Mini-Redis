package minidbms.sql;

import java.util.List;
import java.util.Optional;

/** sealed와 permits는 Statement를 구현할 타입을 Insert와 Select로 제한한다. */
public sealed interface Statement permits Statement.Insert, Statement.Select {
    // 두 문장에 공통인 테이블 이름 조회 메서드다.
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
    record Select(String table, List<String> columns, Optional<Condition> where) implements Statement {
        public Select {
            columns = List.copyOf(columns);
        }
    }

    // Phase 1의 WHERE는 열 하나와 리터럴 하나를 비교하는 동등 조건이다.
    record Condition(String column, Object value) {}
}
