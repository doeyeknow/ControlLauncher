package app.control.launcher;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-transport bridging (v1.24): a message received on one
 * walkie road is passed live to the other connected roads, so
 * it is available to anyone regardless of connection method —
 * and a phone with data publishes mesh/Bluetooth messages to
 * the relay channel. Adam's order (2026-11-15): "I want the
 * messages to be available to anyone regardless of the
 * connection method. For example if a device is connected
 * through a mesh, it gets passed to all devices and then gets
 * published to a channel when a phone has data."
 *
 * LIVE ONLY, no store-and-forward queue: a message is
 * forwarded at the moment it arrives, to the roads connected
 * at that moment. A message that arrives while no phone has
 * data stays within the mesh — only NEW messages publish once
 * a phone gains data. Nothing is held for later, and no copy
 * may promise delivery to people who were offline.
 *
 * PROVENANCE — the trust rule. A gateway can never sign as
 * the original sender: a relayed message is signed (on the
 * relay) or sent (on the mesh/Bluetooth) by the GATEWAY. The
 * original sender, the original timestamp, and the gateway's
 * identity travel with the message, and the UI marks it
 * "relayed by &lt;gateway&gt;" — the gateway vouches for
 * delivery, never for authorship. A relayed message is never
 * presented as if it came directly from the sender over the
 * road it arrived on.
 *
 * WHERE PROVENANCE RIDES — one rule per road, because the
 * roads carry different shapes:
 *
 *  - Relay texts: extra keys in the LowDataEnvelope object
 *    ({"t":"text","text":…,"ld":…,"bid":…,"relayed":true,
 *    "orig":…,"origPub":…,"ots":"…","via":…,"trav":…}).
 *    Old builds read only "t"/"text"/"ld" and show the text
 *    as usual — the keys are ignored, exactly like "ld" was
 *    in v1.11.
 *  - Relay bursts: bursts have no envelope — the payload is
 *    raw ADPCM, and wrapping it in JSON would make old
 *    builds play the wrapper as noise. A relayed burst rides
 *    as ONE header line of JSON, a newline, then the raw
 *    ADPCM bytes verbatim: {"t":"rburst","bid":…,…}\n&lt;adpcm&gt;.
 *    New builds strip the header; old builds play a ~25ms
 *    click from the small header and then the real audio
 *    intact, showing the gateway as the sender.
 *  - Mesh/Bluetooth text packets and spurt-start packets:
 *    the same keys in the packet JSON ("from"/"name" already
 *    carries the original sender's name). Old receivers
 *    ignore the unknown keys.
 *
 * The clientMsgId is deliberately NOT the provenance carrier:
 * the relay caps it at 128 chars and scopes idempotency per
 * sender, so it can neither hold the fields nor dedup across
 * gateways. Bridged relay posts use plain random UUIDs, like
 * every other post.
 *
 * LOOP PREVENTION — two mechanisms, both required:
 *
 *  1. The bridge id ("bid"): the message's stable identity,
 *     derived from its origin wherever an origin identity
 *     exists — the relay client message id for a relay
 *     message, the packet message id ("mid", minted by the
 *     sender per message/spurt) for a mesh/Bluetooth
 *     message — and carried unchanged on every hop. Two
 *     gateways forwarding the same message therefore mint
 *     the SAME bid, and receivers land it once. Only a
 *     message with no origin identity (a pre-v1.24 sender's
 *     packet) gets a fresh UUID bid, minted by the first
 *     forwarder. A phone that already forwarded a bid never
 *     forwards it again (the forwarded set is persisted —
 *     see WalkieStore's bridge-seen store — so a restart
 *     cannot re-forward), and a thread entry carrying a bid
 *     suppresses the same message arriving on another road.
 *  2. The traversed set ("trav"): the roads the message has
 *     already been on. A phone never forwards back to a
 *     traversed road, so a two-phone ping-pong dies even if
 *     the forwarded set was evicted.
 *
 * PRIVATE CHANNELS: only a phone holding the channel key
 * forwards a private-channel message. In practice the crypto
 * already enforces this — a non-member cannot open the relay
 * blob or join the keyed mesh session — but the rule is
 * checked explicitly at every forward decision, and the
 * harness proves it.
 *
 * Pure Java over org.json (the store's own entry shape) — no
 * Android types — so the harness proves the envelope, the
 * fan-out, and the loop rules with the exact shipping code.
 */
public final class BridgeRule {
    private BridgeRule() {}

    /** The three walkie roads. */
    public enum Transport {
        MESH("mesh"), BLUETOOTH("bt"), RELAY("relay");

        public final String token;

        Transport(String token) {
            this.token = token;
        }

        static Transport fromToken(String token) {
            if (token == null) return null;
            for (Transport t : values()) {
                if (t.token.equals(token)) return t;
            }
            return null;
        }
    }

    /** Provenance of a relayed message: who really sent it,
     *  when, who relayed it onto this hop, and which roads it
     *  has already been on. Immutable. The "via" name is the
     *  gateway of the CURRENT hop — it changes at each
     *  forward; everything else is stable from the first
     *  forward on. */
    public static final class Relayed {
        public final String bid;
        public final String origName;
        public final String origPub;
        public final long origTs;
        public final String viaName;
        public final Set<Transport> traversed;

        private Relayed(String bid, String origName, String origPub,
                        long origTs, String viaName,
                        Set<Transport> traversed) {
            this.bid = bid;
            this.origName = origName;
            this.origPub = origPub;
            this.origTs = origTs;
            this.viaName = viaName;
            this.traversed = Collections.unmodifiableSet(
                    EnumSet.copyOf(traversed.isEmpty()
                            ? EnumSet.noneOf(Transport.class) : traversed));
        }

        /** Builds provenance, or null when the identity is
         *  unusable (no bid, or no original sender name) —
         *  callers treat null as "not a relayed message". */
        public static Relayed of(String bid, String origName, String origPub,
                                 long origTs, String viaName,
                                 Set<Transport> traversed) {
            if (bid == null || bid.isEmpty()) return null;
            if (origName == null || origName.isEmpty()) return null;
            return new Relayed(bid, origName,
                    origPub == null ? "" : origPub,
                    origTs, viaName == null ? "" : viaName,
                    traversed == null
                            ? EnumSet.noneOf(Transport.class) : traversed);
        }

        /** The provenance stamp for the forwarded copy: the
         *  same bid and origin, this phone named as the
         *  gateway, the arrival road added to traversed. */
        public Relayed forwarded(String gatewayName, Transport arrivedOn) {
            EnumSet<Transport> trav = traversed.isEmpty()
                    ? EnumSet.noneOf(Transport.class)
                    : EnumSet.copyOf(traversed);
            if (arrivedOn != null) trav.add(arrivedOn);
            return new Relayed(bid, origName, origPub, origTs,
                    gatewayName == null ? "" : gatewayName, trav);
        }
    }

    /** A fresh bridge id. */
    public static String newBid() {
        return UUID.randomUUID().toString();
    }

    // ---- arrival bids: one stable identity per message ----

    /**
     * The bridge id for a message arriving on the relay. A
     * relayed message carries its bid; a direct message
     * derives it from the relay client message id — stable
     * across every phone that pulls it, so two gateways
     * forwarding the same relay message mint the SAME bid
     * and receivers land it once. A client message id with
     * the "m:" prefix IS the bid: it names the mesh/
     * Bluetooth spurt the originator's own relay post
     * belongs to (see WalkiePage's spurt mid), so a
     * gatewayed copy dedups against the originator's own
     * post instead of doubling it. An empty id (the relay
     * always sends one, but a hand-built message might not)
     * falls back to a fresh bid.
     */
    public static String relayArrivalBid(String clientMsgId,
                                         Relayed relayed) {
        if (relayed != null) return relayed.bid;
        if (clientMsgId != null && !clientMsgId.isEmpty()) {
            if (clientMsgId.startsWith("m:")) return clientMsgId;
            return "r:" + clientMsgId;
        }
        return newBid();
    }

    /**
     * The bridge id for a message arriving on the mesh or
     * Bluetooth. A relayed message carries its bid; a direct
     * message derives it from the packet's message id ("mid",
     * minted by the sender per message/spurt) — stable
     * across every gateway that hears it, so two gateways
     * forwarding the same mesh message mint the SAME bid.
     * A packet with no mid (a pre-v1.24 sender) falls back
     * to a fresh bid: duplicates across gateways are then
     * possible, and accepted — the sender predates
     * bridging.
     */
    public static String localArrivalBid(String mid, Relayed relayed) {
        if (relayed != null) return relayed.bid;
        if (mid != null && !mid.isEmpty()) return "m:" + mid;
        return newBid();
    }

    // ---- traversed set ----

    /** The wire form: comma-joined tokens in enum order, so
     *  the same set always encodes the same way. */
    public static String travString(Set<Transport> traversed) {
        if (traversed == null || traversed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Transport t : Transport.values()) {
            if (traversed.contains(t)) {
                if (sb.length() > 0) sb.append(',');
                sb.append(t.token);
            }
        }
        return sb.toString();
    }

    /** Parses the wire form; unknown tokens and blanks are
     *  ignored, null reads as empty. */
    public static EnumSet<Transport> parseTrav(String s) {
        EnumSet<Transport> out = EnumSet.noneOf(Transport.class);
        if (s == null) return out;
        for (String part : s.split(",")) {
            Transport t = Transport.fromToken(part.trim());
            if (t != null) out.add(t);
        }
        return out;
    }

    // ---- fan-out ----

    /**
     * The roads a received message forwards to. The connected
     * set names the roads live right now (the caller decides
     * what "connected" means per road — a live local session
     * for mesh/Bluetooth, a derivable channel id for the
     * relay). The result is connected minus the arrival road
     * minus the traversed roads, gated: bridging off, an
     * already-forwarded bid, an active transmission from
     * this phone, or a private channel without the key all
     * yield no targets. Own messages never reach here — the
     * callers bridge received messages only.
     */
    public static EnumSet<Transport> targets(
            Transport arrivedOn,
            EnumSet<Transport> connected,
            boolean bridgingOn,
            boolean isPrivate,
            boolean holdsKey,
            boolean alreadyForwarded,
            boolean transmitting,
            Set<Transport> traversed) {
        EnumSet<Transport> out = EnumSet.noneOf(Transport.class);
        if (!bridgingOn || alreadyForwarded || transmitting) return out;
        if (isPrivate && !holdsKey) return out;
        if (connected == null) return out;
        out.addAll(connected);
        if (arrivedOn != null) out.remove(arrivedOn);
        if (traversed != null) out.removeAll(traversed);
        return out;
    }

    // ---- relay text envelope ----

    /** A relayed text's relay payload: the ordinary
     *  LowDataEnvelope shape plus the provenance keys. The
     *  "ld" marker rides exactly as LowDataEnvelope.build
     *  writes it, so a low-data relayed text keeps its
     *  marker on the far side — and the text is word-coded
     *  on the "w" key (v1.32: every message coded, no
     *  marker), since the gateway re-sends decoded plain
     *  text and the far side decodes it like any direct
     *  text. */
    public static String buildRelayedText(String text, boolean lowData,
                                          Relayed r) {
        return buildRelayedText(text, lowData, r, null);
    }

    /** Scope-aware (v1.29): the gateway teaches the
     *  destination channel its learned words with a "wdt"
     *  block. A null scope means no teaching. */
    public static String buildRelayedText(String text, boolean lowData,
                                          Relayed r, String scope) {
        String plain = text == null ? "" : text;
        WordDict.Encoded e = DictStore.encodeForScope(scope, plain);
        String wire = e == null
                ? WordDict.escapeOnly(plain) : e.wire;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"t\":\"text\",\"w\":\"");
        sb.append(escape(wire));
        sb.append("\"");
        if (lowData) sb.append(",\"ld\":true");
        if (e != null && e.teach != null && !e.teach.isEmpty()) {
            sb.append(",\"wdt\":\"").append(escape(e.teach)).append("\"");
        }
        sb.append(",\"relayed\":true");
        sb.append(",\"bid\":\"").append(escape(r.bid)).append("\"");
        sb.append(",\"orig\":\"").append(escape(r.origName)).append("\"");
        sb.append(",\"origPub\":\"").append(escape(r.origPub)).append("\"");
        sb.append(",\"ots\":\"").append(r.origTs).append("\"");
        sb.append(",\"via\":\"").append(escape(r.viaName)).append("\"");
        sb.append(",\"trav\":\"").append(travString(r.traversed)).append("\"");
        sb.append("}");
        return sb.toString();
    }

    /** Parses provenance from a relay text payload. Returns
     *  null for a direct (non-relayed) text, a malformed
     *  payload, or a relayed payload missing its bid or its
     *  original sender — the caller then treats the message
     *  as direct. The "ots" value is a string of millis by
     *  design (one representation for both parsers); an
     *  unparsable value reads as 0 (unknown). */
    public static Relayed relayedFromTextPayload(String payload) {
        if (payload == null) return null;
        String t = extractString(payload, "t");
        if (!"text".equals(t)) return null;
        if (!hasTrueFlag(payload, "relayed")) return null;
        return relayedFromKeys(payload);
    }

    // ---- relay burst header ----

    /** The burst header's type marker. */
    static final String BURST_TYPE = "rburst";

    /** A relayed burst's relay payload: one header line of
     *  JSON, a newline, then the raw ADPCM bytes verbatim.
     *  The header is single-line by construction (all strings
     *  escaped), so the first newline byte is the boundary. */
    public static byte[] buildRelayedBurstPayload(Relayed r, byte[] adpcm) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"t\":\"").append(BURST_TYPE).append("\"");
        sb.append(",\"bid\":\"").append(escape(r.bid)).append("\"");
        sb.append(",\"orig\":\"").append(escape(r.origName)).append("\"");
        sb.append(",\"origPub\":\"").append(escape(r.origPub)).append("\"");
        sb.append(",\"ots\":\"").append(r.origTs).append("\"");
        sb.append(",\"via\":\"").append(escape(r.viaName)).append("\"");
        sb.append(",\"trav\":\"").append(travString(r.traversed)).append("\"");
        sb.append("}\n");
        byte[] header = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + adpcm.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(adpcm, 0, out, header.length, adpcm.length);
        return out;
    }

    /** A parsed burst header: the provenance, and the offset
     *  where the raw ADPCM body starts. */
    public static final class BurstHeader {
        public final Relayed relayed;
        public final int bodyOffset;

        BurstHeader(Relayed relayed, int bodyOffset) {
            this.relayed = relayed;
            this.bodyOffset = bodyOffset;
        }
    }

    /** Parses a relay payload's burst header. Returns null
     *  for a raw burst (no header), a malformed header, or a
     *  header missing its bid or original sender — the
     *  caller then treats the whole payload as raw ADPCM.
     *  Works on bytes, never on a decoded string: the ADPCM
     *  body is not valid UTF-8, and decoding it would
     *  corrupt the boundary. */
    public static BurstHeader parseBurstHeader(byte[] plain) {
        if (plain == null) return null;
        String prefix = "{\"t\":\"" + BURST_TYPE + "\"";
        byte[] pre = prefix.getBytes(StandardCharsets.UTF_8);
        if (plain.length < pre.length) return null;
        for (int i = 0; i < pre.length; i++) {
            if (plain[i] != pre[i]) return null;
        }
        int nl = -1;
        for (int i = pre.length; i < plain.length; i++) {
            if (plain[i] == '\n') {
                nl = i;
                break;
            }
        }
        if (nl < 0) return null;
        String header = new String(plain, 0, nl, StandardCharsets.UTF_8);
        String t = extractString(header, "t");
        if (!BURST_TYPE.equals(t)) return null;
        Relayed r = relayedFromKeys(header);
        if (r == null) return null;
        return new BurstHeader(r, nl + 1);
    }

    /** Shared key extraction for the relay payload shapes
     *  (text envelope, burst header): bid + orig are
     *  required; everything else defaults. */
    private static Relayed relayedFromKeys(String json) {
        String bid = extractString(json, "bid");
        String orig = extractString(json, "orig");
        if (bid == null || bid.isEmpty()) return null;
        if (orig == null || orig.isEmpty()) return null;
        String origPub = extractString(json, "origPub");
        String via = extractString(json, "via");
        long ots = 0;
        String otsRaw = extractString(json, "ots");
        if (otsRaw != null) {
            try {
                ots = Long.parseLong(otsRaw);
            } catch (Exception ignored) {
            }
        }
        return Relayed.of(bid, orig, origPub == null ? "" : origPub, ots,
                via == null ? "" : via, parseTrav(extractString(json, "trav")));
    }

    // ---- mesh/Bluetooth packet keys (org.json) ----

    /** Stamps the provenance keys onto a packet object the
     *  caller already built (the caller sets the sender
     *  name field — "from" for text, "name" for spurt-start
     *  — to the original sender itself). */
    public static void putRelayKeys(JSONObject o, Relayed r) {
        if (o == null || r == null) return;
        try {
            o.put("bid", r.bid);
            o.put("relayed", true);
            o.put("origPub", r.origPub);
            o.put("ots", String.valueOf(r.origTs));
            o.put("via", r.viaName);
            o.put("trav", travString(r.traversed));
        } catch (Exception ignored) {
        }
    }

    /** Parses provenance from a packet object. nameKey is the
     *  sender-name field ("from" for text packets, "name"
     *  for spurt-start packets) — it carries the original
     *  sender's name. Returns null when the packet is not
     *  marked relayed, or is missing its bid or sender. */
    public static Relayed relayedFromJson(JSONObject o, String nameKey) {
        if (o == null) return null;
        if (!o.optBoolean("relayed", false)) return null;
        String bid = o.optString("bid", "");
        String orig = o.optString(nameKey, "");
        if (bid.isEmpty() || orig.isEmpty()) return null;
        long ots = 0;
        Object otsRaw = o.opt("ots");
        if (otsRaw instanceof Number) {
            ots = ((Number) otsRaw).longValue();
        } else if (otsRaw instanceof String) {
            try {
                ots = Long.parseLong((String) otsRaw);
            } catch (Exception ignored) {
            }
        }
        return Relayed.of(bid, orig, o.optString("origPub", ""), ots,
                o.optString("via", ""), parseTrav(o.optString("trav", "")));
    }

    // ---- thread-entry stamping ----

    /** Stamps just the bridge id onto a thread entry — for
     *  direct and own entries, whose bid is the cross-road
     *  identity without any relayed marker. */
    public static void stampBid(JSONObject e, String bid) {
        if (e == null || bid == null || bid.isEmpty()) return;
        try {
            e.put("bid", bid);
        } catch (Exception ignored) {
        }
    }

    /** Stamps relayed provenance onto a thread entry the
     *  caller already built: the entry shows the ORIGINAL
     *  sender (name + relay pubkey for the voice-profile
     *  lookup) at the ORIGINAL time, marked relayed with the
     *  gateway's name and the traversed set, so a later
     *  bridge decision can reconstruct the full picture from
     *  the entry alone. */
    public static void stampRelayedEntry(JSONObject e, Relayed r) {
        if (e == null || r == null) return;
        try {
            e.put("from", r.origName);
            e.put("sender", r.origPub);
            if (r.origTs > 0) e.put("ts", r.origTs);
            e.put("bid", r.bid);
            e.put("relayed", true);
            e.put("via", r.viaName);
            e.put("trav", travString(r.traversed));
        } catch (Exception ignored) {
        }
    }

    /** Reconstructs provenance from a stamped entry, or null
     *  when the entry is not a relayed message. The page's
     *  background-arrival scan uses this: a relay entry the
     *  subscription service stored is bridged from its entry
     *  alone, with no second copy of the relay state. */
    public static Relayed relayedFromEntry(JSONObject e) {
        if (e == null || !e.optBoolean("relayed", false)) return null;
        return Relayed.of(e.optString("bid", ""), e.optString("from", ""),
                e.optString("sender", ""), e.optLong("ts", 0),
                e.optString("via", ""), parseTrav(e.optString("trav", "")));
    }

    /** True when any entry in the list carries this bridge
     *  id — the cross-road duplicate check: the same logical
     *  message arriving on a second road is already in the
     *  thread and must not land twice. An empty id never
     *  matches. */
    public static boolean entryWithBid(List<JSONObject> thread, String bid) {
        if (thread == null || bid == null || bid.isEmpty()) return false;
        for (JSONObject e : thread) {
            if (e != null && bid.equals(e.optString("bid", ""))) return true;
        }
        return false;
    }

    /** The bridge decision is made exactly once per received
     *  entry, at first sight: entries the scan examines are
     *  marked, forwarded or not, so a later scan never
     *  republishes an old message when a road comes up. */
    public static boolean isBridgeSeen(JSONObject e) {
        return e != null && e.optBoolean("bridgeSeen", false);
    }

    public static void markBridgeSeen(JSONObject e) {
        if (e == null) return;
        try {
            e.put("bridgeSeen", true);
        } catch (Exception ignored) {
        }
    }

    // ---- tiny JSON scanners (self-contained, no org.json) ----

    /** JSON-escapes a string value, mirroring
     *  VoiceProfile.escape: the relay payload builders must
     *  not depend on the voice-profile class. */
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

    /** Extracts a string field's unescaped value, or null
     *  when absent, not a string, or malformed — the same
     *  shape as VoiceProfile.extractString. */
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
                                int cp = Integer.parseInt(
                                        json.substring(i + 2, i + 6), 16);
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

    /** True only when "key":true (the JSON literal) appears —
     *  the same rule as LowDataEnvelope's flag check. */
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

    private static int findKey(String json, String key) {
        String needle = "\"" + key + "\"";
        int idx = 0;
        while (true) {
            int at = json.indexOf(needle, idx);
            if (at < 0) return -1;
            int i = at + needle.length();
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ':') i++;
                else break;
            }
            if (i < json.length()) return i;
            idx = at + needle.length();
        }
    }

    private static int skipWs(String json, int i) {
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

}
