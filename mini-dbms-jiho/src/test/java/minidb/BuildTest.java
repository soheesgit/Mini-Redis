package minidb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// 빌드와 테스트 실행 환경 확인용. #22부터 실제 테스트가 생기면 지운다.
class BuildTest {
    @Test
    void javaVersionIs25OrHigher() {
        assertEquals(true, Runtime.version().feature() >= 25);
    }
}
