package miniredis.hashtable;

/**
 * SipHash-2-4. 키(시드)를 모르면 같은 버킷에 몰리는 키를 만들 수 없다.
 * Java에는 부호 없는 64비트가 없지만 덧셈·XOR·회전은 long으로 해도 비트 결과가 같다.
 */
public final class SipHash {
    private final long k0;
    private final long k1;

    /** key: 16바이트 시드. 앞 8바이트가 k0, 뒤 8바이트가 k1 (리틀엔디언). */
    public SipHash(byte[] key) {
        if (key.length != 16) throw new IllegalArgumentException("SipHash key must be 16 bytes");
        this.k0 = readLE(key, 0, 8);
        this.k1 = readLE(key, 8, 8);
    }

    public long hash(byte[] m) {
        long[] v = {
            0x736f6d6570736575L ^ k0,
            0x646f72616e646f6dL ^ k1,
            0x6c7967656e657261L ^ k0,
            0x7465646279746573L ^ k1,
        };
        int len = m.length;
        int end = len - (len % 8);
        // 압축: 8바이트씩 섞는다 (c = 2 라운드)
        for (int i = 0; i < end; i += 8) {
            long mi = readLE(m, i, 8);
            v[3] ^= mi;
            round(v);
            round(v);
            v[0] ^= mi;
        }
        // 마지막 블록: 남은 바이트 + 맨 위 바이트에 전체 길이
        long b = ((long) len << 56) | readLE(m, end, len - end);
        v[3] ^= b;
        round(v);
        round(v);
        v[0] ^= b;
        // 마무리 (d = 4 라운드)
        v[2] ^= 0xff;
        for (int i = 0; i < 4; i++) round(v);
        return v[0] ^ v[1] ^ v[2] ^ v[3];
    }

    private static void round(long[] v) {
        v[0] += v[1]; v[1] = Long.rotateLeft(v[1], 13); v[1] ^= v[0]; v[0] = Long.rotateLeft(v[0], 32);
        v[2] += v[3]; v[3] = Long.rotateLeft(v[3], 16); v[3] ^= v[2];
        v[0] += v[3]; v[3] = Long.rotateLeft(v[3], 21); v[3] ^= v[0];
        v[2] += v[1]; v[1] = Long.rotateLeft(v[1], 17); v[1] ^= v[2]; v[2] = Long.rotateLeft(v[2], 32);
    }

    private static long readLE(byte[] b, int off, int n) {
        long r = 0;
        for (int j = 0; j < n; j++) r |= (b[off + j] & 0xFFL) << (8 * j);
        return r;
    }
}
