package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * Off-device verification for the v1.8 audio modem, exercising the
 * exact shipping pure-Java code (AudioModem + AudioKeyPayload):
 *
 * Legs:
 *   1. ROUND-TRIP: contact / channel / chat payloads encode to PCM
 *      and decode bit-exact through the streaming decoder fed in
 *      320-sample chunks (the way AudioRecord delivers audio).
 *   2. JSON: the reconstructed text matches the legacy QR shapes
 *      byte for byte, including the escaper on hostile names.
 *   3. NOISE: decode under additive white noise at 20/10/5/0 dB SNR.
 *   4. LEVEL: playback at 0.05x / 0.2x / 1.0x amplitude.
 *   5. CLOCK: sample-clock offset of +/-0.5% and +/-1% via resampling.
 *   6. ECHO: a 30 ms delayed copy at 0.3 gain mixed in.
 *   7. GARBAGE: noise before/after the frame; pure noise and
 *      silence produce no frame and no crash.
 *   8. CORRUPTION: one symbol's tones replaced -> CRC error reported
 *      and NO payload delivered.
 *   9. REPLAY: two frames back to back with a silence gap both decode.
 *  10. LIMITS: empty/oversize payloads rejected; over-long names
 *      truncate at a character boundary; malformed binaries rejected.
 *  11. TIMING: measured air time per payload kind, printed (the UI
 *      copy states these numbers — they must come from here).
 *  12. TYPE TONES (v1.26): the initial tones announce the kind
 *      before the payload finishes — onTypeHeard fires exactly
 *      once per frame, before onFrame, with the right kind; the
 *      frame's type matches payload[0]; a corrupted type symbol
 *      is refused as a CRC error, never misrouted.
 *  13. MESH PAYLOAD (v1.26): kind 4 encodes/decodes both roads,
 *      rebuilds the join JSON shape, and rejects bad roads,
 *      empty addresses, and empty channel ids; the kind registry
 *      admits exactly kinds 1-4.
 *  14. BAND (v1.26): 48 kHz, tones 14-17.5 kHz on a 500 Hz grid,
 *      32 ms symbols — the high band Adam asked for, with the
 *      impairment gates above proving it reliable.
 *
 * What this harness cannot prove: real phone speakers and microphones
 * (frequency response, AGC pumping, room echo beyond the simulated
 * tap), or two real phones in a real room — Adam's two phones are
 * the first acoustic test. The simulated legs bound the risk; they
 * do not eliminate it. The high band adds one more unknown the
 * harness cannot see: whether a phone's speaker and microphone
 * reproduce 14-17.5 kHz cleanly, and whether OEM preprocessing
 * filters those frequencies — the on-device checklist covers it.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac --release 17 -sourcepath src -d harness/classes \
 *     harness/AudioModemHarness.java
 *   java -cp harness/classes app.control.launcher.AudioModemHarness
 */
public class AudioModemHarness {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        } else {
            System.out.println("ok:   " + name);
        }
    }

    private static final class Sink implements AudioModem.Decoder.Listener {
        final List<byte[]> frames = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
        final List<Integer> heardTypes = new ArrayList<>();
        int crcErrors = 0;

        @Override public void onFrame(int type, byte[] payload) {
            types.add(type);
            frames.add(payload);
        }

        @Override public void onTypeHeard(int type) {
            heardTypes.add(type);
        }

        @Override public void onCrcError() {
            crcErrors++;
        }
    }

    /** Feeds pcm through a fresh decoder in small chunks. */
    private static Sink run(short[] pcm, int chunk) {
        Sink sink = new Sink();
        AudioModem.Decoder dec = new AudioModem.Decoder(sink);
        for (int i = 0; i < pcm.length; i += chunk) {
            int n = Math.min(chunk, pcm.length - i);
            short[] part = Arrays.copyOfRange(pcm, i, i + n);
            dec.feed(part, 0, n);
        }
        return sink;
    }

    private static byte[] keyBytes(int seed) {
        byte[] k = new byte[32];
        new Random(seed).nextBytes(k);
        return k;
    }

    private static String b64Std(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String b64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static short[] withNoise(short[] pcm, double snrDb, long seed) {
        double sig = 0;
        for (short s : pcm) sig += (double) s * s;
        sig /= pcm.length;
        double noisePow = sig / Math.pow(10.0, snrDb / 10.0);
        double sd = Math.sqrt(noisePow);
        Random r = new Random(seed);
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            double v = pcm[i] + r.nextGaussian() * sd;
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return out;
    }

    private static short[] scaled(short[] pcm, double f) {
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = (short) Math.round(pcm[i] * f);
        }
        return out;
    }

    /** Resamples by factor s: out[i] = in[i*s] (linear interp).
     *  s > 1 stretches (receiver clock slower than sender). */
    private static short[] resampled(short[] pcm, double s) {
        int n = (int) (pcm.length / s);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double pos = i * s;
            int j = (int) pos;
            double frac = pos - j;
            double a = pcm[Math.min(j, pcm.length - 1)];
            double b = pcm[Math.min(j + 1, pcm.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * frac);
        }
        return out;
    }

    private static short[] withEcho(short[] pcm, int delay, double gain) {
        short[] out = Arrays.copyOf(pcm, pcm.length);
        for (int i = delay; i < pcm.length; i++) {
            double v = out[i] + gain * pcm[i - delay];
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return out;
    }

    private static short[] noiseBurst(int samples, double rms, long seed) {
        Random r = new Random(seed);
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (short) Math.round(r.nextGaussian() * rms);
        }
        return out;
    }

    private static short[] concat(short[]... parts) {
        int n = 0;
        for (short[] p : parts) n += p.length;
        short[] out = new short[n];
        int pos = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    /** Replaces one data symbol's tones with a different nibble's
     *  (the nibble actually there, flipped — never a no-op). */
    private static short[] corruptSymbol(short[] pcm, int symbolIndex) {
        short[] out = Arrays.copyOf(pcm, pcm.length);
        int off = AudioModem.LEAD_SILENCE + symbolIndex * AudioModem.SYMBOL_SAMPLES;
        int current = AudioModem.classify(out, off + 64);
        int newNibble = (current < 0 ? 0 : current) ^ 0x5;
        int fl = AudioModem.LOW_TONES[(newNibble >> 2) & 3];
        int fh = AudioModem.HIGH_TONES[newNibble & 3];
        for (int i = 0; i < AudioModem.SYMBOL_SAMPLES; i++) {
            double v = 0.5 * (Math.sin(2 * Math.PI * fl * i / AudioModem.SAMPLE_RATE)
                    + Math.sin(2 * Math.PI * fh * i / AudioModem.SAMPLE_RATE));
            out[off + i] = (short) Math.round(v * 32767.0);
        }
        return out;
    }

    public static void main(String[] args) {
        byte[] pub = keyBytes(11);
        byte[] dm = keyBytes(22);
        byte[] chanKey = keyBytes(33);

        // ---- payload construction ----
        byte[] contact = AudioKeyPayload.contact("Ada Lovelace", b64Url(pub), b64Std(dm));
        byte[] invite = AudioKeyPayload.channel(7, true, "Worksite", b64Std(chanKey));
        byte[] openInvite = AudioKeyPayload.channel(3, false, "", null);
        byte[] chat = AudioKeyPayload.chat("Trail crew", b64Std(chanKey));
        check(contact != null && contact.length == 1 + 1 + 12 + 64, "contact payload size");
        check(invite != null && invite.length == 1 + 1 + 1 + 1 + 8 + 32, "invite payload size");
        check(openInvite != null && openInvite.length == 4, "open invite carries no key");
        check(chat != null && chat.length == 1 + 1 + 10 + 32, "chat payload size");

        // ---- 1. round-trip through the modem ----
        for (byte[] p : new byte[][]{contact, invite, openInvite, chat}) {
            short[] pcm = AudioModem.encode(p);
            Sink s = run(pcm, 320);
            check(s.frames.size() == 1 && Arrays.equals(s.frames.get(0), p)
                    && s.crcErrors == 0, "round-trip " + p.length + "B payload");
        }

        // ---- 2. JSON reconstruction ----
        AudioKeyPayload.Decoded dc = AudioKeyPayload.decode(contact);
        String contactJson = AudioKeyPayload.toJsonText(dc);
        String expectContact = "{\"app\":\"control-contact\",\"v\":2,\"name\":\"Ada Lovelace\""
                + ",\"pub\":\"" + b64Url(pub) + "\",\"dm\":\"" + b64Std(dm) + "\"}";
        check(contactJson.equals(expectContact), "contact JSON matches legacy shape");
        AudioKeyPayload.Decoded di = AudioKeyPayload.decode(invite);
        String inviteJson = AudioKeyPayload.toJsonText(di);
        String expectInvite = "{\"app\":\"control-channel\",\"v\":1,\"number\":7"
                + ",\"label\":\"Worksite\",\"mode\":\"private\",\"key\":\""
                + b64Std(chanKey) + "\"}";
        check(inviteJson.equals(expectInvite), "invite JSON matches legacy shape");
        AudioKeyPayload.Decoded dh = AudioKeyPayload.decode(chat);
        check(AudioKeyPayload.toJsonText(dh).equals(
                "{\"app\":\"control-chat\",\"v\":1,\"name\":\"Trail crew\",\"key\":\""
                        + b64Std(chanKey) + "\"}"), "chat JSON matches legacy shape");
        // Hostile name: quotes, backslash, control chars.
        byte[] evil = AudioKeyPayload.contact("A\"B\\C\nD\tEF", b64Url(pub), b64Std(dm));
        AudioKeyPayload.Decoded de = AudioKeyPayload.decode(evil);
        String evilJson = AudioKeyPayload.toJsonText(de);
        check(evilJson.contains("A\\\"B\\\\C\\nD\\tE\\u0001F"), "escaper handles hostile names");
        check(AudioKeyPayload.decode(evil) != null
                && AudioKeyPayload.decode(evil).name.equals("A\"B\\C\nD\tEF"),
                "hostile name survives binary round-trip");

        // ---- 3. noise ----
        short[] contactPcm = AudioModem.encode(contact);
        for (double snr : new double[]{20, 10, 5, 0}) {
            Sink s = run(withNoise(contactPcm, snr, 42), 320);
            check(s.frames.size() == 1 && Arrays.equals(s.frames.get(0), contact),
                    "decode at " + (int) snr + " dB SNR");
        }

        // ---- 4. level ----
        for (double f : new double[]{1.0, 0.2, 0.05}) {
            Sink s = run(scaled(contactPcm, f), 320);
            check(s.frames.size() == 1 && Arrays.equals(s.frames.get(0), contact),
                    "decode at " + f + "x amplitude");
        }

        // ---- 5. clock offset ----
        for (double s : new double[]{0.99, 0.995, 1.005, 1.01}) {
            Sink sink = run(resampled(contactPcm, s), 320);
            check(sink.frames.size() == 1 && Arrays.equals(sink.frames.get(0), contact),
                    "decode at " + ((s - 1) * 100) + "% clock offset");
        }

        // ---- 6. echo ----
        int echo30 = (int) (0.030 * AudioModem.SAMPLE_RATE);
        int echo80 = (int) (0.080 * AudioModem.SAMPLE_RATE);
        Sink echo = run(withEcho(contactPcm, echo30, 0.3), 320);
        check(echo.frames.size() == 1 && Arrays.equals(echo.frames.get(0), contact),
                "decode with 30ms echo at 0.3 gain");

        // ---- 7. garbage / silence / noise-only ----
        short[] padded = concat(noiseBurst(8000, 300, 7), contactPcm, noiseBurst(8000, 300, 8));
        Sink p = run(padded, 320);
        check(p.frames.size() == 1 && Arrays.equals(p.frames.get(0), contact),
                "decode with noise before and after");
        Sink silence = run(new short[32000], 320);
        check(silence.frames.isEmpty() && silence.crcErrors == 0, "silence yields nothing");
        Sink noiseOnly = run(noiseBurst(48000, 2000, 9), 320);
        check(noiseOnly.frames.isEmpty() && noiseOnly.crcErrors == 0,
                "loud noise yields no false frame");

        // ---- 8. corruption -> CRC error, no payload ----
        // Layout: preamble 0-7, sync 8-9, type 10-11, length 12-13,
        // payload from 14. Corrupting 14 flips the first payload
        // nibble (the kind byte), so the CRC must refuse it.
        short[] bad = corruptSymbol(contactPcm, 14);
        Sink badSink = run(bad, 320);
        check(badSink.frames.isEmpty() && badSink.crcErrors == 1,
                "corrupted symbol -> CRC error, no payload delivered");

        // ---- 9. replay: two frames, one listen ----
        short[] two = concat(contactPcm, new short[8000], AudioModem.encode(invite));
        Sink twoSink = run(two, 320);
        check(twoSink.frames.size() == 2
                        && Arrays.equals(twoSink.frames.get(0), contact)
                        && Arrays.equals(twoSink.frames.get(1), invite),
                "two back-to-back frames both decode");

        // ---- 10. limits ----
        check(AudioModem.encode(null) == null, "null payload rejected");
        check(AudioModem.encode(new byte[0]) == null, "empty payload rejected");
        check(AudioModem.encode(new byte[201]) == null, "oversize payload rejected");
        String longName = "A".repeat(200);
        byte[] longContact = AudioKeyPayload.contact(longName, b64Url(pub), b64Std(dm));
        AudioKeyPayload.Decoded dl = AudioKeyPayload.decode(longContact);
        check(dl != null && dl.name.length() == 64, "over-long name truncates to 64 bytes");
        String multi = "\uD83D\uDE00".repeat(40); // 4-byte chars
        byte[] multiContact = AudioKeyPayload.contact(multi, b64Url(pub), b64Std(dm));
        AudioKeyPayload.Decoded dm2 = AudioKeyPayload.decode(multiContact);
        check(dm2 != null && dm2.name.codePointCount(0, dm2.name.length()) == 16,
                "truncation respects character boundaries");
        check(AudioKeyPayload.channel(0, true, "", b64Std(chanKey)) == null,
                "channel 0 rejected");
        check(AudioKeyPayload.channel(7, true, "", "not-a-key") == null,
                "malformed private key rejected");
        check(AudioKeyPayload.contact("X", "short", b64Std(dm)) == null,
                "malformed pubkey rejected");
        check(AudioKeyPayload.decode(new byte[]{9, 1, 2, 3}) == null,
                "unknown kind rejected");
        check(AudioKeyPayload.decode(new byte[]{1, 5, 'A'}) == null,
                "truncated payload rejected");
        check(AudioKeyPayload.toJsonText(null).isEmpty(), "null decoded -> empty JSON");

        // ---- 10b. combined impairments + random payloads ----
        short[] hostile = withEcho(resampled(withNoise(contactPcm, 10, 77), 1.01), echo30, 0.3);
        Sink hs = run(hostile, 320);
        check(hs.frames.size() == 1 && Arrays.equals(hs.frames.get(0), contact),
                "combined: 10dB noise + 1% clock + echo");
        Sink echoStrong = run(withEcho(contactPcm, echo30, 0.5), 320);
        check(echoStrong.frames.size() == 1 && Arrays.equals(echoStrong.frames.get(0), contact),
                "decode with 30ms echo at 0.5 gain");
        Sink echoLong = run(withEcho(contactPcm, echo80, 0.2), 320);
        check(echoLong.frames.size() == 1 && Arrays.equals(echoLong.frames.get(0), contact),
                "decode with 80ms echo at 0.2 gain");
        Random rnd = new Random(1234);
        boolean randomOk = true;
        for (int t = 0; t < 8; t++) {
            String nm = "N" + t + " " + rnd.nextInt(100000);
            byte[] rp = AudioKeyPayload.contact(nm, b64Url(keyBytes(100 + t)),
                    b64Std(keyBytes(200 + t)));
            Sink s = run(AudioModem.encode(rp), 320);
            if (s.frames.size() != 1 || !Arrays.equals(s.frames.get(0), rp)) randomOk = false;
        }
        check(randomOk, "8 random contact payloads round-trip");
        Sink tiny = run(contactPcm, 64);
        check(tiny.frames.size() == 1 && Arrays.equals(tiny.frames.get(0), contact),
                "decode fed in 64-sample chunks");
        Sink huge = run(contactPcm, 8192);
        check(huge.frames.size() == 1 && Arrays.equals(huge.frames.get(0), contact),
                "decode fed in 8192-sample chunks");
        short[] badThenGood = concat(bad, new short[8000], contactPcm);
        Sink bg = run(badThenGood, 320);
        check(bg.frames.size() == 1 && Arrays.equals(bg.frames.get(0), contact)
                        && bg.crcErrors == 1,
                "CRC failure then replay in one listen: 1 error + 1 frame");

        // ---- 11. timing (reported, and asserted sane) ----
        int contactMs = AudioModem.airTimeMs(contact);
        int inviteMs = AudioModem.airTimeMs(invite);
        int chatMs = AudioModem.airTimeMs(chat);
        System.out.println("AIR TIME contact=" + contactMs + "ms invite=" + inviteMs
                + "ms chat=" + chatMs + "ms");
        check(contactMs > 4000 && contactMs < 8000, "contact air time in range");
        check(inviteMs > 3000 && inviteMs < 7000, "invite air time in range");

        // ---- 12. type tones (v1.26) ----
        byte[] meshP2p = AudioKeyPayload.mesh(AudioKeyPayload.ROAD_P2P,
                "Ada's Phone", "AA:BB:CC:DD:EE:FF", "open:7", "Channel 7", false);
        check(meshP2p != null, "mesh p2p payload builds");
        byte[][] typed = new byte[][]{contact, invite, chat, meshP2p};
        int[] kinds = new int[]{AudioKeyPayload.KIND_CONTACT,
                AudioKeyPayload.KIND_CHANNEL, AudioKeyPayload.KIND_CHAT,
                AudioKeyPayload.KIND_MESH};
        boolean typesOk = true;
        for (int i = 0; i < typed.length; i++) {
            Sink s = run(AudioModem.encode(typed[i]), 320);
            if (s.frames.size() != 1 || s.heardTypes.size() != 1
                    || s.heardTypes.get(0) != kinds[i]
                    || s.types.size() != 1 || s.types.get(0) != kinds[i]
                    || (s.frames.get(0)[0] & 0xFF) != kinds[i]) typesOk = false;
        }
        check(typesOk, "type tones announce each kind once, matching payload[0]");
        // The announcement precedes the delivery.
        List<String> events = new ArrayList<>();
        AudioModem.Decoder orderDec = new AudioModem.Decoder(
                new AudioModem.Decoder.Listener() {
                    @Override public void onFrame(int type, byte[] payload) {
                        events.add("F");
                    }
                    @Override public void onTypeHeard(int type) {
                        events.add("T");
                    }
                    @Override public void onCrcError() {
                        events.add("E");
                    }
                });
        short[] orderPcm = AudioModem.encode(chat);
        for (int i = 0; i < orderPcm.length; i += 320) {
            int n = Math.min(320, orderPcm.length - i);
            orderDec.feed(Arrays.copyOfRange(orderPcm, i, i + n), 0, n);
        }
        check(events.size() == 2 && "T".equals(events.get(0))
                && "F".equals(events.get(1)), "type announced before frame delivered");
        // A corrupted type symbol (index 10) disagrees with the
        // CRC-protected kind byte: refused, never misrouted.
        short[] badType = corruptSymbol(contactPcm, 10);
        Sink bt = run(badType, 320);
        check(bt.frames.isEmpty() && bt.crcErrors == 1,
                "corrupted type tone -> CRC error, no misroute");
        short[] badType2 = corruptSymbol(contactPcm, 11);
        Sink bt2 = run(badType2, 320);
        check(bt2.frames.isEmpty() && bt2.crcErrors == 1,
                "corrupted second type tone -> CRC error, no misroute");

        // ---- 13. mesh payload (v1.26) ----
        Sink mp = run(AudioModem.encode(meshP2p), 320);
        check(mp.frames.size() == 1 && Arrays.equals(mp.frames.get(0), meshP2p),
                "mesh payload round-trips through the modem");
        AudioKeyPayload.Decoded dm3 = AudioKeyPayload.decode(meshP2p);
        check(dm3 != null && dm3.kind == AudioKeyPayload.KIND_MESH
                && dm3.road == AudioKeyPayload.ROAD_P2P
                && "Ada's Phone".equals(dm3.hostName)
                && "AA:BB:CC:DD:EE:FF".equals(dm3.address)
                && "open:7".equals(dm3.channelId)
                && "Channel 7".equals(dm3.channelName) && !dm3.keyed,
                "mesh p2p fields decode");
        check(AudioKeyPayload.toJsonText(dm3).equals(
                "{\"app\":\"control-mesh-join\",\"v\":1,\"road\":\"p2p\""
                        + ",\"host\":\"Ada's Phone\",\"address\":\"AA:BB:CC:DD:EE:FF\""
                        + ",\"channelId\":\"open:7\",\"channelName\":\"Channel 7\""
                        + ",\"keyed\":false}"), "mesh JSON matches join shape");
        byte[] meshLan = AudioKeyPayload.mesh(AudioKeyPayload.ROAD_LAN,
                "Bob", "192.168.1.5", "priv:3:deadbeef", "Worksite", true);
        AudioKeyPayload.Decoded dm4 = AudioKeyPayload.decode(meshLan);
        check(dm4 != null && dm4.road == AudioKeyPayload.ROAD_LAN
                && "192.168.1.5".equals(dm4.address)
                && "priv:3:deadbeef".equals(dm4.channelId) && dm4.keyed,
                "mesh LAN fields decode, keyed preserved");
        check(meshP2p.length <= AudioModem.MAX_PAYLOAD
                && meshLan.length <= AudioModem.MAX_PAYLOAD,
                "mesh payloads fit the modem cap");
        check(AudioKeyPayload.mesh(3, "H", "1.2.3.4", "open:1", "C", false) == null,
                "bad mesh road rejected");
        check(AudioKeyPayload.mesh(AudioKeyPayload.ROAD_LAN, "H", "",
                "open:1", "C", false) == null, "empty mesh address rejected");
        check(AudioKeyPayload.mesh(AudioKeyPayload.ROAD_LAN, "H", "1.2.3.4",
                "", "C", false) == null, "empty mesh channel id rejected");
        String longHost = "H".repeat(200);
        AudioKeyPayload.Decoded dm5 = AudioKeyPayload.decode(AudioKeyPayload.mesh(
                AudioKeyPayload.ROAD_LAN, longHost, "1.2.3.4", "open:1", "C", false));
        check(dm5 != null && dm5.hostName.length() == 32,
                "mesh host name truncates to 32 bytes");
        check(AudioKeyPayload.isKnownKind(1) && AudioKeyPayload.isKnownKind(2)
                && AudioKeyPayload.isKnownKind(3) && AudioKeyPayload.isKnownKind(4)
                && !AudioKeyPayload.isKnownKind(0) && !AudioKeyPayload.isKnownKind(5)
                && !AudioKeyPayload.isKnownKind(255),
                "kind registry admits exactly kinds 1-4");
        check("mesh invite".equals(AudioKeyPayload.kindName(4)),
                "mesh kind name");
        check(AudioKeyPayload.kindName(9).contains("doesn't know"),
                "unknown kind names itself unknown");
        int meshMs = AudioModem.airTimeMs(meshP2p);
        System.out.println("AIR TIME mesh=" + meshMs + "ms");
        check(meshMs > 2000 && meshMs < 6000, "mesh air time in range");

        // ---- 14. band facts (v1.26) ----
        check(AudioModem.SAMPLE_RATE == 48000, "sample rate is 48 kHz");
        boolean gridOk = AudioModem.LOW_TONES.length == 4
                && AudioModem.HIGH_TONES.length == 4
                && AudioModem.LOW_TONES[0] == 14000
                && AudioModem.HIGH_TONES[3] == 17500;
        for (int i = 1; i < 4; i++) {
            if (AudioModem.LOW_TONES[i] - AudioModem.LOW_TONES[i - 1] != 500) gridOk = false;
            if (AudioModem.HIGH_TONES[i] - AudioModem.HIGH_TONES[i - 1] != 500) gridOk = false;
        }
        check(gridOk, "tones 14-17.5 kHz on a 500 Hz grid");
        check(AudioModem.SYMBOL_SAMPLES * 1000 / AudioModem.SAMPLE_RATE == 32,
                "symbols are 32 ms");

        System.out.println("---");
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
