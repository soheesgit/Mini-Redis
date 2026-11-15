package miniredis.debug;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 관찰용 이벤트 기록 (데모 페이지용, 기본 꺼짐). Engine.Config에 Debug를 넣었을 때만 만들어진다.
 *
 * 여러 스레드(핸들러, executor, fsync)가 일이 일어난 그 자리에서 emit()으로 남기고,
 * 읽는 쪽(데모 서버)은 since()로 가져간다. 읽기가 executor를 건드리지 않는다.
 * 고정 크기 링이라 오래된 이벤트는 버려진다(dropped로 알린다).
 */
public final class Trace {
    public static final int CAPACITY = 4096;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String[] ring = new String[CAPACITY];
    private long next = 1; // 다음 이벤트 번호
    private final long t0 = System.nanoTime();
    private final AtomicLong ids = new AtomicLong();
    // 요청 ID를 스레드에 붙여 둔다: 핸들러는 요청마다 가상 스레드 1개, executor는 실행 중인 요청으로 바꿔 끼운다
    private final ThreadLocal<Long> bound = new ThreadLocal<>();

    /** JSON에 그대로 넣을 문자열(이미 JSON인 것). */
    public record Raw(String json) { }

    public long newRequestId() {
        return ids.incrementAndGet();
    }

    public void bind(long id) {
        bound.set(id);
    }

    public void unbind() {
        bound.remove();
    }

    /** 지금 스레드에 붙은 요청 ID. 없으면 0 (틱·복구 같은 요청 없는 일). 관찰 명령은 -1. */
    public long bound() {
        Long id = bound.get();
        return id == null ? 0 : id;
    }

    public synchronized long seq() {
        return next;
    }

    /** kv는 이름, 값 쌍. "req"를 직접 넣지 않으면 스레드에 붙은 요청 ID가 들어간다. */
    public void emit(String type, Object... kv) {
        StringBuilder sb = new StringBuilder(128);
        Thread th = Thread.currentThread();
        String thread = th.isVirtual() ? "handler" : th.getName();
        boolean hasReq = false;
        for (int i = 0; i < kv.length; i += 2) if ("req".equals(kv[i])) hasReq = true;
        sb.append(",\"t\":").append(System.nanoTime() - t0)
            .append(",\"type\":").append(quote(type))
            .append(",\"thread\":").append(quote(thread));
        if (!hasReq) sb.append(",\"req\":").append(bound());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(',').append(quote((String) kv[i])).append(':');
            write(sb, kv[i + 1]);
        }
        sb.append('}');
        synchronized (this) {
            ring[(int) (next % CAPACITY)] = "{\"seq\":" + next + sb;
            next++;
        }
    }

    /** seq가 since보다 큰 이벤트를 최대 limit개. */
    public synchronized String since(long since, int limit) {
        long oldest = Math.max(1, next - CAPACITY);
        long from = Math.max(since + 1, oldest);
        long dropped = Math.max(0, oldest - (since + 1));
        long to = Math.min(next, from + limit);
        StringBuilder sb = new StringBuilder("{\"next\":").append(to - 1)
            .append(",\"dropped\":").append(dropped).append(",\"events\":[");
        for (long s = from; s < to; s++) {
            if (s > from) sb.append(',');
            sb.append(ring[(int) (s % CAPACITY)]);
        }
        return sb.append("]}").toString();
    }

    // ---------------- 라이브러리 없는 작은 JSON 쓰기 ----------------

    public static String json(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof Raw r) sb.append(r.json);
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof byte[] b) sb.append(quote(new String(b, StandardCharsets.UTF_8)));
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(quote(String.valueOf(e.getKey()))).append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else sb.append(quote(o.toString()));
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String hex(byte[] b, int off, int len) {
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 15];
        }
        return new String(out);
    }

    public static String hex(byte[] b) {
        return hex(b, 0, b.length);
    }
}
