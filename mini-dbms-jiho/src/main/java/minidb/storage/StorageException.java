package minidb.storage;

/**
 * 파일 형식이 틀렸거나 페이지가 깨졌을 때 (I/O 자체 실패는 IOException).
 * 메시지 앞에 "N번 페이지: "를 붙이고, page()로도 꺼낼 수 있다
 */
public class StorageException extends RuntimeException {

    private final int page;

    public StorageException(int page, String message) {
        super(page + "번 페이지: " + message);
        this.page = page;
    }

    public int page() {
        return page;
    }
}
