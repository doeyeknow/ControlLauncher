package app.control.launcher;

import java.util.List;

/**
 * A per-contact TTS voice profile for walkie-talkie low-data mode:
 * which installed system voice to read that person's messages in,
 * plus pitch and rate. This is a SYNTHETIC profile — a recognizable
 * stand-in chosen on this phone — never the person's real voice and
 * never a voice clone. The profile is deliberately small and plain
 * so it can live on the contact record in profile.json and travel
 * with the encrypted profile export; a voice NAME that isn't
 * installed on the phone reading it falls back to the system
 * default voice (see resolve), so an imported profile never breaks
 * speech on a phone with different voices installed.
 *
 * Pure Java, no Android and no org.json (the harness runs the exact
 * shipping code on a plain JVM, where android.jar's org.json stubs
 * throw). The JSON shape is flat and hand-parsed, in the AppListCodec
 * style: {"voice":"en-US-language#...","pitch":1.0,"rate":1.0}.
 */
public final class VoiceProfile {
    public static final float MIN_PITCH = 0.5f;
    public static final float MAX_PITCH = 2.0f;
    public static final float MIN_RATE = 0.5f;
    public static final float MAX_RATE = 2.0f;
    public static final float DEFAULT_PITCH = 1.0f;
    public static final float DEFAULT_RATE = 1.0f;

    /** "" means "the system default voice" — always valid. */
    public final String voiceName;
    public final float pitch;
    public final float rate;

    public VoiceProfile(String voiceName, float pitch, float rate) {
        this.voiceName = voiceName == null ? "" : voiceName;
        this.pitch = clamp(pitch, MIN_PITCH, MAX_PITCH, DEFAULT_PITCH);
        this.rate = clamp(rate, MIN_RATE, MAX_RATE, DEFAULT_RATE);
    }

    public static VoiceProfile defaults() {
        return new VoiceProfile("", DEFAULT_PITCH, DEFAULT_RATE);
    }

    private static float clamp(float v, float lo, float hi, float fallback) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return fallback;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    // ---- JSON ----

    public String toJson() {
        return "{\"voice\":\"" + escape(voiceName) + "\",\"pitch\":"
                + Float.toString(pitch) + ",\"rate\":" + Float.toString(rate) + "}";
    }

    /**
     * Tolerant parse: null, empty, malformed, or wrong-typed input
     * yields the default profile (never throws, never null). Missing
     * fields fall back to their defaults; unknown fields are ignored;
     * out-of-range numbers are clamped by the constructor.
     */
    public static VoiceProfile parse(String json) {
        if (json == null) return defaults();
        String s = json.trim();
        if (s.isEmpty() || !s.startsWith("{") || !s.endsWith("}")) return defaults();
        String voice = extractString(s, "voice");
        Float pitch = extractNumber(s, "pitch");
        Float rate = extractNumber(s, "rate");
        return new VoiceProfile(
                voice == null ? "" : voice,
                pitch == null ? DEFAULT_PITCH : pitch,
                rate == null ? DEFAULT_RATE : rate);
    }

    /** The voice name to actually use: the stored name when it is
     *  installed on this phone, else "" (system default). A null or
     *  empty available list means "unknown" — the stored name is
     *  returned as-is and the speaker falls back at speak time. */
    public String resolve(List<String> available) {
        if (voiceName.isEmpty()) return "";
        if (available == null || available.isEmpty()) return voiceName;
        return available.contains(voiceName) ? voiceName : "";
    }

    // ---- minimal JSON field extraction (flat object, this shape only) ----

    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
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
                    break;
            }
        }
        return sb.toString();
    }

    /** Extracts a string field's unescaped value, or null when the
     *  field is absent, not a string, or malformed. */
    static String extractString(String json, String key) {
        int at = findKey(json, key);
        if (at < 0) return null;
        int i = skipWs(json, at);
        if (i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') return sb.toString();
            if (c == '\\' && i + 1 < json.length()) {
                char e = json.charAt(i + 1);
                switch (e) {
                    case '"': sb.append('"'); i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case '/': sb.append('/'); i += 2; continue;
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'b': sb.append('\b'); i += 2; continue;
                    case 'f': sb.append('\f'); i += 2; continue;
                    case 'u':
                        if (i + 5 < json.length()) {
                            try {
                                int cp = Integer.parseInt(json.substring(i + 2, i + 6), 16);
                                sb.append((char) cp);
                                i += 6;
                                continue;
                            } catch (Exception ignored) {
                            }
                        }
                        return null;
                    default: return null;
                }
            }
            if (c < 0x20) return null;
            sb.append(c);
            i++;
        }
        return null;
    }

    /** Extracts a numeric field's value, or null when absent, not a
     *  number, or malformed. */
    static Float extractNumber(String json, String key) {
        int at = findKey(json, key);
        if (at < 0) return null;
        int i = skipWs(json, at);
        int start = i;
        while (i < json.length()) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                    || c == 'e' || c == 'E') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) return null;
        try {
            return Float.parseFloat(json.substring(start, i));
        } catch (Exception e) {
            return null;
        }
    }

    /** Index just past `"key":` in a flat object, or -1. */
    private static int findKey(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = 0;
        while (true) {
            int at = json.indexOf(needle, idx);
            if (at < 0) return -1;
            int i = skipWs(json, at + needle.length());
            if (i < json.length() && json.charAt(i) == ':') return i + 1;
            idx = at + needle.length();
        }
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof VoiceProfile)) return false;
        VoiceProfile v = (VoiceProfile) o;
        return voiceName.equals(v.voiceName)
                && Float.compare(pitch, v.pitch) == 0
                && Float.compare(rate, v.rate) == 0;
    }

    @Override
    public int hashCode() {
        int h = voiceName.hashCode();
        h = 31 * h + Float.floatToIntBits(pitch);
        h = 31 * h + Float.floatToIntBits(rate);
        return h;
    }

    @Override
    public String toString() {
        return toJson();
    }
}
