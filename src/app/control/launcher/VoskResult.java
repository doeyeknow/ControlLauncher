package app.control.launcher;

/**
 * Parses Vosk recognizer output — the JSON the engine returns for a
 * finished utterance — into a verdict the walkie UI can act on.
 * Vosk result shapes (vosk 0.3.45):
 *
 *   final:   {"text": "hello world", "result": [{"conf": 0.98,
 *             "end": 1.2, "start": 0.9, "word": "hello"}, ...]}
 *   partial: {"partial": "hel"}
 *   empty:   {"text": ""}
 *
 * Pure Java, no org.json (the harness runs the exact shipping code
 * on a plain JVM). Parsing is tolerant: anything that isn't a
 * readable result JSON is FAILED, never a crash and never a
 * silent empty send — the caller shows the failure reason.
 */
public final class VoskResult {
    public enum Status {
        /** Text was recognized (possibly low confidence — see meanConf). */
        OK,
        /** The engine ran but heard no speech (empty text). */
        EMPTY,
        /** The result JSON was missing or unreadable. */
        FAILED
    }

    public final Status status;
    /** Recognized text; "" unless status is OK. */
    public final String text;
    /** Mean per-word confidence, 0..1; 0 when no word confidences
     *  were reported. The UI warns when this is low. */
    public final float meanConf;
    /** True when the result carried word-level detail. */
    public final boolean hasWords;

    private VoskResult(Status status, String text, float meanConf, boolean hasWords) {
        this.status = status;
        this.text = text;
        this.meanConf = meanConf;
        this.hasWords = hasWords;
    }

    /**
     * Summarizes a getFinalResult()/getResult() JSON string. Null,
     * empty, non-object, or text-less JSON is FAILED; a present but
     * blank "text" is EMPTY; anything else is OK with the text.
     */
    public static VoskResult summarize(String finalJson) {
        if (finalJson == null) return failed();
        String s = finalJson.trim();
        if (s.isEmpty() || !s.startsWith("{") || !s.endsWith("}")) return failed();
        String text = VoiceProfile.extractString(s, "text");
        if (text == null) return failed();
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return new VoskResult(Status.EMPTY, "", 0f, false);
        }
        float mean = 0f;
        boolean words = false;
        float[] confs = extractConfs(s);
        if (confs != null && confs.length > 0) {
            float sum = 0f;
            for (float c : confs) sum += c;
            mean = sum / confs.length;
            words = true;
        }
        return new VoskResult(Status.OK, trimmed, clamp01(mean), words);
    }

    /** Extracts the "partial" field of a getPartialResult() JSON
     *  string; "" when absent or unreadable. Partials are display
     *  only — they are never sent. */
    public static String partialText(String partialJson) {
        if (partialJson == null) return "";
        String s = partialJson.trim();
        if (s.isEmpty() || !s.startsWith("{")) return "";
        String p = VoiceProfile.extractString(s, "partial");
        return p == null ? "" : p;
    }

    private static VoskResult failed() {
        return new VoskResult(Status.FAILED, "", 0f, false);
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /**
     * All "conf" numbers inside the "result" array, or null when
     * there is no result array. Numbers outside the array (there
     * are none in Vosk's shape, but a hostile string could carry
     * them) are ignored: extraction is scoped to the brackets.
     */
    static float[] extractConfs(String json) {
        int res = json.indexOf("\"result\"");
        if (res < 0) return null;
        int open = json.indexOf('[', res);
        if (open < 0) return null;
        int close = json.indexOf(']', open);
        if (close < 0) return null;
        String array = json.substring(open, close);
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        int idx = 0;
        while (true) {
            int at = array.indexOf("\"conf\"", idx);
            if (at < 0) break;
            int i = at + 6;
            while (i < array.length()
                    && (array.charAt(i) == ' ' || array.charAt(i) == '\t'
                    || array.charAt(i) == ':')) {
                i++;
            }
            int start = i;
            while (i < array.length()) {
                char c = array.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (i > start) {
                try {
                    out.add(Float.parseFloat(array.substring(start, i)));
                } catch (Exception ignored) {
                }
            }
            idx = at + 6;
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < out.size(); i++) r[i] = out.get(i);
        return r;
    }
}
