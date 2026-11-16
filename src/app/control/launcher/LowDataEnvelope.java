package app.control.launcher;

/**
 * The walkie text envelope that rides the relay inside the
 * sealed/plaintext payload (PROTOCOL §6; see WalkiePage): texts are
 * a tiny JSON object so a receiver can tell text from raw-ADPCM
 * bursts. v1.11 adds the low-data marker — a single extra boolean —
 * so a receiver knows the text arrived as a transcribed low-data
 * message and should be read aloud in the sender's voice profile:
 *
 *   {"t":"text","text":"hello","ld":true}
 *
 * OLD-CLIENT TOLERANCE (by design, not by accident): pre-v1.11
 * receivers parse this object with org.json and read only "t" and
 * "text" — an unknown "ld" key is ignored, so a low-data message
 * still shows as plain text on an old build. The marker is omitted
 * entirely for ordinary typed texts, so those envelopes are
 * byte-identical to the v1.3 shape.
 *
 * Pure Java, no org.json: the harness runs this exact code and
 * proves the round-trip plus old-client readability (the harness
 * parses the built envelope with a real org.json jar, the way an
 * old build would).
 */
public final class LowDataEnvelope {
    private LowDataEnvelope() {}

    public static final class Parsed {
        public final String text;
        public final boolean lowData;

        Parsed(String text, boolean lowData) {
            this.text = text;
            this.lowData = lowData;
        }
    }

    /** Builds the envelope. The text is JSON-escaped; the marker is
     *  present only for low-data messages. */
    public static String build(String text, boolean lowData) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"t\":\"text\",\"text\":\"");
        sb.append(VoiceProfile.escape(text == null ? "" : text));
        sb.append("\"");
        if (lowData) sb.append(",\"ld\":true");
        sb.append("}");
        return sb.toString();
    }

    /**
     * Parses an envelope. Returns null when the payload is not a
     * text envelope at all (raw burst bytes, malformed JSON, a
     * different "t", or a missing/non-string "text") — the caller
     * then treats the payload as a burst, exactly as before. A
     * missing "ld" means an ordinary text; "ld" present but not a
     * JSON true means ordinary too (only the literal true marks
     * low-data, so a stray string or number can never arm speech).
     */
    public static Parsed parse(String payload) {
        if (payload == null) return null;
        String s = payload.trim();
        if (s.isEmpty() || !s.startsWith("{") || !s.endsWith("}")) return null;
        String t = VoiceProfile.extractString(s, "t");
        if (!"text".equals(t)) return null;
        String text = VoiceProfile.extractString(s, "text");
        if (text == null) return null;
        return new Parsed(text, hasTrueFlag(s, "ld"));
    }

    /** True only when "key":true (the JSON literal) appears. */
    static boolean hasTrueFlag(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = 0;
        while (true) {
            int at = json.indexOf(needle, idx);
            if (at < 0) return false;
            int i = at + needle.length();
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ':') i++;
                else break;
            }
            if (json.startsWith("true", i)) {
                int after = i + 4;
                if (after >= json.length()
                        || json.charAt(after) == ','
                        || json.charAt(after) == '}'
                        || json.charAt(after) == ' '
                        || json.charAt(after) == '\t'
                        || json.charAt(after) == '\n'
                        || json.charAt(after) == '\r') {
                    return true;
                }
            }
            idx = at + needle.length();
        }
    }
}
