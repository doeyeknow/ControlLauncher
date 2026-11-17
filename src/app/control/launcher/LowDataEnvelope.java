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
 * v1.28 adds the word-dictionary marker the same way: build()
 * word-codes the text when coding saves bytes and adds "wd":1;
 * old receivers ignore the key and show the coded text, which
 * is printable ASCII by construction. Uncoded texts keep the
 * exact shape above.
 *
 * v1.29 adds the teach block the same way: build(text, lowData,
 * scope) attaches "wdt" when the scope has words to learn, and
 * the "wd" marker carries the wire's version (1 or 2). The
 * Parsed result carries the adopted indices so the caller can
 * record the sender's scope taught. Old receivers ignore "wdt"
 * and show the text as before.
 *
 * v1.32 removes the marker and codes every message: the coded
 * payload rides a dedicated "w" key instead of "text", so the
 * shape itself says "decode me" at zero extra bytes — no
 * header, no marker. Legacy envelopes ("text" key, with or
 * without "wd") still parse exactly as before. A build at or
 * below v1.31 receiving the new shape finds no "text" key and
 * treats the payload as a burst (see BUILD_NOTES for the
 * exact old-build behavior per road) — both sides need 1.32+.
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
        /** Non-null when the text arrived word-coded with a
         *  dictionary this build cannot decode: text is then
         *  the raw coded payload, shown as sent, and this
         *  note says why. Null for plain and cleanly decoded
         *  texts alike. */
        public final String wordNote;
        /** Learned indices adopted from the envelope's teach
         *  block (empty when none). The caller records the
         *  sender's scope taught for these. */
        public final java.util.Set<Integer> adopted;
        /** The payload exactly as received: the coded wire
         *  form for a coded message, the plain text for a
         *  legacy plain message. The caller stores it so
         *  the long-press view shows the real wire form. */
        public final String wire;
        /** True when the payload arrived coded (the "w" key,
         *  or a legacy "wd"-marked "text" key). False for
         *  legacy plain text. */
        public final boolean coded;

        Parsed(String text, boolean lowData, String wordNote) {
            this(text, lowData, wordNote, new java.util.HashSet<Integer>(),
                    text, false);
        }

        Parsed(String text, boolean lowData, String wordNote,
               java.util.Set<Integer> adopted) {
            this(text, lowData, wordNote, adopted, text, false);
        }

        Parsed(String text, boolean lowData, String wordNote,
               java.util.Set<Integer> adopted, String wire, boolean coded) {
            this.text = text;
            this.lowData = lowData;
            this.wordNote = wordNote;
            this.adopted = adopted == null
                    ? new java.util.HashSet<Integer>() : adopted;
            this.wire = wire;
            this.coded = coded;
        }
    }

    /** A built envelope plus the exact wire form it carries,
     *  so the sender can record the real coded form on its
     *  own thread entry (v1.32). */
    public static final class Built {
        public final String envelope;
        public final String wire;

        Built(String envelope, String wire) {
            this.envelope = envelope;
            this.wire = wire;
        }
    }

    /** Builds the envelope. v1.32: the text is always
     *  word-coded and rides the "w" key; the low-data marker
     *  is present only for low-data messages, and the teach
     *  block rides as "wdt" when the scope has words to
     *  learn. No scope, so no teaching. */
    public static String build(String text, boolean lowData) {
        return buildDetailed(text, lowData, null).envelope;
    }

    /** Builds the envelope for a scope, returning the wire
     *  form alongside it. */
    public static Built buildDetailed(String text, boolean lowData, String scope) {
        String plain = text == null ? "" : text;
        WordDict.Encoded e = DictStore.encodeForScope(scope, plain);
        String wire = e == null
                ? WordDict.escapeOnly(plain) : e.wire;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"t\":\"text\",\"w\":\"");
        sb.append(VoiceProfile.escape(wire));
        sb.append("\"");
        if (lowData) sb.append(",\"ld\":true");
        if (e != null && e.teach != null && !e.teach.isEmpty()) {
            sb.append(",\"wdt\":\"").append(VoiceProfile.escape(e.teach)).append("\"");
        }
        sb.append("}");
        return new Built(sb.toString(), wire);
    }

    /** Builds the envelope for a scope: the teach block rides
     *  as "wdt" when the scope has words to learn. */
    public static String build(String text, boolean lowData, String scope) {
        return buildDetailed(text, lowData, scope).envelope;
    }

    /**
     * Parses an envelope. Returns null when the payload is not a
     * text envelope at all (raw burst bytes, malformed JSON, a
     * different "t", or neither a "w" nor a "text" key) — the
     * caller then treats the payload as a burst, exactly as
     * before. A "w" key means a v1.32 coded payload: it must
     * parse strictly (decodeCoded), and anything unparseable
     * keeps its raw text with wordNote instead of failing the
     * parse. A legacy "text" key decodes exactly as before: a
     * "wd" marker word-decodes it, no marker passes it through
     * as plain text. A missing "ld" means an ordinary text;
     * "ld" present but not a JSON true means ordinary too (only
     * the literal true marks low-data, so a stray string or
     * number can never arm speech).
     */
    public static Parsed parse(String payload) {
        if (payload == null) return null;
        String s = payload.trim();
        if (s.isEmpty() || !s.startsWith("{") || !s.endsWith("}")) return null;
        String t = VoiceProfile.extractString(s, "t");
        if (!"text".equals(t)) return null;
        String teach = VoiceProfile.extractString(s, "wdt");
        String w = VoiceProfile.extractString(s, "w");
        if (w != null) {
            WordDict.Result decoded = WordDict.decodeCoded(w, teach);
            return new Parsed(decoded.text, hasTrueFlag(s, "ld"),
                    decoded.note, decoded.adopted, w, true);
        }
        String text = VoiceProfile.extractString(s, "text");
        if (text == null) return null;
        int wd = extractInt(s, "wd");
        WordDict.Result decoded = WordDict.decodeMarked(text, wd, teach);
        return new Parsed(decoded.text, hasTrueFlag(s, "ld"), decoded.note,
                decoded.adopted, text, wd > 0);
    }

    /** Reads a flat JSON integer field; 0 when the key is
     *  absent, non-numeric, or quoted (a malformed marker
     *  reads as "unmarked", so the text shows as sent). */
    static int extractInt(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return 0;
        int i = at + needle.length();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ':') i++;
            else break;
        }
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') i++;
        while (i < json.length()
                && json.charAt(i) >= '0' && json.charAt(i) <= '9') i++;
        if (i == start || (i == start + 1 && json.charAt(start) == '-')) return 0;
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (Exception e) {
            return 0;
        }
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
