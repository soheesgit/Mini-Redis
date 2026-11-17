package minidbms.engine;

import minidbms.cache.RedisCache;
import minidbms.sql.Statement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * SELECT 결과를 Mini Redis에 캐시한다 (cache-aside).
 *
 * 캐시 키는 데이터 경로, 테이블, 파일 버전과 조회 조건으로 구성한다.
 * 변경은 파일 버전을 바꾸므로 캐시를 끈 CLI에서 쓴 데이터도 다음 조회에 나타난다.
 * 이전 결과는 TTL로 만료된다. Redis가 응답하지 않으면 이 실행 동안 파일을 직접 읽는다.
 */
// shortcut: 카운터와 disabledReason은 스레드 안전하지 않다. 단일 스레드 CLI 전제이며, Phase 3에서 엔진을 스레드끼리 공유하면 Atomic/volatile로 바꾼다
final class QueryCache {
    static final long TTL_SECONDS = 60;
    static final int MAX_VALUE_BYTES = 1 << 20; // mini-redis HttpApi.MAX_BODY와 같은 값. 넘으면 서버가 413으로 거부한다

    interface Loader {
        SqlEngine.Selected load() throws IOException;
    }

    private final RedisCache redis;
    private final String namespace;
    private long hits, misses;
    private String disabledReason;

    QueryCache(RedisCache redis, Path dataDirectory) {
        this.redis = redis;
        // 같은 Redis를 쓰는 다른 데이터 폴더(다른 DB)와 키가 섞이지 않게 폴더 경로로 이름 공간을 나눈다
        this.namespace = "dbms:" + sha256(dataDirectory.toAbsolutePath().normalize().toString()).substring(0, 12);
    }

    SqlEngine.Selected select(Statement.Select select, String revision, Loader loader) throws IOException {
        if (disabledReason != null) {
            return loader.load();
        }
        String key;
        try {
            key = tableKey(select.table()) + ":r" + sha256(revision) + ":" + sha256(canonical(select));
            byte[] cached = redis.get(key);
            if (cached != null) {
                hits++;
                return decode(cached);
            }
        } catch (IOException exception) {
            disable(exception);
            return loader.load();
        }
        misses++;
        SqlEngine.Selected result = loader.load();
        byte[] encoded = encode(result);
        if (encoded.length > MAX_VALUE_BYTES) {
            return result; // 너무 큰 결과는 저장만 건너뛴다. 캐시는 다음 SELECT에서 계속 쓴다
        }
        try {
            redis.set(key, encoded, TTL_SECONDS);
        } catch (IOException exception) {
            disable(exception);
        }
        return result;
    }

    String summary() {
        return disabledReason != null
                ? "cache: off (" + disabledReason + "), hits=" + hits + " misses=" + misses
                : "cache: hits=" + hits + " misses=" + misses;
    }

    long hits() {
        return hits;
    }

    long misses() {
        return misses;
    }

    boolean disabled() {
        return disabledReason != null;
    }

    private void disable(IOException exception) {
        disabledReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private String tableKey(String table) {
        return namespace + ":" + table;
    }

    // 값에 구분자가 포함돼도 조건의 경계를 구분할 수 있도록 값의 길이를 기록한다.
    static String canonical(Statement.Select select) {
        var builder = new StringBuilder(select.table()).append('|').append(String.join(",", select.columns()));
        for (Statement.Condition condition : select.where()) {
            String value = condition.value().toString();
            builder.append('|').append(condition.column()).append(':').append(condition.operator()).append(':')
                    .append(condition.value() instanceof Long ? 'L' : 'S')
                    .append(value.length()).append(':').append(value);
        }
        return builder.toString();
    }

    static byte[] encode(SqlEngine.Selected selected) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(selected.columns().size());
            for (String column : selected.columns()) {
                writeText(out, column);
            }
            out.writeInt(selected.rows().size());
            for (List<Object> row : selected.rows()) {
                for (Object value : row) {
                    if (value instanceof Long number) {
                        out.writeByte('L');
                        out.writeLong(number);
                    } else {
                        out.writeByte('S');
                        writeText(out, (String) value);
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    static SqlEngine.Selected decode(byte[] data) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(data))) {
            int columnCount = in.readInt();
            var columns = new ArrayList<String>(columnCount);
            for (int index = 0; index < columnCount; index++) {
                columns.add(readText(in));
            }
            int rowCount = in.readInt();
            var rows = new ArrayList<List<Object>>(rowCount);
            for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
                var row = new ArrayList<Object>(columnCount);
                for (int index = 0; index < columnCount; index++) {
                    int tag = in.readByte();
                    if (tag == 'L') {
                        row.add(in.readLong());
                    } else if (tag == 'S') {
                        row.add(readText(in));
                    } else {
                        throw new IOException("Corrupt cached result.");
                    }
                }
                rows.add(List.copyOf(row));
            }
            return new SqlEngine.Selected(List.copyOf(columns), List.copyOf(rows));
        }
    }

    // writeUTF는 64KB까지라 쓰지 않는다. 행 하나는 1 MiB까지 저장되므로 길이를 int로 적는다
    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        return new String(in.readNBytes(in.readInt()), StandardCharsets.UTF_8);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
