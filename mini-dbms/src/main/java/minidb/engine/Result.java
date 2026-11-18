package minidb.engine;

import java.util.List;

/**
 * 문장 하나의 실행 결과. SELECT는 columns(머리글)와 rows, affected = 행 수, changedTable = null.
 * 나머지는 columns·rows = null, affected = 영향받은 행 수, changedTable = 바뀐 테이블 키 (Phase 3 캐시 무효화가 쓴다).
 */
public record Result(List<String> columns, List<Object[]> rows, long affected, String changedTable) {}
