package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Off-device verification for the v1.11 walkie low-data mode,
 * exercising the exact shipping pure-Java code (VoiceProfile,
 * VoskResult, LowDataEnvelope, SpeakGate):
 *
 * Legs:
 *   1. VOICE PROFILE: defaults, pitch/rate clamping (incl. NaN),
 *      JSON round-trips, tolerant parse of malformed / partial /
 *      wrong-typed / hostile input, unknown-voice fallback
 *      resolution, string escaping round-trips.
 *   2. VOSK RESULT: final-result parse (text + word confidences +
 *      mean), empty text, partial extraction, malformed / null /
 *      text-less JSON, alternatives shape, conf scoping (numbers
 *      outside the result array ignored).
 *   3. ENVELOPE: build/parse round-trips incl. escaping (quotes,
 *      backslash, newline, unicode, control chars), old-envelope
 *      tolerance (no "ld"), malformed / non-text / burst-bytes
 *      rejection, ld flag strictness (only the literal true arms
 *      it), and OLD-CLIENT readability — the built envelope parsed
 *      with a real org.json jar the way a pre-v1.11 build would.
 *   4. SPEAK GATE: the full truth table — QUIET silences every
 *      path including manual replays; auto-read needs only the
 *      switch (v1.17: every received text speaks, low-data or
 *      typed); manual bypasses the switch only.
 *
 * What this harness cannot prove: Vosk native loading on a real
 * phone, transcription accuracy on Adam's voice, the model
 * download, TTS voice availability, or a two-phone low-data
 * exchange — all of that is Adam's on-device test. These legs
 * bound the pure-Java risk; they do not eliminate the rest.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar -d harness/classes \
 *     harness/LowDataHarness.java
 *   java -cp harness/classes:harness/org-json.jar \
 *     app.control.launcher.LowDataHarness
 */
public class LowDataHarness {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void eq(Object a, Object b, String name) {
        check(a == null ? b == null : a.equals(b),
                name + " (got " + a + ", want " + b + ")");
    }

    private static void feq(float a, float b, String name) {
        check(Math.abs(a - b) < 0.001f, name + " (got " + a + ", want " + b + ")");
    }

    public static void main(String[] args) {
        profileLeg();
        voskResultLeg();
        envelopeLeg();
        gateLeg();
        System.out.println("LowDataHarness: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ---- 1. voice profile ----

    private static void profileLeg() {
        VoiceProfile d = VoiceProfile.defaults();
        eq(d.voiceName, "", "default voice name empty");
        feq(d.pitch, 1.0f, "default pitch");
        feq(d.rate, 1.0f, "default rate");

        // clamping
        feq(new VoiceProfile("", 0.1f, 5.0f).pitch, 0.5f, "pitch clamped low");
        feq(new VoiceProfile("", 0.1f, 5.0f).rate, 2.0f, "rate clamped high");
        feq(new VoiceProfile("", 3.0f, -1.0f).pitch, 2.0f, "pitch clamped high");
        feq(new VoiceProfile("", 3.0f, -1.0f).rate, 0.5f, "rate clamped low");
        feq(new VoiceProfile("", Float.NaN, Float.POSITIVE_INFINITY).pitch, 1.0f, "NaN pitch -> default");
        feq(new VoiceProfile("", Float.NaN, Float.POSITIVE_INFINITY).rate, 1.0f, "Inf rate -> default");
        eq(new VoiceProfile(null, 1f, 1f).voiceName, "", "null voice name -> empty");

        // round-trips
        VoiceProfile p1 = new VoiceProfile("en-US-language#male", 1.25f, 0.9f);
        eq(VoiceProfile.parse(p1.toJson()), p1, "round-trip simple");
        VoiceProfile p2 = new VoiceProfile("we\"ird\\na\tme\u00e9", 0.5f, 2.0f);
        eq(VoiceProfile.parse(p2.toJson()), p2, "round-trip escaped name");
        VoiceProfile p3 = new VoiceProfile("", 1.0f, 1.0f);
        eq(VoiceProfile.parse(p3.toJson()), p3, "round-trip default");

        // tolerant parse
        eq(VoiceProfile.parse(null), VoiceProfile.defaults(), "parse null");
        eq(VoiceProfile.parse(""), VoiceProfile.defaults(), "parse empty");
        eq(VoiceProfile.parse("not json"), VoiceProfile.defaults(), "parse garbage");
        eq(VoiceProfile.parse("{}"), VoiceProfile.defaults(), "parse empty object");
        eq(VoiceProfile.parse("{\"voice\":\"v\"}"), new VoiceProfile("v", 1f, 1f), "parse voice only");
        eq(VoiceProfile.parse("{\"pitch\":1.5}"), new VoiceProfile("", 1.5f, 1f), "parse pitch only");
        eq(VoiceProfile.parse("{\"rate\":0.75,\"extra\":true}"), new VoiceProfile("", 1f, 0.75f), "parse unknown ignored");
        eq(VoiceProfile.parse("{\"voice\":42,\"pitch\":\"x\",\"rate\":null}"), VoiceProfile.defaults(), "parse wrong types");
        eq(VoiceProfile.parse("{\"voice\":\"v\",\"pitch\":99,\"rate\":-9}"), new VoiceProfile("v", 2f, 0.5f), "parse out-of-range clamped");
        eq(VoiceProfile.parse("{\"voice\":\"unclosed}"), VoiceProfile.defaults(), "parse unclosed string");
        eq(VoiceProfile.parse("{\"voice\":\"a\\\"b\"}"), new VoiceProfile("a\"b", 1f, 1f), "parse escaped quote");
        eq(VoiceProfile.parse("{\"voice\":\"a\\u0041b\"}"), new VoiceProfile("aAb", 1f, 1f), "parse unicode escape");
        eq(VoiceProfile.parse("  { \"voice\" : \"v\" , \"pitch\" : 1.2 , \"rate\" : 0.8 }  "), new VoiceProfile("v", 1.2f, 0.8f), "parse whitespace");
        eq(VoiceProfile.parse("{\"pitch\":1.5e0,\"rate\":.5}"), new VoiceProfile("", 1.5f, 0.5f), "parse exponent/dot numbers");
        // a hostile payload must not throw or smuggle values
        eq(VoiceProfile.parse("{\"voice\":\"x\",\"pitch\":1.0} garbage"), VoiceProfile.defaults(), "parse trailing garbage");
        eq(VoiceProfile.parse("[{\"voice\":\"x\"}]"), VoiceProfile.defaults(), "parse array rejected");

        // resolve / fallback
        List<String> avail = Arrays.asList("en-US-a", "en-GB-b");
        eq(new VoiceProfile("en-US-a", 1f, 1f).resolve(avail), "en-US-a", "resolve installed");
        eq(new VoiceProfile("gone-voice", 1f, 1f).resolve(avail), "", "resolve missing -> default");
        eq(new VoiceProfile("", 1f, 1f).resolve(avail), "", "resolve empty stays empty");
        eq(new VoiceProfile("any", 1f, 1f).resolve(null), "any", "resolve unknown list keeps name");
        eq(new VoiceProfile("any", 1f, 1f).resolve(new ArrayList<String>()), "any", "resolve empty list keeps name");
        eq(new VoiceProfile("EN-US-A", 1f, 1f).resolve(avail), "", "resolve is case-sensitive");
    }

    // ---- 2. vosk result ----

    private static void voskResultLeg() {
        VoskResult ok = VoskResult.summarize(
                "{\"text\": \"hello world\", \"result\": ["
                + "{\"conf\": 1.0, \"end\": 1.1, \"start\": 0.9, \"word\": \"hello\"}, "
                + "{\"conf\": 0.5, \"end\": 1.5, \"start\": 1.1, \"word\": \"world\"}]}");
        eq(ok.status, VoskResult.Status.OK, "final OK status");
        eq(ok.text, "hello world", "final text");
        feq(ok.meanConf, 0.75f, "mean confidence");
        check(ok.hasWords, "final hasWords");

        VoskResult noWords = VoskResult.summarize("{\"text\": \"hi\"}");
        eq(noWords.status, VoskResult.Status.OK, "no-words OK");
        eq(noWords.text, "hi", "no-words text");
        feq(noWords.meanConf, 0f, "no-words conf 0");
        check(!noWords.hasWords, "no-words hasWords false");

        VoskResult trimmed = VoskResult.summarize("{\"text\": \"  padded  \"}");
        eq(trimmed.text, "padded", "text trimmed");

        eq(VoskResult.summarize("{\"text\": \"\"}").status, VoskResult.Status.EMPTY, "empty text EMPTY");
        eq(VoskResult.summarize("{\"text\": \"   \"}").status, VoskResult.Status.EMPTY, "blank text EMPTY");
        eq(VoskResult.summarize("{\"text\": \"\"}").text, "", "empty text empty");
        eq(VoskResult.summarize(null).status, VoskResult.Status.FAILED, "null FAILED");
        eq(VoskResult.summarize("").status, VoskResult.Status.FAILED, "empty FAILED");
        eq(VoskResult.summarize("garbage").status, VoskResult.Status.FAILED, "garbage FAILED");
        eq(VoskResult.summarize("{\"partial\": \"hel\"}").status, VoskResult.Status.FAILED, "partial-only FAILED as final");
        eq(VoskResult.summarize("{\"result\": []}").status, VoskResult.Status.FAILED, "no text field FAILED");
        eq(VoskResult.summarize("{\"text\": 42}").status, VoskResult.Status.FAILED, "numeric text FAILED");
        eq(VoskResult.summarize("{\"text\": \"x\"").status, VoskResult.Status.FAILED, "unclosed FAILED");
        eq(VoskResult.summarize("[]").status, VoskResult.Status.FAILED, "array FAILED");

        // alternatives shape: top-level text still wins
        VoskResult alt = VoskResult.summarize(
                "{\"alternatives\": [{\"text\": \"a\", \"confidence\": 0.9}], \"text\": \"a\"}");
        eq(alt.status, VoskResult.Status.OK, "alternatives OK");
        eq(alt.text, "a", "alternatives text");

        // conf scoping: a "conf" outside the result array must not count
        VoskResult scoped = VoskResult.summarize(
                "{\"conf\": 0.01, \"text\": \"x\", \"result\": [{\"conf\": 0.9, \"word\": \"x\"}]}");
        feq(scoped.meanConf, 0.9f, "conf scoped to result array");
        VoskResult noArray = VoskResult.summarize("{\"conf\": 0.01, \"text\": \"x\"}");
        feq(noArray.meanConf, 0f, "no array no conf");

        // partials are display-only extraction
        eq(VoskResult.partialText("{\"partial\": \"hel\"}"), "hel", "partial extracted");
        eq(VoskResult.partialText("{\"partial\": \"\"}"), "", "partial empty");
        eq(VoskResult.partialText("{\"text\": \"x\"}"), "", "partial absent");
        eq(VoskResult.partialText(null), "", "partial null");
        eq(VoskResult.partialText("garbage"), "", "partial garbage");
        eq(VoskResult.partialText("{\"partial\": \"a\\\"b\"}"), "a\"b", "partial escaped");
    }

    // ---- 3. envelope ----

    private static void envelopeLeg() {
        // v1.32: every text codes on the "w" key, no marker.
        String helloWire = WordDict.encode("hello");
        eq(LowDataEnvelope.build("hello", false), "{\"t\":\"text\",\"w\":\"" + helloWire + "\"}", "ordinary envelope shape");
        eq(LowDataEnvelope.build("hello", true), "{\"t\":\"text\",\"w\":\"" + helloWire + "\",\"ld\":true}", "low-data envelope shape");
        eq(LowDataEnvelope.build("", false), "{\"t\":\"text\",\"w\":\"\"}", "empty text envelope");
        eq(LowDataEnvelope.build(null, false), "{\"t\":\"text\",\"w\":\"\"}", "null text envelope");

        // round-trips incl. escaping. The texts start
        // capitalized (or with a non-word) so the codec's
        // sentence-start normalization cannot change them:
        // this leg proves the envelope, not the codec.
        String[] texts = {
            "Plain", "With \"quotes\"", "Back\\slash", "New\nline", "Tab\there",
            "unicode \u00e9\u4e2d", "emoji \uD83D\uDE00", "ctrl\u0001char",
            "{\"t\":\"text\"}", "ld\":true, \"x\":\"", "Trailing\\", ""
        };
        for (String t : texts) {
            LowDataEnvelope.Parsed p = LowDataEnvelope.parse(LowDataEnvelope.build(t, true));
            check(p != null && t.equals(p.text) && p.lowData, "round-trip ld: " + escapeForLog(t));
            LowDataEnvelope.Parsed p2 = LowDataEnvelope.parse(LowDataEnvelope.build(t, false));
            check(p2 != null && t.equals(p2.text) && !p2.lowData, "round-trip plain: " + escapeForLog(t));
        }
        // A lowercase sentence start normalizes through the
        // coded path (the codec is live in the envelope).
        LowDataEnvelope.Parsed norm = LowDataEnvelope.parse(
                LowDataEnvelope.build("hello there friend", false));
        check(norm != null && "Hello there friend".equals(norm.text), "lowercase start normalizes");

        // old-envelope tolerance
        LowDataEnvelope.Parsed old = LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"hi\"}");
        check(old != null && "hi".equals(old.text) && !old.lowData, "old envelope tolerated");
        LowDataEnvelope.Parsed spaced = LowDataEnvelope.parse("{ \"t\" : \"text\" , \"text\" : \"hi\" , \"ld\" : true }");
        check(spaced != null && spaced.lowData, "spaced envelope parsed");
        // a spaced NEW-shape envelope parses too
        LowDataEnvelope.Parsed spacedW = LowDataEnvelope.parse(
                "{ \"t\" : \"text\" , \"w\" : \"" + WordDict.encode("Hello there") + "\" , \"ld\" : true }");
        check(spacedW != null && "Hello there".equals(spacedW.text) && spacedW.lowData, "spaced w envelope parsed");

        // rejection: bursts and malformed payloads are not envelopes
        check(LowDataEnvelope.parse(null) == null, "null rejected");
        check(LowDataEnvelope.parse("") == null, "empty rejected");
        check(LowDataEnvelope.parse("garbage bytes \u0001\u0002") == null, "burst bytes rejected");
        check(LowDataEnvelope.parse("{\"t\":\"burst\"}") == null, "other t rejected");
        check(LowDataEnvelope.parse("{\"t\":\"text\"}") == null, "missing payload rejected");
        check(LowDataEnvelope.parse("{\"t\":\"text\",\"text\":42}") == null, "numeric text rejected");
        check(LowDataEnvelope.parse("{\"t\":\"text\",\"w\":42}") == null, "numeric w rejected");
        check(LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\"") == null, "unclosed rejected");
        check(LowDataEnvelope.parse("[{\"t\":\"text\",\"text\":\"x\"}]") == null, "array rejected");
        check(LowDataEnvelope.parse("{\"t\":\"Text\",\"text\":\"x\"}") == null, "t case-sensitive");

        // ld strictness: only the JSON literal true arms speech
        check(LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":true}").lowData, "ld true arms");
        check(!LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":false}").lowData, "ld false disarms");
        check(!LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":\"true\"}").lowData, "ld string disarms");
        check(!LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":1}").lowData, "ld number disarms");
        check(!LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":null}").lowData, "ld null disarms");
        check(!LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":trueish}").lowData, "ld trueish disarms");
        check(LowDataEnvelope.parse("{\"t\":\"text\",\"text\":\"x\",\"ld\":true,\"ld\":false}").lowData, "first ld wins");

        // OLD-CLIENT behavior (v1.32 breaking change, stated
        // honestly): a pre-v1.32 build parses with org.json
        // and reads only t/text — the new shape carries no
        // "text" key, so the old build sees an empty text
        // (the relay path's silent miss). The new code still
        // parses old-built envelopes.
        try {
            for (boolean ld : new boolean[]{false, true}) {
                String env = LowDataEnvelope.build("hello \"world\"", ld);
                org.json.JSONObject o = new org.json.JSONObject(env);
                eq(o.optString("t", ""), "text", "old client t (ld=" + ld + ")");
                eq(o.optString("text", ""), "", "old client sees no text (ld=" + ld + ")");
                check(o.has("w") && !o.optString("w", "").isEmpty(), "new shape carries w (ld=" + ld + ")");
            }
            // and an old-built envelope still parses in the new code
            org.json.JSONObject oldBuilt = new org.json.JSONObject();
            oldBuilt.put("t", "text");
            oldBuilt.put("text", "from an old build");
            LowDataEnvelope.Parsed p = LowDataEnvelope.parse(oldBuilt.toString());
            check(p != null && "from an old build".equals(p.text) && !p.lowData, "old-built envelope parsed");
        } catch (Exception e) {
            check(false, "old-client leg threw: " + e);
        }
    }

    private static String escapeForLog(String t) {
        return t.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ---- 4. speak gate (v1.17: every received text speaks; the
    // low-data marker no longer gates speech) ----

    private static void gateLeg() {
        // auto path: switch on speaks, switch off mutes — for any
        // received text, low-data or typed
        eq(SpeakGate.decide(true, false, false), SpeakGate.Decision.SPEAK, "auto: speak");
        eq(SpeakGate.decide(false, false, false), SpeakGate.Decision.SKIP_MUTED, "auto: muted");
        // QUIET silences everything, including manual replays
        for (boolean auto : new boolean[]{true, false}) {
            for (boolean manual : new boolean[]{true, false}) {
                eq(SpeakGate.decide(auto, true, manual), SpeakGate.Decision.SKIP_QUIET,
                        "quiet silences auto=" + auto + " manual=" + manual);
            }
        }
        // manual bypasses the switch, never QUIET (covered above)
        eq(SpeakGate.decide(false, false, true), SpeakGate.Decision.SPEAK, "manual bypasses mute");
        eq(SpeakGate.decide(true, false, true), SpeakGate.Decision.SPEAK, "manual speaks");
    }
}
