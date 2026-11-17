package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Control Relay protocol client (PROTOCOL.md v1), implemented
 * exactly: every /v1 request is Ed25519-signed over
 * "METHOD\nPATH\nBODY-HASH\nTIMESTAMP" with the timestamp in
 * milliseconds (§3); channel ids are percent-encoded in paths and the
 * ENCODED path is signed; registration is idempotent (§4).
 *
 * Pure Java (HttpURLConnection + {@link RelayKeys} + {@link RelayJson})
 * — no Android, no org.json — so the JVM harness exercises this exact
 * code against the live relay. HTTP proxy system properties are
 * honored by HttpURLConnection's default selector (the harness uses
 * them; Android sets none and goes direct).
 */
public final class RelayCore {

    public static final String BASE = "https://relay.eyeknow.app";

    /** A relay refusal: HTTP status + the protocol's stable error code. */
    public static final class RelayException extends Exception {
        public final int http;
        public final String code;

        RelayException(int http, String code, String message) {
            super(message);
            this.http = http;
            this.code = code;
        }
    }

    public static final class ChannelInfo {
        public String id = "";
        public boolean open;
        public String creator = "";
        public int memberCount;
        public long lastSeq;
    }

    public static final class PostResult {
        public long seq;
        public boolean duplicate;
    }

    public static final class Msg {
        public long seq;
        public String sender = "";
        public String clientMsgId = "";
        public String ciphertext = "";
        public String createdAt = "";
    }

    public static final class PullResult {
        public long nextAfter;
        public final List<Msg> messages = new ArrayList<>();
    }

    private final byte[] seed;
    private final byte[] pub;
    public final String pubB64;

    public RelayCore(byte[] seed) {
        this.seed = seed.clone();
        this.pub = RelayKeys.publicFromSeed(seed);
        this.pubB64 = RelayKeys.b64Url(pub);
    }

    public byte[] publicKey() {
        return pub.clone();
    }

    // ---- endpoints ----

    /** §4 — returns true when this call created the registration. */
    public boolean register() throws Exception {
        Map<String, Object> res = call("POST", "/v1/devices",
                "{\"pubkey\":\"" + pubB64 + "\"}");
        return RelayJson.bool(res, "created");
    }

    /** §7.1 — every channel the caller is a member of. */
    public List<ChannelInfo> listChannels() throws Exception {
        Map<String, Object> res = call("GET", "/v1/channels", null);
        List<ChannelInfo> out = new ArrayList<>();
        for (Object o : RelayJson.arr(res.get("channels"))) {
            Map<String, Object> m = RelayJson.obj(o);
            ChannelInfo ci = new ChannelInfo();
            ci.id = RelayJson.str(m, "id");
            ci.open = RelayJson.bool(m, "open");
            ci.creator = RelayJson.str(m, "creator");
            ci.memberCount = (int) RelayJson.lng(m, "memberCount", 0);
            ci.lastSeq = RelayJson.lng(m, "lastSeq", 0);
            out.add(ci);
        }
        return out;
    }

    /** §7.3 — returns true when the member was newly added. */
    public boolean addMember(String channelId, String memberPubB64) throws Exception {
        Map<String, Object> res = call("POST",
                "/v1/channels/" + encodePath(channelId) + "/members",
                "{\"pubkey\":\"" + memberPubB64 + "\"}");
        return RelayJson.bool(res, "added");
    }

    /** §7.2 — idempotent on clientMsgId. */
    public PostResult postMessage(String channelId, String clientMsgId,
                                  String ciphertextB64) throws Exception {
        Map<String, Object> res = call("POST",
                "/v1/channels/" + encodePath(channelId) + "/messages",
                "{\"clientMsgId\":\"" + clientMsgId
                        + "\",\"ciphertext\":\"" + ciphertextB64 + "\"}");
        PostResult pr = new PostResult();
        pr.seq = RelayJson.lng(res, "seq", -1);
        pr.duplicate = RelayJson.bool(res, "duplicate");
        return pr;
    }

    /** §7.5 — propose learned words for the shared dictionary.
     *  Returns the accepted count. Same signed-request path as
     *  every other endpoint; the body is built by DictSubmit.
     *  A 429 (daily word budget) or 401 (unregistered key)
     *  arrives as a RelayException, like any other refusal. */
    public int proposeWords(List<String> words) throws Exception {
        Map<String, Object> res = call("POST", "/v1/dict/proposals",
                DictSubmit.proposalBody(words));
        return (int) RelayJson.lng(res, "accepted", 0);
    }

    /** §7.4 — pull messages after a seq cursor. */
    public PullResult pullMessages(String channelId, long after, int limit)
            throws Exception {
        String path = "/v1/channels/" + encodePath(channelId)
                + "/messages?after=" + after + "&limit=" + limit;
        Map<String, Object> res = call("GET", path, null);
        PullResult pr = new PullResult();
        pr.nextAfter = RelayJson.lng(res, "nextAfter", after);
        for (Object o : RelayJson.arr(res.get("messages"))) {
            Map<String, Object> m = RelayJson.obj(o);
            Msg msg = new Msg();
            msg.seq = RelayJson.lng(m, "seq", 0);
            msg.sender = RelayJson.str(m, "sender");
            msg.clientMsgId = RelayJson.str(m, "clientMsgId");
            msg.ciphertext = RelayJson.str(m, "ciphertext");
            msg.createdAt = RelayJson.str(m, "createdAt");
            pr.messages.add(msg);
        }
        return pr;
    }

    // ---- signed transport (§3) ----

    /** One signed request, with its outcome: HTTP status, parsed
     *  body, and the round-trip time in milliseconds. Transport
     *  failures (no connection, timeout, DNS) throw, as before;
     *  an HTTP error status does NOT throw here — the caller
     *  decides, so a probe can measure a completed round trip
     *  even when the server refuses it. */
    private static final class CallResult {
        final int http;
        final Map<String, Object> map;
        final long elapsedMs;

        CallResult(int http, Map<String, Object> map, long elapsedMs) {
            this.http = http;
            this.map = map;
            this.elapsedMs = elapsedMs;
        }
    }

    private CallResult callRaw(String method, String path, String bodyJson,
                               int connectTimeoutMs, int readTimeoutMs)
            throws Exception {
        byte[] body = bodyJson == null ? new byte[0]
                : bodyJson.getBytes(StandardCharsets.UTF_8);
        String ts = String.valueOf(System.currentTimeMillis());
        String payload = method + "\n" + path + "\n"
                + RelayKeys.sha256Hex(body) + "\n" + ts;
        String sig = RelayKeys.b64Url(RelayKeys.sign(seed,
                payload.getBytes(StandardCharsets.UTF_8)));
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestProperty("x-relay-pubkey", pubB64);
        conn.setRequestProperty("x-relay-timestamp", ts);
        conn.setRequestProperty("x-relay-signature", sig);
        long start = System.currentTimeMillis();
        if (bodyJson != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
        }
        int http = conn.getResponseCode();
        String text = readAll(http >= 200 && http < 300
                ? conn.getInputStream() : conn.getErrorStream());
        long elapsedMs = System.currentTimeMillis() - start;
        Object parsed = null;
        try {
            parsed = RelayJson.parse(text);
        } catch (Exception ignored) {
        }
        return new CallResult(http, RelayJson.obj(parsed), elapsedMs);
    }

    private Map<String, Object> call(String method, String path, String bodyJson)
            throws Exception {
        CallResult r = callRaw(method, path, bodyJson, 15000, 20000);
        if (r.http < 200 || r.http >= 300) {
            String code = RelayJson.str(r.map, "error");
            String message = RelayJson.str(r.map, "message");
            throw new RelayException(r.http,
                    code.isEmpty() ? "http_" + r.http : code,
                    message.isEmpty() ? ("Relay answered HTTP " + r.http) : message);
        }
        return r.map;
    }

    /**
     * The speed probe (v1.23): one signed GET to the read-only
     * channel list, bounded to 5 seconds, returning the
     * round-trip time in milliseconds. Nothing is posted, no
     * user data leaves the phone beyond the signed identity
     * header every relay call carries, and no state changes —
     * the channel list is a read. Any completed HTTP round
     * trip counts (even a refusal: the server answered, so the
     * link is up); only a transport failure throws, and the
     * caller reads that as "unreachable". The timeouts are
     * tighter than the relay loop's on purpose: a probe that
     * hangs for 35 seconds is not a measurement.
     */
    public long probeRoundTrip() throws Exception {
        return callRaw("GET", "/v1/channels", null, 5000, 5000).elapsedMs;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    /** encodeURIComponent for channel ids: everything outside the
     *  unreserved set becomes %XX (ids contain ':' → %3A). */
    public static String encodePath(String channelId) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < channelId.length(); i++) {
            char c = channelId.charAt(i);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.'
                    || c == '~';
            if (unreserved) {
                sb.append(c);
            } else {
                sb.append(String.format("%%%02X", (int) c));
            }
        }
        return sb.toString();
    }
}
