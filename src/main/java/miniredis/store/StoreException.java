package miniredis.store;

/** 명령 실패. code는 HTTP 상태 코드와 같은 숫자를 쓴다 (400 입력 오류, 404 키 없음, 409, 503, 507). */
public final class StoreException extends RuntimeException {
    public final int code;

    public StoreException(int code, String message) {
        super(message);
        this.code = code;
    }
}
