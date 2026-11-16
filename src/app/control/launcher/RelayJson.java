package app.control.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader for relay responses — just enough to parse the
 * protocol's fixed shapes ({channels:[…]}, {messages:[…]}, error
 * bodies) without org.json, so {@link RelayCore} runs identically in
 * the app and in the plain-JVM harness (android.jar's org.json stubs
 * throw off-device). Values come back as Map / List / String / Long /
 * Double / Boolean / null.
 */
public final class RelayJson {

    private final String s;
    private int pos;

    private RelayJson(String s) {
        this.s = s == null ? "" : s;
    }

    public static Object parse(String text) {
        RelayJson p = new RelayJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    public static long lng(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? fallback : Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return fallback;
        }
    }

    public static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(str(m, key));
    }

    // ---- recursive descent ----

    private void ws() {
        while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
    }

    private Object value() {
        if (pos >= s.length()) return null;
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) {
        if (s.startsWith(word, pos)) pos += word.length();
        else throw new IllegalArgumentException("bad JSON at " + pos);
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
        while (true) {
            ws();
            String key = string();
            ws();
            if (pos < s.length() && s.charAt(pos) == ':') pos++;
            ws();
            m.put(key, value());
            ws();
            if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
            if (pos < s.length() && s.charAt(pos) == '}') pos++;
            return m;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        ws();
        if (pos < s.length() && s.charAt(pos) == ']') { pos++; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            if (pos < s.length() && s.charAt(pos) == ',') { pos++; continue; }
            if (pos < s.length() && s.charAt(pos) == ']') pos++;
            return list;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++; // opening quote
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\' && pos < s.length()) {
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(
                                    s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        break;
                    default: sb.append(e); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object number() {
        int start = pos;
        while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
        String n = s.substring(start, pos);
        try {
            if (n.contains(".") || n.contains("e") || n.contains("E")) {
                return Double.parseDouble(n);
            }
            return Long.parseLong(n);
        } catch (Exception e) {
            return 0L;
        }
    }
}
