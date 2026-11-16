package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Binary key payloads for the audio modem (v1.8). The air format is
 * compact — raw key bytes, length-prefixed names — because JSON and
 * base64 would nearly double the seconds a key spends on the air.
 * The SEMANTICS are exactly the old QR payloads': on receipt the
 * decoded fields are rebuilt into the same JSON text the QR flows
 * produced, and the existing resolution (ChannelInvite.join,
 * ContactCard.apply, the chat-key filing) runs unchanged — same
 * first-card-wins convergence, same wireId/slot placement.
 *
 * Envelope: [kind:1] + kind fields. All key material is 32 raw
 * bytes. Names and labels are UTF-8, length-prefixed, capped at 64
 * bytes (truncated at a character boundary — a longer display name
 * still files, with the tail cut; keys are never truncated).
 *
 *   kind 1, contact card: [nameLen:1][name][pub:32][dm:32]
 *   kind 2, channel invite: [number:1][mode:1][labelLen:1][label]
 *       [key:32, private only — open channels carry no key]
 *   kind 3, chat key: [nameLen:1][name][key:32]
 *
 * Pure Java with no Android or org.json dependency, so the JVM
 * harness exercises this exact code; the JSON text is built by hand
 * with a small escaper and verified byte-for-byte in the harness.
 */
public final class AudioKeyPayload {

    public static final int KIND_CONTACT = 1;
    public static final int KIND_CHANNEL = 2;
    public static final int KIND_CHAT = 3;

    /** Longest name/label carried over the air, in UTF-8 bytes. */
    public static final int MAX_TEXT = 64;

    private AudioKeyPayload() {}

    public static final class Decoded {
        public int kind;
        public String name = "";   // contact name / chat name
        public byte[] pub;         // contact: relay pubkey, 32 bytes
        public byte[] dm;          // contact: carried DM key, 32 bytes
        public int number;         // channel: wanted slot 1..50
        public boolean priv;       // channel: private mode
        public String label = "";  // channel label
        public byte[] key;         // channel (private) / chat key, 32 bytes
    }

    // ---- encode ----

    private static byte[] textBytes(String s) {
        if (s == null) s = "";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= MAX_TEXT) return raw;
        // Truncate at a UTF-8 character boundary.
        int cut = MAX_TEXT;
        while (cut > 0 && (raw[cut] & 0xC0) == 0x80) cut--;
        byte[] out = new byte[cut];
        System.arraycopy(raw, 0, out, 0, cut);
        return out;
    }

    private static byte[] key32(String b64, boolean url) {
        if (b64 == null) return null;
        try {
            String s = b64.trim();
            if (url) {
                while (s.length() % 4 != 0) s += "=";
                byte[] raw = Base64.getUrlDecoder().decode(s);
                return raw.length == 32 ? raw : null;
            }
            byte[] raw = Base64.getDecoder().decode(s);
            return raw.length == 32 ? raw : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Contact card: display name + relay pubkey (base64url) + card
     *  DM key (base64 std). Null when either key is missing or malformed. */
    public static byte[] contact(String name, String pubB64Url, String dmB64) {
        byte[] pub = key32(pubB64Url, true);
        byte[] dm = key32(dmB64, false);
        if (pub == null || dm == null) return null;
        byte[] nameBytes = textBytes(name);
        byte[] out = new byte[1 + 1 + nameBytes.length + 32 + 32];
        int p = 0;
        out[p++] = KIND_CONTACT;
        out[p++] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, p, nameBytes.length);
        p += nameBytes.length;
        System.arraycopy(pub, 0, out, p, 32);
        p += 32;
        System.arraycopy(dm, 0, out, p, 32);
        return out;
    }

    /** Channel invite: wanted number, private flag, label, and the
     *  channel key (base64 std) for private channels. Open channels
     *  carry no key. Null on a bad number or a missing/malformed
     *  private key. */
    public static byte[] channel(int number, boolean priv, String label, String keyB64) {
        if (number < 1 || number > 50) return null;
        byte[] key = null;
        if (priv) {
            key = key32(keyB64, false);
            if (key == null) return null;
        }
        byte[] labelBytes = textBytes(label);
        byte[] out = new byte[1 + 1 + 1 + 1 + labelBytes.length + (priv ? 32 : 0)];
        int p = 0;
        out[p++] = KIND_CHANNEL;
        out[p++] = (byte) number;
        out[p++] = (byte) (priv ? 1 : 0);
        out[p++] = (byte) labelBytes.length;
        System.arraycopy(labelBytes, 0, out, p, labelBytes.length);
        p += labelBytes.length;
        if (priv) System.arraycopy(key, 0, out, p, 32);
        return out;
    }

    /** Chat key: chat name + channel key (base64 std). Null on a
     *  missing/malformed key. */
    public static byte[] chat(String name, String keyB64) {
        byte[] key = key32(keyB64, false);
        if (key == null) return null;
        byte[] nameBytes = textBytes(name);
        byte[] out = new byte[1 + 1 + nameBytes.length + 32];
        int p = 0;
        out[p++] = KIND_CHAT;
        out[p++] = (byte) nameBytes.length;
        System.arraycopy(nameBytes, 0, out, p, nameBytes.length);
        p += nameBytes.length;
        System.arraycopy(key, 0, out, p, 32);
        return out;
    }

    // ---- decode ----

    /** Parses an air payload; null when it is not a well-formed key
     *  payload of a known kind. Never throws. */
    public static Decoded decode(byte[] payload) {
        if (payload == null || payload.length < 2) return null;
        try {
            int kind = payload[0] & 0xFF;
            Decoded d = new Decoded();
            d.kind = kind;
            int p = 1;
            if (kind == KIND_CONTACT) {
                int n = payload[p++] & 0xFF;
                if (n > MAX_TEXT || payload.length != 1 + 1 + n + 64) return null;
                d.name = new String(payload, p, n, StandardCharsets.UTF_8);
                p += n;
                d.pub = copy(payload, p, 32);
                p += 32;
                d.dm = copy(payload, p, 32);
                return d;
            }
            if (kind == KIND_CHANNEL) {
                if (payload.length < 4) return null;
                d.number = payload[p++] & 0xFF;
                d.priv = (payload[p++] & 0xFF) == 1;
                if (d.number < 1 || d.number > 50) return null;
                int n = payload[p++] & 0xFF;
                if (n > MAX_TEXT) return null;
                int want = 1 + 1 + 1 + 1 + n + (d.priv ? 32 : 0);
                if (payload.length != want) return null;
                d.label = new String(payload, p, n, StandardCharsets.UTF_8);
                p += n;
                if (d.priv) d.key = copy(payload, p, 32);
                return d;
            }
            if (kind == KIND_CHAT) {
                int n = payload[p++] & 0xFF;
                if (n > MAX_TEXT || payload.length != 1 + 1 + n + 32) return null;
                d.name = new String(payload, p, n, StandardCharsets.UTF_8);
                p += n;
                d.key = copy(payload, p, 32);
                return d;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] copy(byte[] src, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    // ---- JSON reconstruction (the old QR shapes, byte for byte) ----

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String b64Std(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String b64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Rebuilds the exact JSON text the old QR payload carried, so
     *  the existing resolution code runs unchanged. The channel
     *  "id" is omitted on purpose: ChannelInvite.join recomputes
     *  it from number + key and never trusts a carried id. */
    public static String toJsonText(Decoded d) {
        if (d == null) return "";
        if (d.kind == KIND_CONTACT) {
            return "{\"app\":\"control-contact\",\"v\":2,\"name\":\"" + esc(d.name)
                    + "\",\"pub\":\"" + b64Url(d.pub)
                    + "\",\"dm\":\"" + b64Std(d.dm) + "\"}";
        }
        if (d.kind == KIND_CHANNEL) {
            return "{\"app\":\"control-channel\",\"v\":1,\"number\":" + d.number
                    + ",\"label\":\"" + esc(d.label) + "\",\"mode\":\""
                    + (d.priv ? "private" : "open") + "\",\"key\":\""
                    + (d.priv ? b64Std(d.key) : "") + "\"}";
        }
        if (d.kind == KIND_CHAT) {
            return "{\"app\":\"control-chat\",\"v\":1,\"name\":\"" + esc(d.name)
                    + "\",\"key\":\"" + b64Std(d.key) + "\"}";
        }
        return "";
    }

    /** Human kind name for status copy ("contact card", "channel
     *  invite", "chat key"). */
    public static String kindName(int kind) {
        switch (kind) {
            case KIND_CONTACT: return "contact card";
            case KIND_CHANNEL: return "channel invite";
            case KIND_CHAT: return "chat key";
            default: return "key";
        }
    }
}
