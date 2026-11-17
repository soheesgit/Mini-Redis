package minidb.type;

import static minidb.type.TypeTestSupport.assertError;
import static minidb.type.TypeTestSupport.schema;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import minidb.sql.SqlException;
import minidb.storage.StorageException;
import org.junit.jupiter.api.Test;

class SchemaBytesTest {

    @Test
    void roundTripKeepsEveryColumnProperty() {
        Schema s = schema("name VARCHAR(20) NOT NULL, price DECIMAL(7,2), 이름 BOOLEAN, d DATE, dt DATETIME, ts TIMESTAMP,"
                + " a INT, b BIGINT, c DOUBLE");
        byte[] bytes = s.toBytes();
        assertEquals(s, Schema.fromBytes(bytes));
        // 메타 페이지에서 읽으면 뒤에 0이 붙어 있다
        assertEquals(s, Schema.fromBytes(Arrays.copyOf(bytes, 8184)));
    }

    @Test
    void layoutOfFirstColumn() {
        byte[] b = schema("x INT NOT NULL").toBytes();
        // 칸 2개 | id: 길이 2, "id", INT=1, n=0, s=0, flags=1 | x ...
        assertEquals(2, b[1]);
        assertEquals(2, b[2]);
        assertEquals(1, b[5]); // id의 타입 코드
        assertEquals(1, b[9]); // id의 NOT NULL flag
    }

    @Test
    void unknownTypeCodeIsStorageError() {
        byte[] b = schema("x INT").toBytes();
        b[5] = 42;
        StorageException e = assertThrows(StorageException.class, () -> Schema.fromBytes(b));
        assertEquals(0, e.page());
    }

    @Test
    void truncatedBytesIsStorageError() {
        byte[] b = schema("x INT").toBytes();
        assertThrows(StorageException.class, () -> Schema.fromBytes(Arrays.copyOf(b, b.length - 3)));
    }

    @Test
    void longColumnNameIsRejectedAtTheName() {
        assertError(() -> schema("a INT, " + "n".repeat(256) + " INT"), 1, 24, "255");
        schema("n".repeat(255) + " INT"); // 255B는 통과
    }

    @Test
    void schemaTooBigForMetaPageIsRejected() {
        StringBuilder cols = new StringBuilder("c0 BOOLEAN");
        for (int i = 1; i < 1500; i++) cols.append(", c").append(i).append(" BOOLEAN");
        SqlException e = assertThrows(SqlException.class, () -> schema(cols.toString()));
        assertTrue(e.getMessage().contains("메타 페이지"), e.getMessage());
    }
}
