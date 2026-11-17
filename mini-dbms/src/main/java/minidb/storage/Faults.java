package minidb.storage;

import java.io.IOException;

/**
 * 테스트가 쓰기 도중에 I/O 실패(또는 죽음)를 흉내 내는 갈고리. 인스턴스마다 따로 달아서(전역 상태 없음) 병렬 테스트가 서로 안 건드린다.
 * 지점: "wal.half"(로그 레코드 앞 절반만 씀), "wal.write"(레코드를 다 썼고 fsync 전), "data.write"(데이터 페이지 i번째를 쓰기 전)
 */
@FunctionalInterface
public interface Faults {

    Faults NONE = (point, i) -> {};

    /** 실패시키려면 IOException을 던진다. i는 "data.write"에서만 의미 있다 (그 커밋의 몇 번째 페이지인지) */
    void at(String point, int i) throws IOException;
}
