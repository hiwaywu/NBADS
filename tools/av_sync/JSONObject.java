package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/** The slice of org.json that Relay.java and Harness.java use, so the check needs no download. */
public class JSONObject {
    private final Map<String, Object> m = new LinkedHashMap<String, Object>();

    public JSONObject put(String k, Object v) { m.put(k, v); return this; }
    public Object opt(String k) { return m.get(k); }
    public JSONObject optJSONObject(String k) { Object v = m.get(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public long optLong(String k) { Object v = m.get(k); return v instanceof Number ? ((Number) v).longValue() : 0L; }
    public int optInt(String k) { Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public String optString(String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v); }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (b.length() > 1) b.append(',');
            b.append(quote(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) b.append("null");
            else if (v instanceof Number || v instanceof Boolean || v instanceof JSONObject) b.append(v);
            else b.append(quote(String.valueOf(v)));
        }
        return b.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
