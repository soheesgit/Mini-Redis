package minidb.storage;

import java.io.IOException;

/**
 * 인스턴스별 저장소 테스트 지점. 로그 중간은 "wal.half", fsync 전은 "wal.write",
 * 실행기의 페이지 읽기 전은 "data.read", 커밋 중 페이지 쓰기 전은 "data.write"다.
 */
@FunctionalInterface
public interface Faults {

    Faults NONE = (point, i) -> {};

    /** 읽을 페이지 번호 또는 커밋 내 쓰기 순번을 받는다. I/O 실패는 IOException을 던져 흉내 낸다. */
    void at(String point, int i) throws IOException;
}
