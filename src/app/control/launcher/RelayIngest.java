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
         *  bytes for a text — callers use text, not this).
         *  For a relayed burst this is the ADPCM body only:
         *  the header line is stripped here, so the stored
         *  blob and the tap-to-play path see pure audio. */
        public final byte[] plain;
        /** ADPCM sample count for a burst; 0 for a text. */
        public final int samples;
        /** Bridging provenance (v1.24): non-null when the
         *  message arrived relayed through a gateway — the
         *  original sender, the original time, and the roads
         *  already traversed. Null for a direct message. */
        public final BridgeRule.Relayed relayed;
        /** Word-dictionary note (v1.28): non-null when a text
         *  arrived word-coded with a dictionary this build
         *  cannot decode — text is then the raw coded
         *  payload. Null for bursts and cleanly decoded
         *  texts. */
        public final String wordNote;
        /** Learned indices adopted from the message's teach
         *  block (v1.29; empty for bursts and messages
         *  without one). The caller records the sender's
         *  scope taught for these. */
        public final java.util.Set<Integer> adopted;
        /** The payload exactly as received (v1.32): the
         *  coded wire form for a coded text, the plain text
         *  for a legacy plain text. Null for bursts. */
        public final String wire;
        /** True when the text arrived coded (v1.32). */
        public final boolean coded;

        Decoded(boolean isText, String text, boolean lowData,
                byte[] plain, int samples, BridgeRule.Relayed relayed,
                String wordNote) {
            this(isText, text, lowData, plain, samples, relayed,
                    wordNote, new java.util.HashSet<Integer>());
        }

        Decoded(boolean isText, String text, boolean lowData,
                byte[] plain, int samples, BridgeRule.Relayed relayed,
                String wordNote, java.util.Set<Integer> adopted) {
            this(isText, text, lowData, plain, samples, relayed,
                    wordNote, adopted, null, false);
        }

        Decoded(boolean isText, String text, boolean lowData,
                byte[] plain, int samples, BridgeRule.Relayed relayed,
                String wordNote, java.util.Set<Integer> adopted,
                String wire, boolean coded) {
            this.isText = isText;
            this.text = text;
            this.lowData = lowData;
            this.plain = plain;
            this.samples = samples;
            this.relayed = relayed;
            this.wordNote = wordNote;
            this.adopted = adopted == null
                    ? new java.util.HashSet<Integer>() : adopted;
            this.wire = wire;
            this.coded = coded;
        }
    }

    /**
     * Decodes one relay message payload. keyBytes is the
     * 32-byte channel key for a private channel, null for an
     * open channel. Returns null when the blob cannot be
     * opened at all.
     *
     * v1.24: a relayed text carries its provenance in the
     * envelope keys, and a relayed burst carries a one-line
     * JSON header ahead of the raw ADPCM body (see
     * BridgeRule) — both surface as Decoded.relayed, and a
     * relayed burst's plain bytes are the body only.
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
        String asText = new String(plain, StandardCharsets.UTF_8);
        LowDataEnvelope.Parsed env = LowDataEnvelope.parse(asText);
        if (env != null) {
            return new Decoded(true, env.text, env.lowData, plain, 0,
                    BridgeRule.relayedFromTextPayload(asText), env.wordNote,
                    env.adopted, env.wire, env.coded);
        }
        BridgeRule.BurstHeader header = BridgeRule.parseBurstHeader(plain);
        if (header != null) {
            byte[] body = new byte[plain.length - header.bodyOffset];
            System.arraycopy(plain, header.bodyOffset, body, 0, body.length);
            return new Decoded(false, "", false, body,
                    Adpcm.samplesIn(body), header.relayed, null);
        }
        return new Decoded(false, "", false, plain, Adpcm.samplesIn(plain), null, null);
    }

    /**
     * Stamps the relay fields onto a thread entry the caller
     * already built with WalkieStore.entry: the client message
     * id (the dedup key), the sender's relay pubkey (the voice
     * profile lookup key), the low-data marker for a marked
     * text, the wire form exactly as received for a text
     * (v1.32, for the long-press view), and the stored burst
     * blob for a voice entry (the relay is store-and-forward,
     * but the thread is the local record — the blob is what
     * tap-to-play plays).
     */
    public static void stampEntry(JSONObject entry, RelayCore.Msg m,
                                  Decoded d) {
        if (entry == null || m == null || d == null) return;
        try {
            entry.put("cmid", m.clientMsgId);
            entry.put("sender", m.sender);
            if (d.isText) {
                if (d.lowData) entry.put("lowData", true);
                if (d.wordNote != null) entry.put("wdNote", d.wordNote);
                if (d.wire != null) {
                    // An undecoded payload's original size
                    // is unknown — the wire IS the text we
                    // show — so record -1 and let the view
                    // omit the size line rather than lie.
                    int plainBytes = d.wordNote != null ? -1
                            : d.text.getBytes(StandardCharsets.UTF_8).length;
                    WalkieStore.stampWire(entry, d.wire, plainBytes, d.coded);
                }
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
