package minidb.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.HexFormat;
import minidb.cache.RedisCache;
import minidb.sql.Ast.*;

/** 소희의 cache-aside 연동을 지호의 AST/Result 계약으로 이식. Database의 문장 잠금 안에서만 호출한다. */
final class QueryCache {
    static final long TTL_SECONDS = 60;
    static final int MAX_VALUE_BYTES = 1 << 20;
    interface Loader { Result load() throws IOException; }
    private final RedisCache redis;
    private final String namespace;
    private long hits, misses;
    private String disabledReason;

    QueryCache(RedisCache redis, Path dir, ZoneId zone) {
        this.redis = redis;
        // v2: 옛 minidbms의 Long/String 직렬화와 호환되지 않는다. 시간대도 WHERE 의미에 영향을 준다.
        namespace = "dbms:v2:" + hash((dir.toString() + "\n" + zone.getId()).getBytes(StandardCharsets.UTF_8));
    }

    Result select(Select select, String revision, Loader loader) throws IOException {
        if (disabledReason != null) return loader.load();
        String key = namespace + ":" + revision + ":" + hash(canonical(select));
        try {
            byte[] bytes = redis.get(key);
            if (bytes != null) {
                Result result = ResultCodec.decode(bytes);
                hits++;
                return result;
            }
        } catch (IOException e) {
            disable(e);
            return loader.load();
        }
        misses++;
        Result result = loader.load(); // SQL/저장소 오류는 캐시 장애로 삼키지 않는다.
        try {
            byte[] bytes = ResultCodec.encode(result);
            if (bytes.length <= MAX_VALUE_BYTES) redis.set(key, bytes, TTL_SECONDS);
        } catch (IOException e) {
            disable(e);
        }
        return result;
    }

    String summary() {
        return "cache: " + (disabledReason == null ? "" : "off (" + disabledReason + "), ")
                + "hits=" + hits + " misses=" + misses;
    }

    private void disable(IOException e) {
        disabledReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static byte[] canonical(Select s) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            text(out, s.table().key());
            out.writeInt(s.columns().size());
            for (Name name : s.columns()) text(out, name.key());
            expression(out, s.where());
            out.writeInt(s.orderBy().size());
            for (OrderBy order : s.orderBy()) {
                text(out, order.column().key());
                out.writeBoolean(order.desc());
            }
            out.writeBoolean(s.limit() != null);
            if (s.limit() != null) out.writeLong(s.limit());
        }
        return bytes.toByteArray();
    }

    // 태그 + 길이로 경계를 보존. 소스 위치는 제외하고 리터럴의 대소문자와 내용은 그대로 둔다.
    private static void expression(DataOutputStream out, Expr e) throws IOException {
        switch (e) {
            case null -> out.writeByte(0);
            case Literal x -> { out.writeByte(1); text(out, x.kind().name()); text(out, x.text()); }
            case Column x -> { out.writeByte(2); text(out, x.name().key()); }
            case Compare x -> { out.writeByte(3); text(out, x.op().name()); expression(out, x.left()); expression(out, x.right()); }
            case And x -> { out.writeByte(4); expression(out, x.left()); expression(out, x.right()); }
            case Or x -> { out.writeByte(5); expression(out, x.left()); expression(out, x.right()); }
            case Not x -> { out.writeByte(6); expression(out, x.expr()); }
            case Between x -> { out.writeByte(7); out.writeBoolean(x.not()); expression(out, x.value()); expression(out, x.low()); expression(out, x.high()); }
            case In x -> { out.writeByte(8); out.writeBoolean(x.not()); expression(out, x.value()); out.writeInt(x.items().size()); for (Operand v : x.items()) expression(out, v); }
            case Like x -> { out.writeByte(9); out.writeBoolean(x.not()); expression(out, x.value()); text(out, x.pattern()); }
            case IsNull x -> { out.writeByte(10); out.writeBoolean(x.not()); expression(out, x.value()); }
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
