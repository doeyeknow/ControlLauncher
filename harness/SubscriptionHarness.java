package app.control.launcher;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Off-device verification for the v1.19 channel subscriptions,
 * exercising the exact shipping pure-Java code
 * (SubscriptionPolicy, RelayIngest):
 *
 * Legs:
 *   1. SET MANAGEMENT: normalize dedups, sorts, clamps to
 *      1..50, drops nulls and out-of-range numbers; toggle
 *      adds/removes idempotently, never admits an
 *      out-of-range number, and leaves its input untouched;
 *      MAX_SLOT is 50 (mirrors Profile.BANK_SIZE).
 *   2. SCHEDULE: spacing is max(1s, 4s/n); the request rate
 *      never exceeds 60/minute for any subscription count
 *      0..60 (a 10x margin under the relay's 600/minute);
 *      the per-channel revisit period is n * spacing and
 *      never under 4s; spacing never under 1s.
 *   3. OFFLINE BACKOFF: 0 failures means no backoff; 1 → 15s,
 *      2 → 30s, 3+ → 60s capped.
 *   4. BLOCKED CHANNELS: retry only after 10 skipped cycles;
 *      error classification maps not_a_member /
 *      channel_not_found / rate_limited exactly, and unknown
 *      codes and nulls to OFFLINE.
 *   5. DECODE: a sealed text envelope decodes to text (with
 *      and without the low-data marker, and with a
 *      non-literal "ld" value reading as ordinary); a plain
 *      base64 envelope decodes on an open channel; raw bytes
 *      decode as a burst with the right sample count; a wrong
 *      key and a tampered blob decode to null (not ours to
 *      show); malformed JSON decodes as a burst, never a
 *      crash.
 *   6. DEDUP: alreadyStored finds a stored cmid, misses an
 *      absent one, never matches an empty or null id, and is
 *      null-safe over the list and its members.
 *   7. STAMP: stampEntry writes cmid + sender always, the
 *      low-data marker only for a marked text, and the burst
 *      blob only for a voice entry; null inputs never throw.
 *
 * Run (from ~/workspace/control-launcher), with the real
 * org.json jar AND bcprov on the classpath (RelayKeys uses
 * Bouncy Castle Ed25519; android.jar must not come first, or
 * its stub org.json shadows the real one):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar:libs/bcprov-jdk18on.jar \
 *     -d harness/classes harness/SubscriptionHarness.java
 *   java -cp harness/classes:harness/org-json.jar:libs/bcprov-jdk18on.jar \
 *     app.control.launcher.SubscriptionHarness
 *
 * What this harness cannot prove: the service lifecycle, the
 * boot receiver, background polling on a real phone, TTS
 * read-aloud from the background, or the subscription UI in
 * hand — all of that is Adam's on-device test.
 */
public class SubscriptionHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        setManagement();
        schedule();
        backoff();
        blocked();
        decode();
        dedup();
        stamp();
        System.out.println("SubscriptionHarness: " + checks + " checks, "
                + failures + " failures");
        if (!failed.isEmpty()) {
            for (String f : failed) System.out.println("  FAILED: " + f);
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            failed.add(name);
            System.out.println("FAIL  " + name);
        }
    }

    // ---- 1. set management ----

    private static void setManagement() {
        check("MAX_SLOT is 50", SubscriptionPolicy.MAX_SLOT == 50);
        TreeSet<Integer> n = SubscriptionPolicy.normalizeSet(
                Arrays.asList(7, 3, 7, 50, 1, 0, 51, -2, null, 12));
        check("normalize dedups/sorts/clamps",
                n.equals(new TreeSet<>(Arrays.asList(1, 3, 7, 12, 50))));
        check("normalize null is empty",
                SubscriptionPolicy.normalizeSet(null).isEmpty());
        check("normalize empty is empty",
                SubscriptionPolicy.normalizeSet(Collections.emptyList()).isEmpty());

        TreeSet<Integer> base = new TreeSet<>(Arrays.asList(3, 7));
        TreeSet<Integer> added = SubscriptionPolicy.toggle(base, 12, true);
        check("toggle add", added.equals(new TreeSet<>(Arrays.asList(3, 7, 12))));
        check("toggle leaves input untouched",
                base.equals(new TreeSet<>(Arrays.asList(3, 7))));
        check("toggle add idempotent",
                SubscriptionPolicy.toggle(base, 7, true).equals(base));
        check("toggle remove",
                SubscriptionPolicy.toggle(base, 3, false).equals(
                        new TreeSet<>(Collections.singletonList(7))));
        check("toggle remove absent is no-op",
                SubscriptionPolicy.toggle(base, 9, false).equals(base));
        check("toggle rejects 0",
                SubscriptionPolicy.toggle(base, 0, true).equals(base));
        check("toggle rejects 51",
                SubscriptionPolicy.toggle(base, 51, true).equals(base));
        check("toggle accepts 1 and 50",
                SubscriptionPolicy.toggle(Collections.emptyList(), 1, true)
                        .contains(1)
                        && SubscriptionPolicy.toggle(Collections.emptyList(), 50, true)
                        .contains(50));
    }

    // ---- 2. schedule ----

    private static void schedule() {
        check("spacing 0 is base period",
                SubscriptionPolicy.requestSpacingMs(0) == 4000);
        check("spacing negative is base period",
                SubscriptionPolicy.requestSpacingMs(-3) == 4000);
        check("spacing 1 is 4s", SubscriptionPolicy.requestSpacingMs(1) == 4000);
        check("spacing 2 is 2s", SubscriptionPolicy.requestSpacingMs(2) == 2000);
        check("spacing 4 is 1s", SubscriptionPolicy.requestSpacingMs(4) == 1000);
        check("spacing 50 floors at 1s",
                SubscriptionPolicy.requestSpacingMs(50) == 1000);
        check("spacing 1000 floors at 1s",
                SubscriptionPolicy.requestSpacingMs(1000) == 1000);
        boolean rateOk = true;
        boolean periodOk = true;
        boolean spacingOk = true;
        for (int n = 0; n <= 60; n++) {
            if (SubscriptionPolicy.requestsPerMinute(n) > 60) rateOk = false;
            if (SubscriptionPolicy.channelPeriodMs(n) < 4000) periodOk = false;
            if (SubscriptionPolicy.requestSpacingMs(n) < 1000) spacingOk = false;
        }
        check("request rate never exceeds 60/min for 0..60 subs", rateOk);
        check("per-channel period never under 4s for 0..60 subs", periodOk);
        check("spacing never under 1s for 0..60 subs", spacingOk);
        check("rate limit margin is 10x",
                SubscriptionPolicy.RATE_LIMIT_PER_MIN == 600);
        check("1 sub: 15 req/min",
                SubscriptionPolicy.requestsPerMinute(1) == 15);
        check("4 subs: 60 req/min",
                SubscriptionPolicy.requestsPerMinute(4) == 60);
        check("50 subs: 60 req/min, 50s period",
                SubscriptionPolicy.requestsPerMinute(50) == 60
                        && SubscriptionPolicy.channelPeriodMs(50) == 50_000);
        check("period is n * spacing",
                SubscriptionPolicy.channelPeriodMs(7)
                        == SubscriptionPolicy.requestSpacingMs(7) * 7);
    }

    // ---- 3. offline backoff ----

    private static void backoff() {
        check("backoff 0 is none", SubscriptionPolicy.offlineBackoffMs(0) == 0);
        check("backoff negative is none",
                SubscriptionPolicy.offlineBackoffMs(-1) == 0);
        check("backoff 1 is 15s", SubscriptionPolicy.offlineBackoffMs(1) == 15_000);
        check("backoff 2 is 30s", SubscriptionPolicy.offlineBackoffMs(2) == 30_000);
        check("backoff 3 is 60s", SubscriptionPolicy.offlineBackoffMs(3) == 60_000);
        check("backoff 100 caps at 60s",
                SubscriptionPolicy.offlineBackoffMs(100) == 60_000);
        check("backoff cap constant is 60s",
                SubscriptionPolicy.MAX_OFFLINE_BACKOFF_MS == 60_000);
    }

    // ---- 4. blocked channels ----

    private static void blocked() {
        boolean early = true;
        for (int i = 0; i < 10; i++) {
            if (SubscriptionPolicy.shouldRetryBlocked(i)) early = false;
        }
        check("no retry before 10 skipped cycles", early);
        check("retry at 10", SubscriptionPolicy.shouldRetryBlocked(10));
        check("retry after 10", SubscriptionPolicy.shouldRetryBlocked(25));
        check("skip constant is 10",
                SubscriptionPolicy.BLOCKED_SKIP_CYCLES == 10);
        check("not_a_member classifies",
                SubscriptionPolicy.classifyError("not_a_member")
                        == SubscriptionPolicy.ChannelState.BLOCKED_NOT_MEMBER);
        check("channel_not_found classifies",
                SubscriptionPolicy.classifyError("channel_not_found")
                        == SubscriptionPolicy.ChannelState.BLOCKED_NOT_FOUND);
        check("rate_limited classifies",
                SubscriptionPolicy.classifyError("rate_limited")
                        == SubscriptionPolicy.ChannelState.RATE_LIMITED);
        check("network classifies OFFLINE",
                SubscriptionPolicy.classifyError("network")
                        == SubscriptionPolicy.ChannelState.OFFLINE);
        check("unknown code classifies OFFLINE",
                SubscriptionPolicy.classifyError("bogus_code")
                        == SubscriptionPolicy.ChannelState.OFFLINE);
        check("null classifies OFFLINE",
                SubscriptionPolicy.classifyError(null)
                        == SubscriptionPolicy.ChannelState.OFFLINE);
        check("empty classifies OFFLINE",
                SubscriptionPolicy.classifyError("")
                        == SubscriptionPolicy.ChannelState.OFFLINE);
    }

    // ---- 5. decode ----

    private static byte[] key(byte fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, fill);
        return k;
    }

    private static void decode() {
        byte[] k1 = key((byte) 0x11);
        byte[] k2 = key((byte) 0x22);

        String env = LowDataEnvelope.build("hello there", false);
        RelayIngest.Decoded d = RelayIngest.decode(k1,
                RelayCrypto.seal(k1, env.getBytes(StandardCharsets.UTF_8)));
        check("sealed text decodes", d != null && d.isText);
        // v1.32: the text codes, so the lowercase sentence
        // start normalizes.
        check("sealed text content", d != null && "Hello there".equals(d.text));
        check("typed text not low-data", d != null && !d.lowData);
        check("text has zero samples", d != null && d.samples == 0);

        String ldEnv = LowDataEnvelope.build("low data words", true);
        RelayIngest.Decoded ld = RelayIngest.decode(k1,
                RelayCrypto.seal(k1, ldEnv.getBytes(StandardCharsets.UTF_8)));
        // "low" is not a dictionary word: it travels as an
        // escape, verbatim — no normalization applies.
        check("low-data marker survives", ld != null && ld.lowData
                && "low data words".equals(ld.text));

        String fakeLd = "{\"t\":\"text\",\"text\":\"x\",\"ld\":\"yes\"}";
        RelayIngest.Decoded fake = RelayIngest.decode(k1,
                RelayCrypto.seal(k1, fakeLd.getBytes(StandardCharsets.UTF_8)));
        check("non-literal ld reads ordinary",
                fake != null && fake.isText && !fake.lowData);

        RelayIngest.Decoded open = RelayIngest.decode(null,
                RelayKeys.b64Std(env.getBytes(StandardCharsets.UTF_8)));
        check("open-channel plaintext decodes",
                open != null && open.isText && "Hello there".equals(open.text));

        byte[] burst = new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        RelayIngest.Decoded b = RelayIngest.decode(k1,
                RelayCrypto.seal(k1, burst));
        check("raw bytes decode as burst", b != null && !b.isText);
        check("burst sample count", b != null && b.samples == Adpcm.samplesIn(burst));
        check("burst plain bytes kept", b != null && Arrays.equals(b.plain, burst));

        RelayIngest.Decoded wrong = RelayIngest.decode(k2,
                RelayCrypto.seal(k1, env.getBytes(StandardCharsets.UTF_8)));
        check("wrong key decodes to null", wrong == null);

        String sealed = RelayCrypto.seal(k1, env.getBytes(StandardCharsets.UTF_8));
        byte[] tampered = RelayKeys.b64StdDecode(sealed);
        tampered[tampered.length - 1] ^= 0x01;
        check("tampered blob decodes to null",
                RelayIngest.decode(k1, RelayKeys.b64Std(tampered)) == null);

        check("garbage ciphertext decodes to null",
                RelayIngest.decode(k1, "!!!not-base64!!!") == null);
        check("null ciphertext decodes to null",
                RelayIngest.decode(k1, null) == null);

        String malformed = "{not json";
        RelayIngest.Decoded m = RelayIngest.decode(null,
                RelayKeys.b64Std(malformed.getBytes(StandardCharsets.UTF_8)));
        check("malformed JSON decodes as burst, never crashes",
                m != null && !m.isText);

        String otherType = "{\"t\":\"other\",\"text\":\"x\"}";
        RelayIngest.Decoded o = RelayIngest.decode(null,
                RelayKeys.b64Std(otherType.getBytes(StandardCharsets.UTF_8)));
        check("non-text envelope decodes as burst", o != null && !o.isText);
    }

    // ---- 6. dedup ----

    private static JSONObject entryWithCmid(String cmid) {
        JSONObject e = new JSONObject();
        try {
            e.put("cmid", cmid);
        } catch (Exception ignored) {
        }
        return e;
    }

    private static void dedup() {
        List<JSONObject> thread = new ArrayList<>();
        thread.add(entryWithCmid("aaa"));
        thread.add(entryWithCmid("bbb"));
        thread.add(new JSONObject());
        thread.add(null);
        check("stored cmid matches",
                RelayIngest.alreadyStored(thread, "aaa"));
        check("absent cmid misses",
                !RelayIngest.alreadyStored(thread, "ccc"));
        check("empty cmid never matches",
                !RelayIngest.alreadyStored(thread, ""));
        check("null cmid never matches",
                !RelayIngest.alreadyStored(thread, null));
        check("null thread is safe",
                !RelayIngest.alreadyStored(null, "aaa"));
        check("empty thread misses",
                !RelayIngest.alreadyStored(new ArrayList<>(), "aaa"));
    }

    // ---- 7. stamp ----

    private static RelayCore.Msg msg(String cmid, String sender) {
        RelayCore.Msg m = new RelayCore.Msg();
        m.clientMsgId = cmid;
        m.sender = sender;
        return m;
    }

    private static void stamp() {
        RelayCore.Msg m = msg("cmid-1", "sender-pub");
        RelayIngest.Decoded text = new RelayIngest.Decoded(
                true, "hi", true, new byte[0], 0, null, null);
        JSONObject e = new JSONObject();
        RelayIngest.stampEntry(e, m, text);
        check("stamp writes cmid", "cmid-1".equals(e.optString("cmid", "")));
        check("stamp writes sender", "sender-pub".equals(e.optString("sender", "")));
        check("stamp writes lowData for marked text",
                e.optBoolean("lowData", false));
        check("stamp writes no blob for text", e.optString("blob", "").isEmpty());

        RelayIngest.Decoded plain = new RelayIngest.Decoded(
                true, "hi", false, new byte[0], 0, null, null);
        JSONObject e2 = new JSONObject();
        RelayIngest.stampEntry(e2, m, plain);
        check("stamp omits lowData for ordinary text",
                !e2.has("lowData"));

        byte[] burst = new byte[] {0x0A, 0x0B, 0x0C};
        RelayIngest.Decoded voice = new RelayIngest.Decoded(
                false, "", false, burst, 6, null, null);
        JSONObject e3 = new JSONObject();
        RelayIngest.stampEntry(e3, m, voice);
        check("stamp writes blob for voice",
                Arrays.equals(RelayKeys.b64StdDecode(e3.optString("blob", "")), burst));
        check("stamp writes no lowData for voice", !e3.has("lowData"));

        // Null-safety: none of these may throw.
        try {
            RelayIngest.stampEntry(null, m, text);
            RelayIngest.stampEntry(new JSONObject(), null, text);
            RelayIngest.stampEntry(new JSONObject(), m, null);
            RelayIngest.stampEntry(null, null, null);
            check("stamp null-safe", true);
        } catch (Exception ex) {
            check("stamp null-safe", false);
        }
    }
}
