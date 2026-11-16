package app.control.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java codec + diff for the persisted installed-app list
 * (v1.9). The cache file is a JSON array of objects with string
 * fields "p" (package) and "l" (label):
 *
 *   [{"p":"com.example","l":"Example"}, ...]
 *
 * No Android imports and no JSON library — the parser below is
 * hand-rolled for exactly this shape and rejects anything else,
 * so a corrupt cache file is detected instead of half-read. A
 * corrupt file means "scan again", never "show wrong data". The
 * file carries no icons: icon bitmaps stay lazy and in-memory
 * only (see InstalledApps.iconFor), so a stale cache can never
 * show a stale icon.
 *
 * Order is the scan's Collator order, preserved verbatim; the
 * diff is order-insensitive (same packages, same labels = same
 * list) because a rescan of unchanged apps must not rewrite the
 * file or flicker the UI.
 */
public final class AppListCodec {

    public static final class Entry {
        public final String pkg;
        public final String label;

        public Entry(String pkg, String label) {
            if (pkg == null || pkg.isEmpty()) {
                throw new IllegalArgumentException("empty package");
            }
            this.pkg = pkg;
            this.label = label == null ? pkg : label;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Entry)) return false;
            Entry e = (Entry) o;
            return pkg.equals(e.pkg) && label.equals(e.label);
        }

        @Override public int hashCode() {
            return 31 * pkg.hashCode() + label.hashCode();
        }

        @Override public String toString() {
            return pkg + " [" + label + "]";
        }
    }

    /** What changed between a cached list and a fresh scan. */
    public static final class Diff {
        public final List<Entry> added = new ArrayList<>();
        public final List<Entry> removed = new ArrayList<>();
        /** Same package, different label — the fresh entry. */
        public final List<Entry> relabeled = new ArrayList<>();

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && relabeled.isEmpty();
        }
    }

    private AppListCodec() {}

    public static String serialize(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            Entry e = entries.get(i);
            sb.append("{\"p\":");
            appendString(sb, e.pkg);
            sb.append(",\"l\":");
            appendString(sb, e.label);
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /**
     * Parses a cache file. Strict: anything that is not exactly
     * an array of {"p": string, "l": string} objects (with
     * optional whitespace, either key order, and extra keys
     * ignored) throws IllegalArgumentException. Duplicate
     * packages are tolerated — the first wins — since a
     * duplicate is stale data, not corruption.
     */
    public static List<Entry> parse(String text) {
        Parser p = new Parser(text);
        List<Entry> out = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        p.ws();
        p.expect('[');
        p.ws();
        if (p.peek() == ']') {
            p.next();
            p.ws();
            p.end();
            return out;
        }
        while (true) {
            p.ws();
            p.expect('{');
            String pkg = null;
            String label = null;
            p.ws();
            if (p.peek() != '}') {
                while (true) {
                    p.ws();
                    String key = p.string();
                    p.ws();
                    p.expect(':');
                    p.ws();
                    if ("p".equals(key)) {
                        pkg = p.string();
                    } else if ("l".equals(key)) {
                        label = p.string();
                    } else {
                        p.value(); // unknown key: skip its value
                    }
                    p.ws();
                    char c = p.peek();
                    if (c == ',') {
                        p.next();
                        continue;
                    }
                    if (c == '}') break;
                    throw p.error("expected ',' or '}' in object");
                }
            }
            p.expect('}');
            if (pkg == null || pkg.isEmpty() || label == null) {
                throw p.error("entry without a package and label");
            }
            if (seen.put(pkg, Boolean.TRUE) == null) {
                out.add(new Entry(pkg, label));
            }
            p.ws();
            char c = p.peek();
            if (c == ',') {
                p.next();
                continue;
            }
            if (c == ']') {
                p.next();
                break;
            }
            throw p.error("expected ',' or ']' in array");
        }
        p.ws();
        p.end();
        return out;
    }

    public static Diff diff(List<Entry> cached, List<Entry> fresh) {
        Map<String, String> oldLabels = new LinkedHashMap<>();
        for (Entry e : cached) oldLabels.put(e.pkg, e.label);
        Map<String, String> newLabels = new LinkedHashMap<>();
        for (Entry e : fresh) newLabels.put(e.pkg, e.label);
        Diff d = new Diff();
        for (Entry e : fresh) {
            String old = oldLabels.get(e.pkg);
            if (old == null) {
                d.added.add(e);
            } else if (!old.equals(e.label)) {
                d.relabeled.add(e);
            }
        }
        for (Entry e : cached) {
            if (!newLabels.containsKey(e.pkg)) d.removed.add(e);
        }
        return d;
    }

    public static boolean same(List<Entry> a, List<Entry> b) {
        return diff(a, b).isEmpty();
    }

    // ---- minimal JSON reader for the cache shape ----

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        char next() {
            return i < s.length() ? s.charAt(i++) : '\0';
        }

        void expect(char c) {
            if (next() != c) throw error("expected '" + c + "'");
        }

        void end() {
            if (i != s.length()) throw error("trailing content");
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException(
                    "corrupt app-list cache at offset " + i + ": " + what);
        }

        String string() {
            if (next() != '"') throw error("expected a string");
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw error("unterminated escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u': {
                            if (i + 4 > s.length()) throw error("bad \\u escape");
                            int code = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i++), 16);
                                if (d < 0) throw error("bad \\u escape");
                                code = (code << 4) | d;
                            }
                            sb.append((char) code);
                            break;
                        }
                        default: throw error("bad escape \\" + e);
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        /** Skips one JSON value of any shape (unknown keys). */
        void value() {
            ws();
            char c = peek();
            if (c == '"') {
                string();
            } else if (c == '{') {
                next();
                ws();
                if (peek() == '}') {
                    next();
                    return;
                }
                while (true) {
                    ws();
                    string();
                    ws();
                    expect(':');
                    value();
                    ws();
                    char d = next();
                    if (d == '}') return;
                    if (d != ',') throw error("expected ',' or '}'");
                }
            } else if (c == '[') {
                next();
                ws();
                if (peek() == ']') {
                    next();
                    return;
                }
                while (true) {
                    value();
                    ws();
                    char d = next();
                    if (d == ']') return;
                    if (d != ',') throw error("expected ',' or ']'");
                }
            } else {
                // literal: true / false / null / number — run to a delimiter
                int start = i;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == ',' || d == '}' || d == ']' || d <= ' ') break;
                    i++;
                }
                if (i == start) throw error("expected a value");
            }
        }
    }
}
