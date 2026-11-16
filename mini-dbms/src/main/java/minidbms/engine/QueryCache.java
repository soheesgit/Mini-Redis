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
 * 키:   dbms:{데이터 폴더 해시}:{테이블}:v{버전}:{SELECT 문의 SHA-256}
 * 버전: dbms:{데이터 폴더 해시}:{테이블}:ver   (INSERT마다 INCR)
 *
 * INSERT는 지울 키를 하나하나 찾지 않고 버전만 올린다. 다음 SELECT는 새 버전 키를 보므로 옛 결과는 다시 쓰이지 않고 TTL로 사라진다.
 * 버전은 파일을 읽기 "전에" 읽는다. 그래야 조회 도중 다른 프로세스가 INSERT해도 그 결과가 옛 버전 키에만 저장된다.
 * Redis가 응답하지 않으면 이 실행 동안 캐시를 끄고 파일에서 바로 읽는다. 캐시는 성능 장치일 뿐, 정답은 항상 파일이다.
 */
final class QueryCache {
    static final long TTL_SECONDS = 60;

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

    SqlEngine.Selected select(Statement.Select select, Loader loader) throws IOException {
        if (disabledReason != null) {
            return loader.load();
        }
        String key;
        try {
            key = tableKey(select.table()) + ":v" + version(select.table()) + ":" + sha256(canonical(select));
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
        try {
            redis.set(key, encode(result), TTL_SECONDS);
        } catch (IOException exception) {
            disable(exception);
        }
        return result;
    }

    /**
     * 파일에 쓴 뒤 부른다.
     * shortcut: INCR이 실패하면(쓰기 직후 Redis가 잠깐 끊김) 옛 결과가 최대 TTL(60초)까지 보일 수 있다. 정확성이 더 중요해지면 INSERT를 실패시키거나 재시도한다.
     */
    void inserted(String table) {
        if (disabledReason != null) {
            return;
        }
        try {
            redis.incr(tableKey(table) + ":ver");
        } catch (IOException exception) {
            disable(exception);
        }
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

    // shortcut: 버전 키에 TTL이 없어서 Mini Redis를 allkeys-lru로 띄우면 버전 키가 밀려나 옛 결과가 다시 보일 수 있다. 기본 noeviction에서는 안전하다.
    private long version(String table) throws IOException {
        byte[] value = redis.get(tableKey(table) + ":ver");
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(new String(value, StandardCharsets.UTF_8));
        } catch (NumberFormatException exception) {
            throw new IOException("Unexpected cache version value.", exception);
        }
    }

    /** 같은 질의면 같은 문자열. 열 이름에는 '|'와 ','가 없고 값은 맨 끝이라 경계가 겹치지 않는다. */
    static String canonical(Statement.Select select) {
        var builder = new StringBuilder(select.table()).append('|').append(String.join(",", select.columns())).append('|');
        select.where().ifPresent(condition -> builder.append(condition.column()).append('=')
                .append(condition.value() instanceof Long ? 'L' : 'S').append(condition.value()));
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
