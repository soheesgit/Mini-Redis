package minidb.api;

import java.time.ZoneId;
import minidb.engine.Result;
import minidb.type.Values;

final class Json {
    private Json() {}

    static String result(Result result, ZoneId zone) {
        if (result.columns() == null) return "{\"affected\":" + result.affected() + "}";
        StringBuilder json = new StringBuilder("{\"columns\":[");
        for (int col = 0; col < result.columns().size(); col++) {
            if (col > 0) json.append(',');
            quoted(json, result.columns().get(col));
        }
        json.append("],\"rows\":[");
        for (int row = 0; row < result.rows().size(); row++) {
            if (row > 0) json.append(',');
            json.append('[');
            Object[] cells = result.rows().get(row);
            for (int col = 0; col < cells.length; col++) {
                if (col > 0) json.append(',');
                Object value = cells[col];
                if (value == null) json.append("null");
                else quoted(json, Values.format(value, zone));
            }
            json.append(']');
        }
        return json.append("]}").toString();
    }

    static String error(String message, int line, int col) {
        StringBuilder json = new StringBuilder("{\"error\":");
        quoted(json, message);
        if (line > 0) json.append(",\"line\":").append(line).append(",\"col\":").append(col);
        return json.append('}').toString();
    }

    private static void quoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append("\\u00")
                            .append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
