package app.control.launcher;

import org.json.JSONObject;

/**
 * The chat plaintext envelope (relay §6 JSON): a chat message
 * is {"type":"text", ...} inside the sealed/plaintext blob so
 * the thread can tell a text from anything else that lands in
 * the channel.
 *
 * v1.32: the coded payload rides a dedicated "w" key — every
 * message is coded and no marker rides — with the teach block
 * as "wdt" when the conversation has words to learn. Legacy
 * envelopes ("text" key, with or without the v1.28 "wd"
 * marker) still unwrap exactly as before, and a plaintext
 * that is not an envelope at all still shows as sent. A build
 * at or below v1.31 reading the new shape finds no "text" key
 * and shows an empty message (see BUILD_NOTES for the exact
 * old-build behavior per road) — both sides need 1.32+.
 *
 * Pure Java over org.json — no Android types — so the harness
 * proves the build/parse round-trip with the exact shipping
 * code. ChatThreadView owns the Android parts.
 */
public final class ChatEnvelope {
    private ChatEnvelope() {}

    /** An unwrapped chat plaintext: the text to show, the
     *  word-dictionary note when the payload arrived coded
     *  with a dictionary this build cannot decode, the
     *  learned indices adopted from its teach block, and
     *  the wire form exactly as received (for the
     *  long-press view). */
    public static final class Unwrapped {
        public final String text;
        public final String wordNote;
        public final java.util.Set<Integer> adopted;
        /** The payload exactly as received: the coded wire
         *  form for a coded message, the plain text for a
         *  legacy plain message. */
        public final String wire;
        /** True when the payload arrived coded (the "w" key,
         *  or a legacy "wd"-marked "text" key). */
        public final boolean coded;

        Unwrapped(String text, String wordNote,
                  java.util.Set<Integer> adopted, String wire, boolean coded) {
            this.text = text;
            this.wordNote = wordNote;
            this.adopted = adopted == null
                    ? new java.util.HashSet<Integer>() : adopted;
            this.wire = wire;
            this.coded = coded;
        }
    }

    /** A built envelope plus the exact wire form it carries,
     *  so the sender can record the real coded form on its
     *  own message (v1.32). {@code coded} is false only on
     *  the raw-text fallback, so the record never claims a
     *  coded form that was not sent. */
    public static final class Built {
        public final String envelope;
        public final String wire;
        public final boolean coded;

        Built(String envelope, String wire, boolean coded) {
            this.envelope = envelope;
            this.wire = wire;
            this.coded = coded;
        }
    }

    /** Builds the envelope for a conversation scope: the
     *  text is always word-coded on the "w" key, the teach
     *  block rides as "wdt" when the conversation has words
     *  to learn, and "ts" carries the send time. */
    public static Built build(String text, String scope) {
        String plain = text == null ? "" : text;
        WordDict.Encoded e = DictStore.encodeForScope(scope, plain);
        String wire = e == null
                ? WordDict.escapeOnly(plain) : e.wire;
        try {
            JSONObject o = new JSONObject();
            o.put("type", "text");
            o.put("w", wire);
            if (e != null && e.teach != null && !e.teach.isEmpty()) {
                o.put("wdt", e.teach);
            }
            o.put("ts", System.currentTimeMillis());
            return new Built(o.toString(), wire, true);
        } catch (Exception ex) {
            return new Built(plain, plain, false);
        }
    }

    /** Unwraps a chat plaintext. A "w" key means a v1.32
     *  coded payload (strict decode); a legacy "text" key
     *  decodes exactly as before ("wd" marker or plain);
     *  anything else shows as sent, unmarked. Never throws. */
    public static Unwrapped unwrap(String plain) {
        try {
            JSONObject o = new JSONObject(plain);
            if ("text".equals(o.optString("type"))) {
                if (o.has("w")) {
                    String wire = o.optString("w", "");
                    WordDict.Result r = WordDict.decodeCoded(wire,
                            o.has("wdt") ? o.optString("wdt", "") : null);
                    return new Unwrapped(r.text, r.note, r.adopted, wire, true);
                }
                String text = o.optString("text", "");
                int wd = o.optInt("wd", 0);
                WordDict.Result r = WordDict.decodeMarked(text, wd,
                        o.has("wdt") ? o.optString("wdt", "") : null);
                return new Unwrapped(r.text, r.note, r.adopted, text, wd > 0);
            }
        } catch (Exception ignored) {
        }
        return new Unwrapped(plain, null,
                new java.util.HashSet<Integer>(), plain, false);
    }
}
