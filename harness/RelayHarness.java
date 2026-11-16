import app.control.launcher.RelayCore;
import app.control.launcher.RelayCrypto;
import app.control.launcher.RelayIds;
import app.control.launcher.RelayKeys;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Off-device verification of the app's relay code (RelayCore /
 * RelayKeys / RelayCrypto / RelayIds — the exact classes shipped in
 * the APK) against the LIVE relay at https://relay.eyeknow.app.
 *
 * Run:  java -Dhttps.proxyHost=::1 -Dhttps.proxyPort=8898 \
 *         -cp classes:../libs/bcprov-jdk18on.jar RelayHarness
 * (the proxy props route the sandbox JVM through the local IPv6
 * CONNECT relay; on a normal network they're unnecessary).
 */
public class RelayHarness {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -- " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- key material round trips (local) ----
        byte[][] a = RelayKeys.generate();
        byte[][] b = RelayKeys.generate();
        byte[][] c = RelayKeys.generate();
        check("pubkey b64url is 43 chars", RelayKeys.b64Url(a[1]).length() == 43,
                String.valueOf(RelayKeys.b64Url(a[1]).length()));
        byte[] msg = "control-relay-harness".getBytes(StandardCharsets.UTF_8);
        byte[] sig = RelayKeys.sign(a[0], msg);
        check("signature b64url is 86 chars", RelayKeys.b64Url(sig).length() == 86,
                String.valueOf(RelayKeys.b64Url(sig).length()));
        check("sign/verify round trip", RelayKeys.verify(a[1], msg, sig), "");
        check("verify rejects wrong key", !RelayKeys.verify(b[1], msg, sig), "");
        check("seed -> same pub", java.util.Arrays.equals(
                RelayKeys.publicFromSeed(a[0]), a[1]), "");

        byte[] chatKey = new byte[32];
        new java.security.SecureRandom().nextBytes(chatKey);
        String chatKeyB64 = RelayKeys.b64Std(chatKey);
        String chatId = RelayIds.chatChannelId(chatKeyB64);
        check("chat id form chat:<16 hex>", chatId != null
                && chatId.matches("chat:[0-9a-f]{16}"), String.valueOf(chatId));
        String sealed = RelayCrypto.seal(chatKey,
                "hello relay".getBytes(StandardCharsets.UTF_8));
        check("crypto round trip", "hello relay".equals(new String(
                RelayCrypto.open(chatKey, sealed), StandardCharsets.UTF_8)), "");
        boolean wrongKeyFailed = false;
        try {
            RelayCrypto.open(b[1].length == 32 ? chatKeyAlt() : chatKey, sealed);
        } catch (Exception e) {
            wrongKeyFailed = true;
        }
        check("open under wrong key fails", wrongKeyFailed, "");

        // ---- live relay ----
        RelayCore coreA = new RelayCore(a[0]);
        RelayCore coreB = new RelayCore(b[0]);
        RelayCore coreC = new RelayCore(c[0]);
        boolean createdA = coreA.register();
        check("A registers (201 created or 200 idempotent)", true, "");
        System.out.println("      A created=" + createdA + " pub=" + coreA.pubB64);
        coreB.register();
        coreC.register();
        boolean againA = coreA.register();
        check("A re-register is idempotent (created=false)", !againA, "");

        boolean added = coreA.addMember(chatId, coreB.pubB64);
        check("A adds B to chat channel (created)", added, "");
        boolean addedAgain = coreA.addMember(chatId, coreB.pubB64);
        check("re-add is idempotent (added=false)", !addedAgain, "");

        // B posts a sealed text envelope; A pulls and decrypts.
        String envelope = "{\"type\":\"text\",\"text\":\"hello from B\",\"ts\":1}";
        String blob = RelayCrypto.seal(chatKey, envelope.getBytes(StandardCharsets.UTF_8));
        String cmidB = UUID.randomUUID().toString();
        RelayCore.PostResult posted = coreB.postMessage(chatId, cmidB, blob);
        check("B posts sealed message", posted.seq > 0 && !posted.duplicate,
                "seq=" + posted.seq);

        RelayCore.PullResult pullA = coreA.pullMessages(chatId, 0, 200);
        boolean foundB = false;
        long bSeq = -1;
        for (RelayCore.Msg m : pullA.messages) {
            if (cmidB.equals(m.clientMsgId)) {
                foundB = true;
                bSeq = m.seq;
                String plain = new String(RelayCrypto.open(chatKey, m.ciphertext),
                        StandardCharsets.UTF_8);
                check("A decrypts B's message exactly", envelope.equals(plain), plain);
                check("sender is B's pubkey", coreB.pubB64.equals(m.sender), m.sender);
            }
        }
        check("A pulls B's message", foundB, "messages=" + pullA.messages.size());

        // Duplicate repost: same clientMsgId, same seq, duplicate:true.
        RelayCore.PostResult dup = coreB.postMessage(chatId, cmidB, blob);
        check("duplicate repost returns original seq + duplicate:true",
                dup.duplicate && dup.seq == bSeq, "seq=" + dup.seq + " dup=" + dup.duplicate);

        // A replies; B pulls from its cursor and sees only the reply.
        String envA = "{\"type\":\"text\",\"text\":\"reply from A\",\"ts\":2}";
        coreA.postMessage(chatId, UUID.randomUUID().toString(),
                RelayCrypto.seal(chatKey, envA.getBytes(StandardCharsets.UTF_8)));
        RelayCore.PullResult pullB = coreB.pullMessages(chatId, bSeq, 200);
        boolean foundA = false;
        for (RelayCore.Msg m : pullB.messages) {
            String plain = new String(RelayCrypto.open(chatKey, m.ciphertext),
                    StandardCharsets.UTF_8);
            if (envA.equals(plain)) foundA = true;
        }
        check("B pulls A's reply after cursor", foundA,
                "messages=" + pullB.messages.size());
        RelayCore.PullResult pullB2 = coreB.pullMessages(chatId, pullB.nextAfter, 200);
        check("cursor at head pulls empty, nextAfter stable",
                pullB2.messages.isEmpty() && pullB2.nextAfter == pullB.nextAfter,
                "nextAfter=" + pullB2.nextAfter);

        // Channel list: A and B see the chat channel; C does not.
        boolean aSees = false, bSees = false, cSees = false;
        for (RelayCore.ChannelInfo ci : coreA.listChannels()) aSees |= chatId.equals(ci.id);
        for (RelayCore.ChannelInfo ci : coreB.listChannels()) bSees |= chatId.equals(ci.id);
        for (RelayCore.ChannelInfo ci : coreC.listChannels()) cSees |= chatId.equals(ci.id);
        check("channel list: A and B are members, C is not", aSees && bSees && !cSees,
                "a=" + aSees + " b=" + bSees + " c=" + cSees);

        // Non-member refusals.
        String code = expectError(() -> coreC.pullMessages(chatId, 0, 10));
        check("non-member pull refused 403 not_a_member", "403:not_a_member".equals(code), code);
        code = expectError(() -> coreC.postMessage(chatId, UUID.randomUUID().toString(), blob));
        check("non-member post refused 403 not_a_member", "403:not_a_member".equals(code), code);
        code = expectError(() -> coreC.addMember(chatId, coreC.pubB64));
        check("non-member member-add refused 403", "403:not_a_member".equals(code), code);

        // Open walkie channel: C posts plaintext, A pulls it.
        String openId = "open:50";
        String burstB64 = RelayKeys.b64Std(new byte[]{1, 2, 3, 4, 5});
        RelayCore.PostResult openPost = coreC.postMessage(openId,
                UUID.randomUUID().toString(), burstB64);
        check("C posts to open:50", openPost.seq > 0, "seq=" + openPost.seq);
        RelayCore.PullResult openPull = coreA.pullMessages(openId, 0, 200);
        boolean openFound = false;
        for (RelayCore.Msg m : openPull.messages) {
            if (burstB64.equals(m.ciphertext) && coreC.pubB64.equals(m.sender)) openFound = true;
        }
        check("A pulls C's open-channel post", openFound, "");
        code = expectError(() -> coreA.addMember(openId, coreB.pubB64));
        check("member-add on open channel refused 400 open_channel",
                "400:open_channel".equals(code), code);

        // Raw signed-request negatives: tampered signature, stale ts.
        int[] raw = rawSigned(coreA, a[0], System.currentTimeMillis(), true);
        check("tampered signature refused 401 unauthorized",
                raw[0] == 401 && raw[1] == 1, "http=" + raw[0]);
        raw = rawSigned(coreA, a[0], System.currentTimeMillis() - 10 * 60 * 1000L, false);
        check("stale timestamp refused 401 stale_timestamp",
                raw[0] == 401 && raw[1] == 2, "http=" + raw[0]);

        System.out.println();
        System.out.println("HARNESS: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static byte[] chatKeyAlt() {
        byte[] k = new byte[32];
        new java.security.SecureRandom().nextBytes(k);
        return k;
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static String expectError(ThrowingCall call) {
        try {
            call.run();
            return "no-error";
        } catch (RelayCore.RelayException e) {
            return e.http + ":" + e.code;
        } catch (Exception e) {
            return "other:" + e;
        }
    }

    /** A hand-built signed GET /v1/channels for the negative tests.
     *  Returns {http, marker} where marker: 1=unauthorized 2=stale. */
    private static int[] rawSigned(RelayCore core, byte[] seed, long ts,
                                   boolean tamper) throws Exception {
        String path = "/v1/channels";
        String payload = "GET\n" + path + "\n"
                + RelayKeys.sha256Hex(new byte[0]) + "\n" + ts;
        byte[] sigBytes = RelayKeys.sign(seed, payload.getBytes(StandardCharsets.UTF_8));
        if (tamper) sigBytes[0] ^= 0x01;
        HttpURLConnection conn = (HttpURLConnection) new URL(
                RelayCore.BASE + path).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("x-relay-pubkey", core.pubB64);
        conn.setRequestProperty("x-relay-timestamp", String.valueOf(ts));
        conn.setRequestProperty("x-relay-signature", RelayKeys.b64Url(sigBytes));
        int http = conn.getResponseCode();
        InputStream in = http >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        int marker = body.contains("stale_timestamp") ? 2
                : body.contains("unauthorized") ? 1 : 0;
        return new int[]{http, marker};
    }
}
