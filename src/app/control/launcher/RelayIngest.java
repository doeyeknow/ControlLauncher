package app.control.launcher;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The shared relay-message ingest (v1.19): the decode and
 * entry-shaping both relay receive paths use — the walkie page's
 * tuned-channel loop and the subscription service's background
 * poller. One decode, one entry shape, one dedup rule, so a
 * message arriving on either path lands in the thread exactly
 * the same way, and can never land twice.
 *
 * Decode: a private channel's blob is opened with the channel
 * key (RelayCrypto, PROTOCOL §6); an open channel's payload is
 * plain base64. Text rides in a {"t":"text"} envelope
 * (LowDataEnvelope); anything else is a raw ADPCM burst. A blob
 * that cannot be opened (wrong key, someone else's sealed
 * traffic on an open channel) decodes to null — not ours to
 * show — exactly as the walkie page has always treated it.
 *
 * Dedup: the pollers share the persisted per-channel cursor,
 * and each keeps an in-memory set of seen client message ids —
 * but the two pollers are different objects, and either can
 * restart, so the authoritative check is the STORE: an entry
 * with the same clientMsgId already in the channel's thread
 * bucket suppresses the ingest. The store bucket is capped at
 * 200 entries, so the scan is cheap, and it is the backstop
 * that makes duplicate delivery impossible across pollers,
 * restarts, and retunes. An empty clientMsgId can never match
 * (the relay always sends one; the page's old behavior of
 * ingesting an empty-id message stands).
 *
 * Pure Java over org.json (the store's own entry shape) — no
 * Android types — so the harness proves the decode, the
 * stamping, and the dedup with the exact shipping code. The
 * callers own the Android parts: building the entry object via
 * WalkieStore, appending it, speaking it, and refreshing UI.
 */
public final class RelayIngest {
    private RelayIngest() {}

    /** A decoded relay payload: text or burst, never both. */
    public static final class Decoded {
        public final boolean isText;
        public final String text;
        public final boolean lowData;
        /** The raw plaintext (burst bytes, or the envelope
         *  bytes for a text — callers use text, not this). */
        public final byte[] plain;
        /** ADPCM sample count for a burst; 0 for a text. */
        public final int samples;

        Decoded(boolean isText, String text, boolean lowData,
                byte[] plain, int samples) {
            this.isText = isText;
            this.text = text;
            this.lowData = lowData;
            this.plain = plain;
            this.samples = samples;
        }
    }

    /**
     * Decodes one relay message payload. keyBytes is the
     * 32-byte channel key for a private channel, null for an
     * open channel. Returns null when the blob cannot be
     * opened at all.
     */
    public static Decoded decode(byte[] keyBytes, String ciphertext) {
        byte[] plain;
        try {
            plain = keyBytes != null
                    ? RelayCrypto.open(keyBytes, ciphertext)
                    : RelayKeys.b64StdDecode(ciphertext);
        } catch (Exception e) {
            return null;
        }
        LowDataEnvelope.Parsed env = LowDataEnvelope.parse(
                new String(plain, StandardCharsets.UTF_8));
        if (env != null) {
            return new Decoded(true, env.text, env.lowData, plain, 0);
        }
        return new Decoded(false, "", false, plain, Adpcm.samplesIn(plain));
    }

    /**
     * Stamps the relay fields onto a thread entry the caller
     * already built with WalkieStore.entry: the client message
     * id (the dedup key), the sender's relay pubkey (the voice
     * profile lookup key), the low-data marker for a marked
     * text, and the stored burst blob for a voice entry (the
     * relay is store-and-forward, but the thread is the local
     * record — the blob is what tap-to-play plays).
     */
    public static void stampEntry(JSONObject entry, RelayCore.Msg m,
                                  Decoded d) {
        if (entry == null || m == null || d == null) return;
        try {
            entry.put("cmid", m.clientMsgId);
            entry.put("sender", m.sender);
            if (d.isText) {
                if (d.lowData) entry.put("lowData", true);
            } else {
                entry.put("blob", RelayKeys.b64Std(d.plain));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * True when the channel's stored thread already holds an
     * entry with this client message id. An empty or null id
     * never matches. This is the cross-poller dedup backstop:
     * both the page loop and the subscription service check
     * it before appending, so a message pulled twice — by two
     * pollers, across a restart, or after a retune — is
     * stored once.
     */
    public static boolean alreadyStored(List<JSONObject> thread, String cmid) {
        if (thread == null || cmid == null || cmid.isEmpty()) return false;
        for (JSONObject e : thread) {
            if (e != null && cmid.equals(e.optString("cmid", ""))) return true;
        }
        return false;
    }
}
