package miniredis.hashtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SipHashTest {
    // 키 = 00 01 .. 0f, 메시지 = 00 01 .. (n-1). 공개 테스트 벡터 (SipHash 논문 부록, 참조 구현 vectors.h)
    private static byte[] seq(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) i;
        return b;
    }

    private final SipHash sip = new SipHash(seq(16));

    @Test
    void publicTestVectors() {
        assertEquals(0x726fdb47dd0e0e31L, sip.hash(seq(0)));  // 빈 메시지: 마지막 블록만
        assertEquals(0x74f839c593dc67fdL, sip.hash(seq(1)));
        assertEquals(0x93f5f5799a932462L, sip.hash(seq(8)));  // 블록 경계 딱 맞음
        assertEquals(0xa129ca6149be45e5L, sip.hash(seq(15))); // 논문에 나온 예시
    }

    @Test
    void keyMustBe16Bytes() {
        assertThrows(IllegalArgumentException.class, () -> new SipHash(new byte[15]));
    }
}
