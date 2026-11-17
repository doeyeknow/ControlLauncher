package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONObject;

/**
 * Off-device verification for the word-dictionary codec
 * (WordDict, v1.28) and the GitHub-hosted dictionary
 * (v1.29: DictFile, WordTable, TeachBlock, TaughtStore)
 * and its framing on every road:
 *
 * Legs:
 *   1. DICTIONARY: 1200 entries, unique, within the 1240-code
 *      space, charset rules (letters + apostrophe only, caps
 *      only the "I" family), every index mapping to a distinct
 *      two-byte code and back.
 *   2. CODE_MATH: index arithmetic spot checks and the display
 *      form's exact superscript/subscript code points.
 *   3. ROUND_TRIP: a corpus of exact round-trips (mixed
 *      known/unknown, punctuation, numbers, caps,
 *      contractions, emoji, hyphenation, quotes, newlines,
 *      tilde runs, very long messages) — every message
 *      codes (v1.32: no savings gate) and decodes
 *      byte-identical except for the two documented
 *      normalizations.
 *   4. NORMALIZATION: the two documented cases where decode
 *      does not reproduce the input — sentence-start
 *      capitals and the "I" forms.
 *   5. SAVINGS: measured bytes before/after on sample
 *      messages; long prose still saves, and short
 *      messages now code too (the wire may grow — the
 *      v1.28 "wire never grows" guarantee is gone by
 *      Adam's choice in v1.32).
 *   6. VERSION: a newer dictionary version renders
 *      undecoded with the honest note; version 0 passes
 *      plain text through untouched.
 *   7. CORRUPT: truncated escapes, bad lengths, lone
 *      symbols, out-of-range codes, truncated wires — all
 *      render undecoded with the note, never throw.
 *   8. PRINTABILITY: coded payloads are valid UTF-8 with
 *      no control bytes introduced, so an old build sees
 *      odd-but-printable text, never garbage.
 *   9. ENVELOPE: the relay framing — LowDataEnvelope
 *      codes every text on the "w" key with no marker
 *      (v1.32), legacy "text" envelopes (marked and plain)
 *      still parse, and a newer version carries the note;
 *      BridgeRule.buildRelayedText codes on the "w" key
 *      while provenance still parses; RelayIngest decodes
 *      sealed coded envelopes and stamps the wire form.
 *  10. PACKET: the mesh/Bluetooth framing — MeshNet.putText
 *      and BtWalkie.putText code every text on the "w"
 *      key with no marker; decodeCoded round-trips the
 *      packet payload and legacy decodeMarked still
 *      decodes old packets.
 *  11. LONG_CODES: the four-byte self-delimiting codes —
 *      round-trips at every boundary, LEAD/DIGIT
 *      disjointness, printability, parseCodeString.
 *  12. DICTFILE: the packed blob — build determinism, CRLF
 *      and blank-line handling, corruption rejection at
 *      parse, build rejection of unshaped input.
 *  13. LEARNED: learned-append stability, the cap,
 *      serialize/load round-trips, and the GRADUATION rule
 *      (a word in both sections encodes to the base code;
 *      both codes decode).
 *  14. TEACH: two-table teach/learn round trips, the
 *      conflict-adoption rule (sender authoritative, old
 *      code stays decodable), taught gating, the
 *      per-message cap, teaching on short messages, and
 *      TeachBlock parsing.
 *  15. TAUGHT_STORE: scope validation, copy semantics,
 *      serialize/load, the scope cap.
 *  16. FULL_LIST: the actual shipped dictionary.txt
 *      (harness fixture, pinned by count + sha256) — the
 *      frozen head, full index/code round-trips, full
 *      word resolution, sampled message round-trips with
 *      tail words, and v1 payloads decoding identically
 *      under the full table.
 *  17. VERSIONS: v1 payload rules under the v2 codec, and
 *      the NOTE_NEWER / NOTE_UNTAUGHT distinction.
 *  18. SUBMIT: the v1.30 write-back rules (DictSubmit) —
 *      the submittable word shape, batching in
 *      first-learned order, the submitted-set dedupe, the
 *      hourly throttle, batch caps, and the proposal body.
 *  19. MERGE: the simulated merge — a learned word arrives
 *      in a fresh dictionary.txt, the table swaps through
 *      the real DictFile bytes, sends prefer the base code,
 *      the old learned code still decodes, unmerged learned
 *      words keep their codes, and taught sets survive the
 *      swap untouched.
 *  20. ENCODE_ALL (v1.32): every message codes — 1-char,
 *      whitespace-only, and short messages; the strict
 *      decodeCoded refusals (adversarial plain text, emoji
 *      and control bytes outside escapes, stray symbols);
 *      the escape-only fallback; wireDisplayForm (head
 *      codes, long codes, escapes, invalid input); the
 *      ChatEnvelope build/parse round-trip and its legacy
 *      shapes; wire-form persistence (stampWire/hasWire,
 *      missing keys read unrecorded); SentText byte counts.
 *
 * Legs 11-17 swap the active table; every one restores the
 * built-in table in a finally block, and none mutates the
 * shared built-in table (fresh tables via fromWords).
 *
 * Run via run-launcher-harnesses.sh (WordDict). No Android
 * classes are instantiated and no network is used; the
 * packet leg calls only static JSON helpers.
 */
public final class WordDictHarness {

    private static int checks;
    private static int failures;

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name);
        }
    }

    private static void checkEq(String name, Object want, Object got) {
        checks++;
        if (want == null ? got == null : want.equals(got)) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name + "  -- want <" + want + "> got <" + got + ">");
        }
    }

    public static void main(String[] args) {
        dictionary();
        codeMath();
        roundTrip();
        normalization();
        savings();
        version();
        corrupt();
        printability();
        envelope();
        packet();
        longCodes();
        dictFile();
        learned();
        teach();
        taughtStore();
        fullList();
        versions();
        submit();
        merge();
        encodeAll();
        System.out.println("WordDictHarness: " + (checks - failures) + "/" + checks);
        if (failures > 0) System.exit(1);
    }

    private static void dictionary() {
        check("dict size is 1200", WordDict.size() == 1200);
        check("dict within code space", WordDict.size() <= WordDict.MAX_CODES);
        Set<String> seen = new HashSet<>();
        Set<String> codes = new HashSet<>();
        boolean charsetOk = true;
        boolean dupOk = true;
        for (int i = 0; i < WordDict.size(); i++) {
            String w = WordDict.wordAt(i);
            if (!seen.add(w)) dupOk = false;
            for (int j = 0; j < w.length(); j++) {
                char c = w.charAt(j);
                if (!Character.isLetter(c) && c != '\'') charsetOk = false;
            }
            if (!w.equals(w.toLowerCase(java.util.Locale.ROOT))
                    && !w.equals("I") && !w.equals("I'm") && !w.equals("I'll")
                    && !w.equals("I'd") && !w.equals("I've")) charsetOk = false;
            String code = WordDict.codeFor(i);
            if (code.length() != 2) charsetOk = false;
            if (!codes.add(code)) dupOk = false;
            // Code decodes back to the same index.
            int sym = WordDict.SYMBOLS.indexOf(code.charAt(0));
            int dig = WordDict.DIGIT_CHARS.indexOf(code.charAt(1));
            if (sym * 20 + dig != i) charsetOk = false;
        }
        check("dict entries unique", dupOk);
        check("dict codes unique", codes.size() == WordDict.size());
        check("dict charset + caps rules", charsetOk);
        check("dict headroom", WordDict.MAX_CODES - WordDict.size() == 40);
        checkEq("dict first word", "the", WordDict.wordAt(0));
        check("dict holds app words",
                WordDict.encode("the channel mesh relay walkie bridge gateway contact invite quiet filter signal") != null);
    }

    private static void codeMath() {
        checkEq("code 0", "a0", WordDict.codeFor(0));
        checkEq("code 1 digit 0 subscript", "a1", WordDict.codeFor(1));
        checkEq("code 10 digit 5 superscript", "aa", WordDict.codeFor(10));
        checkEq("code 19 subscript 9", "aj", WordDict.codeFor(19));
        checkEq("code 20", "b0", WordDict.codeFor(20));
        checkEq("code 21", "b1", WordDict.codeFor(21));
        checkEq("code 1199", "7j", WordDict.codeFor(1199));
        checkEq("display 0 superscript 0",
                "a\u2070", WordDict.displayForm(0));
        checkEq("display 1 subscript 0",
                "a\u2080", WordDict.displayForm(1));
        checkEq("display 19 subscript 9",
                "a\u2089", WordDict.displayForm(19));
        checkEq("display 20", "b\u2070", WordDict.displayForm(20));
        // v1.29: long codes display as their wire form; only
        // beyond the code space (or negative) is "?".
        checkEq("display long code is wire form",
                WordDict.codeFor(5000), WordDict.displayForm(5000));
        checkEq("display out of range", "?",
                WordDict.displayForm(WordDict.MAX_INDEX + 1));
        checkEq("display negative", "?", WordDict.displayForm(-1));
    }

    /** Asserts a message IS coded and round-trips exactly. */
    private static void roundTripCoded(String name, String msg) {
        String wire = WordDict.encode(msg);
        check(name + " codes", wire != null);
        if (wire == null) return;
        WordDict.Result r = WordDict.decode(wire, WordDict.VERSION);
        checkEq(name, msg, r.text);
        check(name + " note null", r.note == null);
    }

    private static void roundTrip() {
        roundTripCoded("plain prose",
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.");
        roundTripCoded("question + meeting",
                "Hey are you there? I need to talk to you about the meeting tomorrow morning at the house.");
        roundTripCoded("contractions",
                "Don't forget: I'm going to the store and I'll bring back milk, bread and eggs for breakfast!");
        roundTripCoded("quoted dialogue",
                "\"Hello,\" she said. \"How are you doing today? I hope you are well and happy.\"");
        roundTripCoded("curly quotes",
                "\u201cHello there,\u201d she said, \u201cdon\u2019t worry about it.\u201d Please come here now and tell me all about it today.");
        roundTripCoded("newlines and tabs",
                "Line one here please.\nLine two there now.\tTabbed part here and more words to make it long enough to code today.");
        roundTripCoded("hyphenated",
                "The well-known mother-in-law came over and the twenty-one year old boy went to school today.");
        roundTripCoded("many sentences",
                "First we go to the store. Then we come back home. After that we eat food and drink water. Later we go to sleep and wake up early in the morning.");
        roundTripCoded("mostly unknown words",
                "The quick brown fox jumps over the lazy dog and runs away into the forest near the river.");
        roundTripCoded("all caps",
                "YES I AM HERE AND I CAN HEAR YOU LOUD AND CLEAR");
        roundTripCoded("numbers and names",
                "Call me at 555-1234 or meet at 5pm near the big red store on Main Street, Taos.");
        // v1.32: these code now, so sentence-start
        // normalization applies after "..." and after the
        // periods in abbreviations (documented behavior).
        checkCodedAs("punctuation torture",
                "What?! Really... yes \u2014 no; maybe: (probably) [definitely] {maybe} \"quoted\" 'single' 100% sure @home #tag $5 & more <tag> `code` _under_ /slash\\ backslash | pipe = equals + plus * star ^ caret ~ tilde.",
                "What?! Really... Yes \u2014 no; maybe: (probably) [definitely] {maybe} \"quoted\" 'single' 100% sure @home #tag $5 & more <tag> `code` _under_ /slash\\ backslash | pipe = equals + plus * star ^ caret ~ tilde.");
        checkCodedAs("abbreviations",
                "U.S.A. and e.g. this and that, i.e. the thing. Dr. Smith went to Washington.",
                "U.S.A. And e.g. This and that, I.e. The thing. Dr. Smith went to Washington.");
        // "emoji" is not a dictionary word: it travels as
        // an escape, verbatim — no normalization.
        roundTripCoded("emoji and unicode",
                "emoji test \uD83D\uDE00 and caf\u00e9 na\u00efve \u4e2d\u6587 words here please");
        roundTripCoded("apostrophe name",
                "McDonald's on the corner said the price is $4.99 for 2 items (50% off) \u2014 see you there!");
        roundTripCoded("tilde run",
                "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
        roundTripCoded("multiple spaces",
                "What the ~heck~ is going on???  Multiple   spaces and\ttabs\nand newlines...");
        // Very long message: 200 common words.
        StringBuilder longMsg = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            longMsg.append("Please tell me all about the time you went to the house and saw the people there today. ");
        }
        roundTripCoded("very long", longMsg.toString().trim());
        // Long unknown run (over the 99-char escape chunk).
        StringBuilder unknown = new StringBuilder(
                "Please read this carefully because ");
        for (int i = 0; i < 30; i++) unknown.append("Xyzzyplugh ");
        unknown.append("and then let me know what you think about all of it today.");
        roundTripCoded("long unknown run", unknown.toString());
        // Empty / null never code; everything else does
        // (v1.32: no savings gate).
        check("empty null", WordDict.encode("") == null);
        check("null null", WordDict.encode(null) == null);
        roundTripCoded("spaces code", "   ");
        checkCodedAs("single letter codes", "a", "A");
        roundTripCoded("single tilde codes", "~");
        roundTripCoded("hi codes", "Hi");
        check("ok codes", WordDict.encode("ok") != null);
        check("empty wire decodes empty",
                "".equals(WordDict.decode("", WordDict.VERSION).text));
    }

    private static void normalization() {
        // Sentence-start capitals: a lowercase word at a
        // sentence start decodes capitalized (documented).
        checkCodedAs("lowercase sentence start",
                "one two three four five six seven eight nine ten eleven twelve",
                "One two three four five six seven eight nine ten eleven twelve");
        checkCodedAs("lowercase after period",
                "hello there. how are you doing today my friend, i hope you are well and happy",
                "Hello there. How are you doing today my friend, I hope you are well and happy");
        checkCodedAs("lowercase i forms",
                "i am here and i want to go home today please let me know if you can help me with this",
                "I am here and I want to go home today please let me know if you can help me with this");
        checkCodedAs("lowercase contractions",
                "i'm going to the store and i'll be back soon with the things you asked for today",
                "I'm going to the store and I'll be back soon with the things you asked for today");
        // A Capitalized word mid-sentence travels raw and
        // reproduces exactly — no normalization there.
        roundTripCoded("capitalized mid-sentence raw",
                "He said Hello to everyone here today and then went home to tell them all about it now.");
    }

    private static void checkCodedAs(String name, String msg, String want) {
        String wire = WordDict.encode(msg);
        check(name + " codes", wire != null);
        if (wire == null) return;
        WordDict.Result r = WordDict.decode(wire, WordDict.VERSION);
        checkEq(name, want, r.text);
        check(name + " note null", r.note == null);
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void savings() {
        String[] samples = {
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.",
                "Hey are you there? I need to talk to you about the meeting tomorrow morning at the house.",
                "Please tell me all about the time you went to the house and saw the people there today and what you said to them when you came back home again.",
        };
        for (int i = 0; i < samples.length; i++) {
            String wire = WordDict.encode(samples[i]);
            check("sample " + i + " codes", wire != null);
            if (wire == null) continue;
            int raw = utf8(samples[i]);
            int w = utf8(wire);
            System.out.println("SAVINGS sample " + i + ": raw=" + raw
                    + " wire=" + w + " saved=" + (raw - w) + " bytes");
            // v1.32: no marker rides anymore, so the wire
            // itself must be smaller for prose that codes.
            check("sample " + i + " saves bytes", w < raw);
        }
        // A long all-common message saves at least 30%.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("Please tell me all about the time you went to the house and saw the people there today. ");
        }
        String wire = WordDict.encode(sb.toString().trim());
        check("long message codes", wire != null);
        if (wire != null) {
            int raw = utf8(sb.toString().trim());
            int w = utf8(wire);
            System.out.println("SAVINGS long: raw=" + raw + " wire=" + w
                    + " (" + (100 * (raw - w) / raw) + "% smaller)");
            check("long message saves 30%", w * 10 <= raw * 7);
        }
        // v1.32: the savings rule is gone — every message
        // codes, and a message of unknown words may grow
        // (escape overhead). Prose still saves; the
        // escape-heavy cases only promise a wire form.
        String[] corpus = {
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.",
                "The quick brown fox jumps over the lazy dog and runs away into the forest near the river.",
                "Don't forget: I'm going to the store and I'll bring back milk, bread and eggs for breakfast!",
                "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        };
        for (String msg : corpus) {
            String w2 = WordDict.encode(msg);
            check("encode-all: " + msg.substring(0, Math.min(20, msg.length())),
                    w2 != null);
        }
        check("prose still saves",
                utf8(WordDict.encode(corpus[0])) < utf8(corpus[0]));
        check("prose 3 still saves",
                utf8(WordDict.encode(corpus[2])) < utf8(corpus[2]));
    }

    private static void version() {
        String wire = WordDict.encode(
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.");
        check("version fixture codes", wire != null);
        if (wire == null) return;
        // v1.29: version 2 is current, so "newer" is 3+.
        WordDict.Result newer = WordDict.decode(wire, 3);
        checkEq("newer version keeps raw text", wire, newer.text);
        check("newer version note names newer list",
                newer.note != null && newer.note.contains("newer word list"));
        WordDict.Result v99 = WordDict.decode(wire, 99);
        check("v99 note", v99.note != null && v99.note.contains("v99"));
        WordDict.Result bad = WordDict.decode(wire, 0);
        check("version 0 via decode is corrupt-noted",
                bad.note != null && wire.equals(bad.text));
        WordDict.Result plain = WordDict.decodeMarked("just plain text", 0);
        checkEq("decodeMarked 0 passes through", "just plain text", plain.text);
        check("decodeMarked 0 note null", plain.note == null);
        WordDict.Result plainNull = WordDict.decodeMarked(null, 0);
        checkEq("decodeMarked null 0 empty", "", plainNull.text);
        WordDict.Result nullWire = WordDict.decode(null, 1);
        check("decode null noted", nullWire.note != null && "".equals(nullWire.text));
        WordDict.Result ok = WordDict.decodeMarked(wire, 1);
        check("decodeMarked 1 decodes", ok.note == null
                && ok.text.startsWith("Hello world"));
    }

    private static void corrupt() {
        // No trailing period: the wire ends in a code, so
        // truncating one byte breaks the final token.
        String good = WordDict.encode(
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today");
        check("corrupt fixture codes", good != null);
        String[] badWires = {
                "a",            // lone symbol
                "a.",           // symbol + non-digit
                "~05ab",        // escape overrun
                "~xx12",        // bad escape length
                "~00",          // zero-length escape
                "a3~99x",       // escape overrun mid-wire
                "9j",           // code index 1239, past the dictionary
                "80",           // code index 1200, past the dictionary
                "~",            // lone escape
                "~0",           // truncated escape
                "~051",         // escape length says 5, only 1 follows
        };
        for (String bad : badWires) {
            WordDict.Result r = WordDict.decode(bad, 1);
            check("corrupt noted: " + bad,
                    r.note != null && bad.equals(r.text));
        }
        if (good != null) {
            // Truncating a valid wire by one byte breaks the
            // final token and must note, never throw.
            WordDict.Result t = WordDict.decode(
                    good.substring(0, good.length() - 1), 1);
            check("truncated wire noted", t.note != null);
            // Cutting a wire mid-escape must note too.
            int esc = good.indexOf('~');
            if (esc >= 0 && esc + 2 < good.length()) {
                WordDict.Result m = WordDict.decode(good.substring(0, esc + 2), 1);
                check("mid-escape cut noted", m.note != null);
            }
        }
        // decode() never throws, even on hostile input.
        try {
            WordDict.decode("\u0000\u0001\u0002\uD83D\uDE00", 1);
            WordDict.decode("~~~~~~", 1);
            WordDict.decode("zzzzzzzzzz", 1);
            check("hostile input no throw", true);
        } catch (Exception e) {
            check("hostile input no throw", false);
        }
    }

    private static void printability() {
        String[] samples = {
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.",
                "Don't forget: I'm going to the store and I'll bring back milk, bread and eggs for breakfast!",
                "Line one here please.\nLine two there now.\tTabbed part here and more words to make it long enough to code today.",
        };
        for (String msg : samples) {
            String wire = WordDict.encode(msg);
            if (wire == null) continue;
            byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
            String back = new String(bytes, StandardCharsets.UTF_8);
            check("wire valid UTF-8", wire.equals(back));
            boolean clean = true;
            for (int i = 0; i < wire.length(); i++) {
                char c = wire.charAt(i);
                // No control bytes the original did not
                // already contain: \n and \t pass through,
                // nothing else below 0x20 may appear.
                if (c < 0x20 && c != '\n' && c != '\t' && c != '\r') clean = false;
                if (c == 0x7f) clean = false;
            }
            check("wire printable", clean);
        }
    }

    private static void envelope() {
        // v1.32: every text codes on the "w" key, no marker
        // — short texts included. The exact shapes:
        String helloWire = WordDict.encode("hello");
        checkEq("envelope ordinary shape",
                "{\"t\":\"text\",\"w\":\"" + helloWire + "\"}",
                LowDataEnvelope.build("hello", false));
        checkEq("envelope ld shape",
                "{\"t\":\"text\",\"w\":\"" + helloWire + "\",\"ld\":true}",
                LowDataEnvelope.build("hello", true));
        checkEq("envelope empty",
                "{\"t\":\"text\",\"w\":\"\"}",
                LowDataEnvelope.build("", false));
        check("envelope carries no marker",
                !LowDataEnvelope.build("hello", false).contains("\"wd\""));
        // A long text codes and decodes back.
        String longText = "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.";
        String env = LowDataEnvelope.build(longText, false);
        check("envelope uses w key", env.contains("\"w\":"));
        check("envelope has no text key", !env.contains("\"text\":"));
        LowDataEnvelope.Parsed p = LowDataEnvelope.parse(env);
        checkEq("envelope decodes", longText, p.text);
        check("envelope note null", p.wordNote == null);
        check("envelope ld clear", !p.lowData);
        check("envelope coded flag", p.coded);
        checkEq("envelope wire recorded",
                WordDict.encode(longText), p.wire);
        // buildDetailed returns the same wire form.
        LowDataEnvelope.Built built = LowDataEnvelope.buildDetailed(longText, false, null);
        checkEq("buildDetailed envelope", env, built.envelope);
        checkEq("buildDetailed wire", WordDict.encode(longText), built.wire);
        String envLd = LowDataEnvelope.build(longText, true);
        LowDataEnvelope.Parsed pLd = LowDataEnvelope.parse(envLd);
        checkEq("envelope ld decodes", longText, pLd.text);
        check("envelope ld kept", pLd.lowData);
        // Old envelopes (no marker) still parse plain.
        LowDataEnvelope.Parsed old = LowDataEnvelope.parse(
                "{\"t\":\"text\",\"text\":\"hi\"}");
        check("old envelope plain",
                old != null && "hi".equals(old.text) && old.wordNote == null
                        && !old.coded && "hi".equals(old.wire));
        // A legacy marked envelope still decodes.
        String legacyWire = WordDict.encode(longText);
        LowDataEnvelope.Parsed legacy = LowDataEnvelope.parse(
                "{\"t\":\"text\",\"text\":\"" + legacyWire + "\",\"wd\":1}");
        checkEq("legacy marked envelope decodes", longText, legacy.text);
        check("legacy marked envelope coded", legacy.coded);
        checkEq("legacy marked envelope wire", legacyWire, legacy.wire);
        // A newer-version envelope carries the note.
        LowDataEnvelope.Parsed newer = LowDataEnvelope.parse(
                "{\"t\":\"text\",\"text\":\"abc\",\"wd\":3}");
        check("envelope newer noted",
                newer != null && "abc".equals(newer.text)
                        && newer.wordNote != null
                        && newer.wordNote.contains("newer word list"));
        // A quoted wd is not a marker: plain text shows.
        LowDataEnvelope.Parsed quoted = LowDataEnvelope.parse(
                "{\"t\":\"text\",\"text\":\"hi\",\"wd\":\"1\"}");
        check("envelope quoted wd unmarked",
                quoted != null && "hi".equals(quoted.text) && quoted.wordNote == null);
        // A corrupt coded envelope carries the corrupt note.
        LowDataEnvelope.Parsed bad = LowDataEnvelope.parse(
                "{\"t\":\"text\",\"text\":\"a\",\"wd\":1}");
        check("envelope corrupt noted",
                bad != null && "a".equals(bad.text) && bad.wordNote != null);
        // extractInt shapes.
        checkEq("extractInt plain", 1,
                LowDataEnvelope.extractInt("{\"wd\":1}", "wd"));
        checkEq("extractInt spaced", 2,
                LowDataEnvelope.extractInt("{ \"wd\" : 2 }", "wd"));
        checkEq("extractInt absent", 0,
                LowDataEnvelope.extractInt("{\"t\":\"text\"}", "wd"));
        checkEq("extractInt quoted", 0,
                LowDataEnvelope.extractInt("{\"wd\":\"1\"}", "wd"));
        // Bridged relay texts code on the "w" key and keep
        // their provenance parseable.
        BridgeRule.Relayed r = BridgeRule.Relayed.of("bid-1", "Ana", "pub-x",
                555L, "Gail", EnumSet.of(BridgeRule.Transport.MESH));
        String relayed = BridgeRule.buildRelayedText(longText, true, r);
        check("relayed text uses w key", relayed.contains("\"w\":"));
        check("relayed text carries no marker", !relayed.contains("\"wd\""));
        BridgeRule.Relayed back = BridgeRule.relayedFromTextPayload(relayed);
        check("relayed provenance parses", back != null && "bid-1".equals(back.bid));
        LowDataEnvelope.Parsed rp = LowDataEnvelope.parse(relayed);
        checkEq("relayed text decodes", longText, rp.text);
        check("relayed ld kept", rp.lowData);
        // A short relayed text codes too (v1.32).
        String shortRelayed = BridgeRule.buildRelayedText("hi", false, r);
        check("short relayed coded",
                shortRelayed.contains("\"w\":") && !shortRelayed.contains("\"wd\""));
        LowDataEnvelope.Parsed srp = LowDataEnvelope.parse(shortRelayed);
        check("short relayed decodes", srp != null && srp.wordNote == null);
        // RelayIngest decodes a sealed coded envelope.
        byte[] k1 = new byte[32];
        for (int i = 0; i < 32; i++) k1[i] = (byte) i;
        RelayIngest.Decoded d = RelayIngest.decode(k1, RelayCrypto.seal(
                k1, LowDataEnvelope.build(longText, false)
                        .getBytes(StandardCharsets.UTF_8)));
        check("ingest coded text", d != null && d.isText);
        checkEq("ingest coded text decodes", longText, d == null ? null : d.text);
        check("ingest coded note null", d != null && d.wordNote == null);
        // A sealed newer-version envelope carries the note.
        String newerEnv = "{\"t\":\"text\",\"text\":\"abc\",\"wd\":3}";
        RelayIngest.Decoded dn = RelayIngest.decode(k1, RelayCrypto.seal(
                k1, newerEnv.getBytes(StandardCharsets.UTF_8)));
        check("ingest newer noted",
                dn != null && dn.isText && "abc".equals(dn.text)
                        && dn.wordNote != null);
        // stampEntry stamps the note onto the entry, and
        // the wire form onto a cleanly decoded entry.
        if (dn != null) {
            try {
                JSONObject entry = new JSONObject();
                RelayCore.Msg m = new RelayCore.Msg();
                m.clientMsgId = "cmid-1";
                m.sender = "pub-x";
                RelayIngest.stampEntry(entry, m, dn);
                check("stampEntry stamps wdNote",
                        dn.wordNote.equals(entry.optString("wdNote", "")));
                check("stampEntry stamps undecoded wire",
                        "abc".equals(entry.optString("wire", "")));
                check("stampEntry undecoded plainBytes unknown",
                        entry.optInt("plainBytes", 0) == -1);
            } catch (Exception e) {
                check("stampEntry stamps wdNote", false);
            }
        }
        if (d != null) {
            try {
                JSONObject entry = new JSONObject();
                RelayCore.Msg m = new RelayCore.Msg();
                m.clientMsgId = "cmid-2";
                m.sender = "pub-x";
                RelayIngest.stampEntry(entry, m, d);
                checkEq("stampEntry stamps wire",
                        WordDict.encode(longText), entry.optString("wire", ""));
                check("stampEntry stamps coded",
                        entry.optBoolean("coded", false));
                checkEq("stampEntry stamps plainBytes",
                        longText.getBytes(StandardCharsets.UTF_8).length,
                        entry.optInt("plainBytes", -1));
            } catch (Exception e) {
                check("stampEntry stamps wire", false);
            }
        }
    }

    private static void packet() {
        String longText = "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.";
        try {
            JSONObject o = new JSONObject();
            String meshWire = MeshNet.putText(o, longText);
            check("mesh packet uses w key",
                    o.has("w") && !o.has("text") && !o.has("wd"));
            checkEq("mesh putText returns wire",
                    WordDict.encode(longText), meshWire);
            WordDict.Result r = WordDict.decodeCoded(
                    o.optString("w", ""), null);
            checkEq("mesh packet decodes", longText, r.text);
            check("mesh packet note null", r.note == null);
            JSONObject shortO = new JSONObject();
            MeshNet.putText(shortO, "hi");
            check("mesh short coded",
                    shortO.has("w") && !shortO.has("text")
                            && !shortO.has("wd"));
            check("mesh short decodes",
                    WordDict.decodeCoded(shortO.optString("w", ""), null).note == null);
            // v1.29: the scoped overloads (the production
            // send path) code the same way. The dictionary
            // store is uninitialized in the harness, so no
            // teach block rides here — the teach attachment
            // itself is proven by the encodeScoped and
            // envelope legs.
            JSONObject scoped = new JSONObject();
            MeshNet.putText(scoped, longText, "open:12");
            check("mesh scoped packet uses w key",
                    scoped.has("w") && !scoped.has("wd"));
            WordDict.Result sr = WordDict.decodeCoded(
                    scoped.optString("w", ""),
                    scoped.has("wdt") ? scoped.optString("wdt", "") : null);
            checkEq("mesh scoped packet decodes", longText, sr.text);
            JSONObject scopedB = new JSONObject();
            BtWalkie.putText(scopedB, longText, "open:12");
            check("bt scoped packet uses w key",
                    scopedB.has("w") && !scopedB.has("wd"));
            JSONObject b = new JSONObject();
            BtWalkie.putText(b, longText);
            check("bt packet uses w key",
                    b.has("w") && !b.has("text") && !b.has("wd"));
            WordDict.Result rb = WordDict.decodeCoded(
                    b.optString("w", ""), null);
            checkEq("bt packet decodes", longText, rb.text);
            JSONObject shortB = new JSONObject();
            BtWalkie.putText(shortB, "hi");
            check("bt short coded",
                    shortB.has("w") && !shortB.has("text")
                            && !shortB.has("wd"));
            // Legacy packets still decode: a "wd"-marked
            // payload and a plain payload.
            WordDict.Result legacyMarked = WordDict.decodeMarked(
                    WordDict.encode(longText), 1);
            checkEq("legacy marked packet decodes", longText, legacyMarked.text);
            WordDict.Result legacyPlain = WordDict.decodeMarked("hi", 0);
            checkEq("legacy plain packet passes", "hi", legacyPlain.text);
            // A newer-version packet carries the note.
            WordDict.Result newer = WordDict.decodeMarked("abc", 3);
            check("packet newer noted",
                    newer.note != null && "abc".equals(newer.text));
        } catch (Exception e) {
            check("packet leg runs", false);
            e.printStackTrace(System.out);
        }
    }

    // ---- v1.29 legs ----

    private static void longCodes() {
        int[] idxs = {0, 1, 1199, 1200, 1239, 1240, 1241, 5000, 100000,
                370163, WordTable.LEARNED_BASE, WordTable.LEARNED_BASE + 49999,
                WordDict.MAX_INDEX};
        boolean rtOk = true;
        boolean lenOk = true;
        boolean printOk = true;
        for (int idx : idxs) {
            String code = WordDict.codeFor(idx);
            int[] p = WordDict.parseCode(code, 0);
            if (p == null || p[0] != idx || p[1] != code.length()) rtOk = false;
            int wantLen = idx < WordDict.LONG_BASE ? 2 : 4;
            if (code.length() != wantLen) lenOk = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c < 0x21 || c > 0x7e) printOk = false;
            }
        }
        check("long codes round-trip at boundaries", rtOk);
        check("long codes lengths (2 below 1240, 4 at/above)", lenOk);
        check("long codes printable ASCII", printOk);
        checkEq("code 1240", "akaa", WordDict.codeFor(1240));
        checkEq("code MAX_INDEX", "9Z99", WordDict.codeFor(WordDict.MAX_INDEX));
        checkEq("code beyond max empty", "", WordDict.codeFor(WordDict.MAX_INDEX + 1));
        checkEq("code negative empty", "", WordDict.codeFor(-1));
        // LEAD and DIGIT alphabets are disjoint: the second
        // byte decides the form unambiguously.
        boolean disjoint = true;
        for (int i = 0; i < WordDict.LEAD_CHARS.length(); i++) {
            if (WordDict.DIGIT_CHARS.indexOf(WordDict.LEAD_CHARS.charAt(i)) >= 0) {
                disjoint = false;
            }
        }
        check("LEAD/DIGIT disjoint", disjoint);
        checkEq("LEAD size", 42, WordDict.LEAD_CHARS.length());
        checkEq("LONG_SPAN", 62 * 42 * 62 * 62, WordDict.LONG_SPAN);
        // parseCode inside a wire: codes back to back.
        String wire = WordDict.codeFor(0) + WordDict.codeFor(5000) + " "
                + WordDict.codeFor(1200);
        int[] p0 = WordDict.parseCode(wire, 0);
        int[] p1 = WordDict.parseCode(wire, p0[1]);
        check("parseCode back-to-back", p0[0] == 0 && p1[0] == 5000);
        check("parseCode rejects truncated long code",
                WordDict.parseCode(WordDict.codeFor(5000).substring(0, 3), 0) == null);
        check("parseCode rejects bad second byte",
                WordDict.parseCode("a.", 0) == null);
        check("parseCode rejects bad tail byte",
                WordDict.parseCode("ak.x", 0) == null);
        // parseCodeString: exactly one code, whole string.
        checkEq("parseCodeString 2-byte", 0, WordDict.parseCodeString("a0"));
        checkEq("parseCodeString 4-byte", 5000,
                WordDict.parseCodeString(WordDict.codeFor(5000)));
        checkEq("parseCodeString rejects two codes", -1, WordDict.parseCodeString("a0a0"));
        checkEq("parseCodeString rejects trailing", -1, WordDict.parseCodeString("a0x"));
        checkEq("parseCodeString rejects empty", -1, WordDict.parseCodeString(""));
        checkEq("parseCodeString rejects null", -1, WordDict.parseCodeString(null));
    }

    private static void dictFile() {
        byte[] a = "the\ncat\ndog\n".getBytes(StandardCharsets.UTF_8);
        byte[] b1 = DictFile.build(a);
        byte[] b2 = DictFile.build(a);
        check("dictfile builds", b1 != null);
        check("dictfile deterministic",
                b1 != null && b2 != null && Arrays.equals(b1, b2));
        DictFile.Parsed p = b1 == null ? null : DictFile.parse(b1);
        check("dictfile parses", p != null);
        checkEq("dictfile count", 3, p == null ? -1 : p.count());
        checkEq("dictfile word 1", "cat", p == null ? null : p.wordAt(1));
        check("dictfile wordAt out of range",
                p != null && p.wordAt(3) == null && p.wordAt(-1) == null);
        // CRLF, blank lines, missing trailing newline: the
        // same blob.
        byte[] crlf = DictFile.build(
                "the\r\ncat\r\ndog\r\n".getBytes(StandardCharsets.UTF_8));
        check("dictfile CRLF equals LF", Arrays.equals(b1, crlf));
        byte[] blanks = DictFile.build(
                "\nthe\n\ncat\n\ndog\n\n".getBytes(StandardCharsets.UTF_8));
        check("dictfile blank lines skipped", Arrays.equals(b1, blanks));
        byte[] noTrail = DictFile.build("the\ncat\ndog".getBytes(StandardCharsets.UTF_8));
        check("dictfile missing trailing newline", Arrays.equals(b1, noTrail));
        // Build rejection.
        check("dictfile rejects empty", DictFile.build(new byte[0]) == null);
        check("dictfile rejects null", DictFile.build(null) == null);
        check("dictfile rejects blanks only",
                DictFile.build("\n\n".getBytes(StandardCharsets.UTF_8)) == null);
        check("dictfile rejects digit word",
                DictFile.build("abc1\n".getBytes(StandardCharsets.UTF_8)) == null);
        check("dictfile rejects space word",
                DictFile.build("two words\n".getBytes(StandardCharsets.UTF_8)) == null);
        StringBuilder long70 = new StringBuilder();
        for (int i = 0; i < 70; i++) long70.append('a');
        check("dictfile rejects long word", DictFile.build(
                (long70 + "\n").getBytes(StandardCharsets.UTF_8)) == null);
        check("dictfile accepts apostrophe",
                DictFile.build("don't\n".getBytes(StandardCharsets.UTF_8)) != null);
        // Parse rejection: surgery on a valid blob.
        if (b1 != null) {
            byte[] badMagic = b1.clone();
            badMagic[0] = 'X';
            check("dictfile rejects bad magic", DictFile.parse(badMagic) == null);
            byte[] badFormat = b1.clone();
            badFormat[7] = 2;
            check("dictfile rejects bad format", DictFile.parse(badFormat) == null);
            byte[] truncated = Arrays.copyOf(b1, b1.length - 1);
            check("dictfile rejects truncation", DictFile.parse(truncated) == null);
            byte[] extended = Arrays.copyOf(b1, b1.length + 1);
            check("dictfile rejects trailing garbage", DictFile.parse(extended) == null);
            byte[] badOffset = b1.clone();
            badOffset[12 + 4] = 0;
            badOffset[12 + 5] = 0;
            badOffset[12 + 6] = 0;
            badOffset[12 + 7] = 0;
            check("dictfile rejects bad offsets", DictFile.parse(badOffset) == null);
            byte[] badUtf = b1.clone();
            badUtf[badUtf.length - 1] = (byte) 0xff;
            check("dictfile rejects bad UTF-8", DictFile.parse(badUtf) == null);
            check("dictfile parse null safe", DictFile.parse(null) == null);
            check("dictfile parse short safe", DictFile.parse(new byte[5]) == null);
        }
        check("dictfile validWord", DictFile.validWord("don't")
                && !DictFile.validWord("a1")
                && !DictFile.validWord("") && !DictFile.validWord(null));
    }

    /** Four lowercase letters encoding i in base 26. */
    private static String capSuffix(int i) {
        char[] c = new char[4];
        for (int k = 3; k >= 0; k--) {
            c[k] = (char) ('a' + (i % 26));
            i /= 26;
        }
        return new String(c);
    }

    private static void learned() {
        WordTable saved = WordDict.activeTable();
        try {
            WordTable t = WordTable.fromWords(WordDict.WORDS);
            WordDict.setActiveTable(t);
            int q = t.learn("quokka");
            checkEq("learn first index", WordTable.LEARNED_BASE, q);
            checkEq("learn again same index", q, t.learn("quokka"));
            checkEq("learn base word -1", -1, t.learn("the"));
            checkEq("learn single letter -1", -1, t.learn("z"));
            checkEq("learn caps -1", -1, t.learn("Quokka"));
            checkEq("learnedIndex", q, t.learnedIndex("quokka"));
            checkEq("wordAt learned", "quokka", t.wordAt(q));
            checkEq("learnedCount 1", 1, t.learnedCount());
            check("not at cap", !t.atCap());
            // learnFromText: counts only newly learned.
            int before = t.learnedCount();
            int added = t.learnFromText("quokka wombat the house");
            checkEq("learnFromText counts new only",
                    t.learnedCount() - before, added);
            check("learnFromText learned wombat", t.learnedIndex("wombat") != null);
            checkEq("learnFromText idempotent", 0,
                    t.learnFromText("quokka wombat the house"));
            checkEq("learnFromText skips caps", 0, t.learnFromText("ZEBRAX"));
            check("learnFromText caps unlearned", t.learnedIndex("zebrax") == null);
            checkEq("learnFromText skips singles", 0, t.learnFromText("q q q"));
            // Graduation: the word merges into the base list.
            WordTable.LearnedState snap = t.snapshotLearned();
            String[] grown = Arrays.copyOf(WordDict.WORDS, WordDict.WORDS.length + 1);
            grown[WordDict.WORDS.length] = "quokka";
            WordTable t2 = WordTable.fromWords(grown);
            t2.restoreLearned(snap);
            WordDict.setActiveTable(t2);
            check("graduated word has base index", t2.baseIndex("quokka") != null);
            checkEq("graduated base index", 1200, (int) t2.baseIndex("quokka"));
            check("graduated word no longer learned", t2.learnedIndex("quokka") == null);
            // The encoder prefers the base code.
            List<TeachBlock.Entry> teachOut = new ArrayList<>();
            WordDict.WireBuild wb = WordDict.buildWireScoped(
                    "the quokka and the dog run near the water and the quokka runs after the horse",
                    t2, new HashSet<Integer>(), teachOut, true);
            checkEq("graduation encodes base code", 1200, wb.maxIndex);
            check("graduation needs no teach", teachOut.isEmpty());
            // Both codes decode to the word.
            WordDict.Result viaBase = WordDict.decode(WordDict.codeFor(1200), 2);
            WordDict.Result viaLearned = WordDict.decode(WordDict.codeFor(q), 2);
            checkEq("base code decodes", "Quokka", viaBase.text);
            checkEq("old learned code decodes", "Quokka", viaLearned.text);
            check("graduation notes null",
                    viaBase.note == null && viaLearned.note == null);
            // Serialize/load: codes survive a simulated restart.
            WordTable t3 = WordTable.fromWords(WordDict.WORDS);
            int a3 = t3.learn("quokka");
            int b3 = t3.learn("wombat");
            String ser = t3.serializeLearned();
            WordTable t4 = WordTable.fromWords(WordDict.WORDS);
            int loaded = t4.loadLearned(ser);
            checkEq("loadLearned count", 2, loaded);
            checkEq("loadLearned quokka code", a3, (int) t4.learnedIndex("quokka"));
            checkEq("loadLearned wombat code", b3, (int) t4.learnedIndex("wombat"));
            checkEq("loadLearned serialize stable", ser, t4.serializeLearned());
            // Defensive load: garbage lines skipped.
            WordTable t5 = WordTable.fromWords(WordDict.WORDS);
            int loaded5 = t5.loadLearned(
                    "garbage\n5000000 quokka\n12 nope\n5000001 BAD\n5000000 quokka\n");
            checkEq("loadLearned skips garbage", 1, loaded5);
            // Load onto a graduated table aliases the old code.
            WordTable t6 = WordTable.fromWords(grown);
            t6.loadLearned(ser);
            check("loadLearned graduates to alias",
                    t6.learnedIndex("quokka") == null
                            && "quokka".equals(t6.wordAt(a3)));
            // The cap: 50,000 learned entries, then -1.
            WordTable cap = WordTable.fromWords(WordDict.WORDS);
            boolean fillOk = true;
            for (int i = 0; i < WordTable.MAX_LEARNED; i++) {
                if (cap.learn("qq" + capSuffix(i)) < 0) {
                    fillOk = false;
                    break;
                }
            }
            check("cap fills", fillOk);
            checkEq("cap count", WordTable.MAX_LEARNED, cap.learnedCount());
            checkEq("cap refuses", -1, cap.learn("qqzzzz"));
            check("cap flag", cap.atCap());
        } finally {
            WordDict.setActiveTable(saved);
        }
    }

    private static void teach() {
        WordTable saved = WordDict.activeTable();
        try {
            // Two phones: S teaches R two new words.
            WordTable s = WordTable.fromWords(WordDict.WORDS);
            WordTable r = WordTable.fromWords(WordDict.WORDS);
            s.learn("quokka");
            s.learn("wombat");
            String msg = "The quokka and the wombat run near the water and the quokka runs after the horse near the house";
            WordDict.setActiveTable(s);
            WordDict.Encoded e = WordDict.encodeScoped(msg, new HashSet<Integer>());
            check("teach block present", e != null && !e.teach.isEmpty());
            check("teach block has both words",
                    e != null && e.teach.contains("quokka:")
                            && e.teach.contains("wombat:"));
            List<TeachBlock.Entry> probe = new ArrayList<>();
            WordDict.WireBuild wb = WordDict.buildWireScoped(
                    msg, s, new HashSet<Integer>(), probe, true);
            check("untaught words not coded", wb.maxIndex < WordTable.LEARNED_BASE);
            checkEq("teach entries collected", 2, probe.size());
            // R receives: the teach block is applied first
            // (v1.32: the coded shape decodes strictly).
            WordDict.setActiveTable(r);
            WordDict.Result rr = WordDict.decodeCoded(e.wire, e.teach);
            checkEq("taught message decodes", msg, rr.text);
            check("taught note null", rr.note == null);
            checkEq("adopted both", 2, rr.adopted.size());
            checkEq("receiver learned quokka code", WordTable.LEARNED_BASE,
                    (int) r.learnedIndex("quokka"));
            // R replies, coded: the taught set gates the codes.
            String reply = "The quokka runs near the water and the wombat runs after the horse near the house today";
            WordDict.Encoded e2 = WordDict.encodeScoped(reply, new HashSet<>(rr.adopted));
            check("taught reply codes", e2 != null && e2.wire != null);
            check("taught reply needs no teach", e2 != null && e2.teach.isEmpty());
            check("taught reply uses learned codes", e2 != null && e2.wire != null
                    && e2.wire.contains(WordDict.codeFor(WordTable.LEARNED_BASE)));
            // S decodes R's coded reply (v1.32 shape).
            WordDict.setActiveTable(s);
            WordDict.Result sr = WordDict.decodeCoded(e2.wire, e2.teach);
            checkEq("coded reply decodes", reply, sr.text);
            // Conflict: the sender's code is authoritative.
            WordTable s2 = WordTable.fromWords(WordDict.WORDS);
            WordTable r2 = WordTable.fromWords(WordDict.WORDS);
            s2.learn("alphax");
            int sQuokka = s2.learn("quokka");
            int rQuokka = r2.learn("quokka");
            check("conflict setup differs", sQuokka != rQuokka);
            WordDict.setActiveTable(r2);
            WordDict.decodeMarked("hello", 0, TeachBlock.build(Arrays.asList(
                    new TeachBlock.Entry("quokka", sQuokka))));
            check("conflict adopts sender code",
                    r2.learnedIndex("quokka") != null
                            && r2.learnedIndex("quokka") == sQuokka);
            checkEq("conflict old code still decodes", "quokka", r2.wordAt(rQuokka));
            Set<Integer> taught2 = new HashSet<>();
            taught2.add(sQuokka);
            List<TeachBlock.Entry> probe2 = new ArrayList<>();
            WordDict.WireBuild wb2 = WordDict.buildWireScoped(
                    "the quokka and the dog run near the water",
                    r2, taught2, probe2, true);
            checkEq("conflict encodes adopted code", sQuokka, wb2.maxIndex);
            WordDict.Result old = WordDict.decode(WordDict.codeFor(rQuokka), 2);
            checkEq("conflict old code decodes", "Quokka", old.text);
            // Adopting a teach for a BASE word aliases the
            // sender's code (it must decode) without
            // recording the word as learned.
            WordTable r3 = WordTable.fromWords(WordDict.WORDS);
            check("adopt base word aliases",
                    r3.adopt("the", WordTable.LEARNED_BASE + 7));
            checkEq("adopt base word decodes", "the",
                    r3.wordAt(WordTable.LEARNED_BASE + 7));
            check("adopt base word not learned", r3.learnedIndex("the") == null);
            // TeachBlock: build/parse, caps, malformed input.
            List<TeachBlock.Entry> entries = new ArrayList<>();
            entries.add(new TeachBlock.Entry("quokka", WordTable.LEARNED_BASE));
            entries.add(new TeachBlock.Entry("wombat", WordTable.LEARNED_BASE + 1));
            String block = TeachBlock.build(entries);
            List<TeachBlock.Entry> back = TeachBlock.parse(block);
            checkEq("teachblock round-trip", 2, back.size());
            checkEq("teachblock word", "quokka", back.get(0).word);
            checkEq("teachblock index 2", WordTable.LEARNED_BASE + 1, back.get(1).index);
            checkEq("teachblock parse null", 0, TeachBlock.parse(null).size());
            checkEq("teachblock parse empty", 0, TeachBlock.parse("").size());
            List<TeachBlock.Entry> mixed = TeachBlock.parse(
                    "quokka:" + WordDict.codeFor(WordTable.LEARNED_BASE)
                            + ",nocolon,word:xx,word:12,BAD:"
                            + WordDict.codeFor(WordTable.LEARNED_BASE + 1)
                            + ",:EYzk,ok:"
                            + WordDict.codeFor(WordTable.LEARNED_BASE + 2));
            checkEq("teachblock skips malformed", 2, mixed.size());
            List<TeachBlock.Entry> many = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                many.add(new TeachBlock.Entry("wa" + capSuffix(i),
                        WordTable.LEARNED_BASE + i));
            }
            String bigBlock = TeachBlock.build(many);
            checkEq("teachblock build caps", TeachBlock.MAX_ENTRIES,
                    TeachBlock.parse(bigBlock).size());
            // Per-message teach cap: 50 entries, the rest raw.
            WordTable capT = WordTable.fromWords(WordDict.WORDS);
            StringBuilder capMsg = new StringBuilder("The dog runs near the water");
            for (int i = 0; i < 60; i++) {
                String w = "zb" + capSuffix(i);
                capT.learn(w);
                capMsg.append(" ").append(w);
            }
            capMsg.append(" and the horse runs near the house today");
            WordDict.setActiveTable(capT);
            WordDict.Encoded capE = WordDict.encodeScoped(
                    capMsg.toString(), new HashSet<Integer>());
            check("teach cap block present", capE != null && !capE.teach.isEmpty());
            checkEq("teach cap 50", WordDict.TEACH_PER_MESSAGE,
                    capE == null ? -1 : TeachBlock.parse(capE.teach).size());
            // Teaching rides short messages too (v1.32:
            // every message codes, and the teach block
            // rides the coded shape).
            WordTable pt = WordTable.fromWords(WordDict.WORDS);
            pt.learn("quokka");
            WordDict.setActiveTable(pt);
            WordDict.Encoded pe = WordDict.encodeScoped("quokka", new HashSet<Integer>());
            check("short message teaches",
                    pe != null && pe.wire != null && !pe.teach.isEmpty());
            WordTable pr = WordTable.fromWords(WordDict.WORDS);
            WordDict.setActiveTable(pr);
            WordDict.Result prr = WordDict.decodeCoded(pe.wire, pe.teach);
            checkEq("short teach decodes", "quokka", prr.text);
            check("short teach adopts", pr.learnedIndex("quokka") != null);
            // Oversized teach blocks are ignored whole.
            StringBuilder huge = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                if (i > 0) huge.append(',');
                huge.append("wa").append(capSuffix(i)).append(':')
                        .append(WordDict.codeFor(WordTable.LEARNED_BASE + i));
            }
            check("oversized block ignored",
                    WordDict.applyTeach(huge.toString()).isEmpty());
        } finally {
            WordDict.setActiveTable(saved);
        }
    }

    private static void taughtStore() {
        TaughtStore ts = new TaughtStore();
        check("validScope ok", TaughtStore.validScope("open:12"));
        check("validScope chat", TaughtStore.validScope("chat:abcdef01"));
        check("validScope rejects space", !TaughtStore.validScope("a b"));
        check("validScope rejects newline", !TaughtStore.validScope("a\nb"));
        check("validScope rejects empty", !TaughtStore.validScope(""));
        check("validScope rejects null", !TaughtStore.validScope(null));
        StringBuilder sb257 = new StringBuilder();
        for (int i = 0; i < 257; i++) sb257.append('x');
        check("validScope rejects long", !TaughtStore.validScope(sb257.toString()));
        Set<Integer> one = new HashSet<>();
        one.add(WordTable.LEARNED_BASE);
        check("markTaught new", ts.markTaught("open:12", one));
        check("markTaught again false", !ts.markTaught("open:12", one));
        checkEq("forScope", one, ts.forScope("open:12"));
        Set<Integer> copy = ts.forScope("open:12");
        copy.add(999);
        check("forScope copy", !ts.forScope("open:12").contains(999));
        check("forScope unknown empty", ts.forScope("open:99").isEmpty());
        check("markTaught invalid scope false", !ts.markTaught("a b", one));
        Set<Integer> two = new HashSet<>();
        two.add(WordTable.LEARNED_BASE + 1);
        two.add(WordTable.LEARNED_BASE + 2);
        ts.markTaught("chat:abcdef01", two);
        String ser = ts.serialize();
        TaughtStore ts2 = new TaughtStore();
        ts2.load(ser);
        checkEq("taught serialize round-trip", one, ts2.forScope("open:12"));
        checkEq("taught serialize round-trip 2", two, ts2.forScope("chat:abcdef01"));
        checkEq("taught serialize stable", ser, ts2.serialize());
        TaughtStore ts3 = new TaughtStore();
        ts3.load("garbage\nopen:12\t5000000,xyz,12\nbad scope\t5000000\nopen:13\t\n");
        checkEq("taught load skips garbage", one, ts3.forScope("open:12"));
        check("taught load skips bad scope", ts3.forScope("bad scope").isEmpty());
        TaughtStore cap = new TaughtStore();
        for (int i = 0; i < TaughtStore.MAX_SCOPES + 50; i++) {
            cap.markTaught("s" + i, one);
        }
        int lines = 0;
        for (String line : cap.serialize().split("\n")) {
            if (!line.isEmpty()) lines++;
        }
        checkEq("taught scope cap", TaughtStore.MAX_SCOPES, lines);
    }

    private static final String FIXTURE_SHA =
            "5cdf9205b7244ad07c2fe181b8abf58c40f9752fdfaf7f31f61f659bb47dd895";
    private static final int FIXTURE_COUNT = 370164;

    private static void fullList() {
        WordTable saved = WordDict.activeTable();
        try {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(Paths.get("harness/dictionary.txt"));
            } catch (Exception e) {
                check("full list fixture present", false);
                return;
            }
            check("full list fixture present", bytes.length > 0);
            checkEq("full list sha matches shipped file", FIXTURE_SHA, sha256Hex(bytes));
            byte[] blob = DictFile.build(bytes);
            check("full list builds", blob != null);
            if (blob == null) return;
            byte[] blob2 = DictFile.build(bytes);
            check("full list build deterministic", Arrays.equals(blob, blob2));
            DictFile.Parsed parsed = DictFile.parse(blob);
            check("full list parses", parsed != null);
            if (parsed == null) return;
            checkEq("full list count", FIXTURE_COUNT, parsed.count());
            // The frozen head: the first 1200 entries are the
            // v1 list, in order, byte for byte.
            boolean headOk = true;
            for (int i = 0; i < WordDict.size(); i++) {
                if (!WordDict.wordAt(i).equals(parsed.wordAt(i))) {
                    headOk = false;
                    break;
                }
            }
            check("full list frozen head", headOk);
            // Full index -> code -> index round trip.
            boolean codesOk = true;
            for (int i = 0; i < parsed.count(); i++) {
                String code = WordDict.codeFor(i);
                int[] p = WordDict.parseCode(code, 0);
                if (p == null || p[0] != i || p[1] != code.length()) {
                    codesOk = false;
                    break;
                }
            }
            check("full list index/code round-trip", codesOk);
            // Full word resolution: every word resolves to an
            // index holding the same word (first-wins among
            // duplicate lowercase forms).
            WordTable full = new WordTable(parsed);
            boolean resolveOk = true;
            for (int i = 0; i < parsed.count(); i++) {
                String w = parsed.wordAt(i);
                Integer j = full.baseIndex(w.toLowerCase(Locale.ROOT));
                if (j == null || !parsed.wordAt(j).equalsIgnoreCase(w)) {
                    resolveOk = false;
                    break;
                }
            }
            check("full list word resolution", resolveOk);
            // Sampled messages with tail words round-trip.
            WordDict.setActiveTable(full);
            int[] tailIdx = {2000, 50000, 150000, 300000, FIXTURE_COUNT - 1};
            StringBuilder tailMsg = new StringBuilder("The dog runs near the water");
            for (int idx : tailIdx) {
                tailMsg.append(" ").append(parsed.wordAt(idx).toLowerCase(Locale.ROOT));
            }
            tailMsg.append(" and the horse runs near the house today");
            String tailText = tailMsg.toString();
            WordDict.Encoded te = WordDict.encodeScoped(tailText, new HashSet<Integer>());
            check("tail message codes", te != null && te.wire != null);
            if (te != null && te.wire != null) {
                checkEq("tail message version 2", 2, te.version);
                WordDict.Result tr = WordDict.decodeMarked(te.wire, te.version, te.teach);
                checkEq("tail message round-trips", tailText, tr.text);
                check("tail message note null", tr.note == null);
                int raw = tailText.getBytes(StandardCharsets.UTF_8).length;
                int w = te.wire.getBytes(StandardCharsets.UTF_8).length;
                System.out.println("SAVINGS tail: raw=" + raw + " wire=" + w);
                check("tail message saves", w < raw);
            }
            // v1 payloads decode identically under the full table.
            WordTable builtin = WordTable.fromWords(WordDict.WORDS);
            WordDict.setActiveTable(builtin);
            String v1msg = "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.";
            String v1wire = WordDict.encode(v1msg);
            check("v1 fixture codes", v1wire != null);
            if (v1wire != null) {
                WordDict.Result underBuiltin = WordDict.decode(v1wire, 1);
                WordDict.setActiveTable(full);
                WordDict.Result underFull = WordDict.decode(v1wire, 1);
                checkEq("v1 payload identical under full table",
                        underBuiltin.text, underFull.text);
                check("v1 payload note null under full", underFull.note == null);
            }
        } finally {
            WordDict.setActiveTable(saved);
        }
    }

    // ---- v1.32 legs ----

    private static void encodeAll() {
        // Every non-empty message codes, short ones
        // included: 1-char, whitespace-only, two words.
        check("1-char codes", WordDict.encode("x") != null);
        check("whitespace codes", WordDict.encode("  \t ") != null);
        check("two-word codes", WordDict.encode("hi you") != null);
        WordDict.Encoded scoped = WordDict.encodeScoped(
                "hi", new HashSet<Integer>());
        check("scoped short codes",
                scoped != null && scoped.wire != null);
        // The wire may grow for short messages now — the
        // v1.28 guarantee is gone by Adam's choice — but
        // it round-trips through the strict decoder.
        String hiWire = WordDict.encode("hi");
        WordDict.Result hi = WordDict.decodeCoded(hiWire, null);
        check("short strict round-trip",
                hi.note == null && "Hi".equals(hi.text));
        // Strict decode refuses adversarial plain text
        // that merely resembles codes: bare letters are
        // not literals, so "hello" can never mis-decode.
        String[] hostile = {
                "hello",            // bare letters
                "hi there friend",  // plain prose
                "a0b1",             // valid codes + trailing junk? no: parses as codes
                "a0!",              // code + literal: parses
                "a0\uD83D\uDE00",    // emoji outside an escape
                "a0\u0001",         // control byte outside an escape
                "a.",               // symbol + non-digit
                "~05ab",            // escape overrun
                "z",                // lone symbol, no digit
                "a0b",              // trailing lone symbol
        };
        // "a0b1" and "a0!" parse as valid coded form
        // (codes + literal), so they decode cleanly; the
        // rest must refuse with the corrupt note.
        for (String h : hostile) {
            WordDict.Result r = WordDict.decodeCoded(h, null);
            if (h.equals("a0b1") || h.equals("a0!")) {
                check("strict parses valid: " + h, r.note == null);
            } else {
                check("strict refuses: " + h,
                        r.note != null && h.equals(r.text));
            }
        }
        // Lenient (legacy) decode still passes stray
        // characters through exactly as v1.28 did.
        WordDict.Result lenient = WordDict.decode(
                "a0\uD83D\uDE00", 1);
        check("lenient passes emoji through", lenient.note == null
                && lenient.text.contains("\uD83D\uDE00"));
        // decodeCoded never throws.
        try {
            WordDict.decodeCoded(null, null);
            WordDict.decodeCoded("\u0000\u0001", null);
            WordDict.decodeCoded("~~~~~~", null);
            check("decodeCoded no throw", true);
        } catch (Exception e) {
            check("decodeCoded no throw", false);
        }
        check("decodeCoded null noted",
                WordDict.decodeCoded(null, null).note != null);
        // The escape-only fallback round-trips exactly,
        // including long runs (chunked at 99).
        String raw = "Quokka! 123 ~tilde~ \uD83D\uDE00 and more words here";
        String esc = WordDict.escapeOnly(raw);
        WordDict.Result er = WordDict.decodeCoded(esc, null);
        checkEq("escapeOnly round-trips", raw, er.text);
        check("escapeOnly note null", er.note == null);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 250; i++) big.append('Z');
        String bigEsc = WordDict.escapeOnly(big.toString());
        checkEq("escapeOnly chunks",
                big.toString(),
                WordDict.decodeCoded(bigEsc, null).text);
        checkEq("escapeOnly empty", "", WordDict.escapeOnly(""));
        checkEq("escapeOnly null", "", WordDict.escapeOnly(null));
        // wireDisplayForm: head codes, long codes, escapes,
        // literals — and null for invalid input.
        checkEq("displayForm wire head codes",
                WordDict.displayForm(0) + " " + WordDict.displayForm(1),
                WordDict.wireDisplayForm(
                        WordDict.codeFor(0) + " " + WordDict.codeFor(1)));
        checkEq("displayForm wire long code",
                WordDict.codeFor(5000),
                WordDict.wireDisplayForm(WordDict.codeFor(5000)));
        checkEq("displayForm wire escape",
                "quokka", WordDict.wireDisplayForm("~06quokka"));
        checkEq("displayForm wire mixed",
                WordDict.displayForm(0) + " quokka!",
                WordDict.wireDisplayForm(
                        WordDict.codeFor(0) + " ~06quokka!"));
        check("displayForm wire invalid null",
                WordDict.wireDisplayForm("hello") == null);
        check("displayForm wire emoji null",
                WordDict.wireDisplayForm("a0\uD83D\uDE00") == null);
        check("displayForm wire null null",
                WordDict.wireDisplayForm(null) == null);
        checkEq("displayForm wire empty", "",
                WordDict.wireDisplayForm(""));
        // ChatEnvelope: build codes on "w", parse decodes;
        // legacy shapes still unwrap; non-envelopes pass.
        ChatEnvelope.Built cb = ChatEnvelope.build(
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.", "chat:abcdef01");
        check("chat build uses w key", cb.envelope.contains("\"w\":"));
        check("chat build no marker", !cb.envelope.contains("\"wd\""));
        check("chat build coded", cb.coded);
        ChatEnvelope.Unwrapped cu = ChatEnvelope.unwrap(cb.envelope);
        checkEq("chat round-trips",
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.", cu.text);
        check("chat note null", cu.wordNote == null);
        check("chat coded flag", cu.coded);
        checkEq("chat wire recorded", cb.wire, cu.wire);
        String chatLegacy = "{\"type\":\"text\",\"text\":\""
                + WordDict.encode("Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.")
                + "\",\"wd\":1}";
        ChatEnvelope.Unwrapped cl = ChatEnvelope.unwrap(chatLegacy);
        checkEq("chat legacy marked decodes",
                "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.", cl.text);
        check("chat legacy marked coded", cl.coded);
        ChatEnvelope.Unwrapped cp = ChatEnvelope.unwrap(
                "{\"type\":\"text\",\"text\":\"hi\"}");
        checkEq("chat legacy plain passes", "hi", cp.text);
        check("chat legacy plain uncoded", !cp.coded);
        ChatEnvelope.Unwrapped cn = ChatEnvelope.unwrap("not json at all");
        checkEq("chat non-envelope passes", "not json at all", cn.text);
        check("chat non-envelope uncoded", !cn.coded);
        // Wire-form persistence: stampWire/hasWire, and a
        // missing key reads unrecorded, never a crash.
        try {
            JSONObject fresh = WalkieStore.entry("text", "Ana", "hi", "",
                    0, "Internet", false, "sent");
            check("fresh entry has no wire", !WalkieStore.hasWire(fresh));
            check("fresh entry wire opt empty",
                    "".equals(fresh.optString("wire", "")));
            WalkieStore.stampWire(fresh, "a0", 2, true);
            check("stamped entry has wire", WalkieStore.hasWire(fresh));
            checkEq("stamped wire", "a0", fresh.optString("wire", ""));
            checkEq("stamped plainBytes", 2, fresh.optInt("plainBytes", -1));
            check("stamped coded", fresh.optBoolean("coded", false));
            WalkieStore.stampWire(null, "a0", 2, true);
            WalkieStore.stampWire(fresh, null, 2, true);
            check("stampWire null-safe", true);
            check("hasWire null-safe", !WalkieStore.hasWire(null));
        } catch (Exception e) {
            check("wire persistence runs", false);
            e.printStackTrace(System.out);
        }
        // SentText byte counts.
        SentText st = new SentText("mid-1", "a0", "hi");
        checkEq("sentText mid", "mid-1", st.mid);
        checkEq("sentText wire", "a0", st.wire);
        checkEq("sentText wireBytes", 2, st.wireBytes);
        checkEq("sentText plainBytes", 2, st.plainBytes);
        SentText stu = new SentText("m", "~06quokka", "quokka");
        checkEq("sentText unicode plainBytes", 6, stu.plainBytes);
        SentText ste = new SentText("m", "a0", "\uD83D\uDE00");
        checkEq("sentText emoji plainBytes", 4, ste.plainBytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            char[] hex = "0123456789abcdef".toCharArray();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(hex[(b >> 4) & 0xf]).append(hex[b & 0xf]);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void versions() {
        WordTable saved = WordDict.activeTable();
        try {
            WordTable builtin = WordTable.fromWords(WordDict.WORDS);
            WordDict.setActiveTable(builtin);
            // v1 payloads reject long codes and tail indices.
            WordDict.Result longV1 = WordDict.decode(WordDict.codeFor(1240), 1);
            checkEq("v1 rejects long code", WordDict.NOTE_CORRUPT, longV1.note);
            WordDict.Result tailV1 = WordDict.decode(WordDict.codeFor(1200), 1);
            checkEq("v1 rejects tail index", WordDict.NOTE_CORRUPT, tailV1.note);
            // The newer/untaught distinction under the
            // built-in table: a tail code means a newer list,
            // a learned code means an untaught word.
            WordDict.Result newer = WordDict.decode(WordDict.codeFor(5000), 2);
            checkEq("tail code newer note", WordDict.NOTE_NEWER, newer.note);
            checkEq("tail code keeps raw", WordDict.codeFor(5000), newer.text);
            WordDict.Result untaught = WordDict.decode(
                    WordDict.codeFor(WordTable.LEARNED_BASE), 2);
            checkEq("learned code untaught note", WordDict.NOTE_UNTAUGHT, untaught.note);
            // Version 3+ is newer, whatever the payload.
            WordDict.Result v3 = WordDict.decode("a0", 3);
            check("v3 newer note", v3.note != null && v3.note.contains("v3"));
            // Version 1 still decodes head-only payloads.
            String wire = WordDict.encode(
                    "Hello world, this is a test of the word dictionary and I hope it works well for everyone here today.");
            check("v1 fixture codes", wire != null);
            if (wire != null) {
                WordDict.Result r1 = WordDict.decode(wire, 1);
                check("v1 decodes under v2 codec", r1.note == null);
                WordDict.Result r2 = WordDict.decode(wire, 2);
                checkEq("v1 wire same under v2", r1.text, r2.text);
            }
        } finally {
            WordDict.setActiveTable(saved);
        }
    }

    private static void submit() {
        // The submittable shape mirrors the server exactly.
        check("submittable plain", DictSubmit.submittable("quokka"));
        check("submittable contraction", DictSubmit.submittable("don't"));
        check("submittable o'clock", DictSubmit.submittable("o'clock"));
        check("submittable two chars", DictSubmit.submittable("ab"));
        check("submittable thirty chars", DictSubmit.submittable("abcdefghijabcdefghijabcdefghij"));
        check("unsubmittable one char", !DictSubmit.submittable("a"));
        check("unsubmittable thirty-one", !DictSubmit.submittable("abcdefghijabcdefghijabcdefghijk"));
        check("unsubmittable caps", !DictSubmit.submittable("Quokka"));
        check("unsubmittable all caps", !DictSubmit.submittable("BAD"));
        check("unsubmittable trailing apos", !DictSubmit.submittable("trailing'"));
        check("unsubmittable leading apos", !DictSubmit.submittable("'lead"));
        check("unsubmittable double apos", !DictSubmit.submittable("o''clock"));
        check("unsubmittable dash", !DictSubmit.submittable("with-dash"));
        check("unsubmittable digit", !DictSubmit.submittable("with1"));
        check("unsubmittable space", !DictSubmit.submittable("has space"));
        check("unsubmittable non-ascii", !DictSubmit.submittable("café"));
        check("unsubmittable empty", !DictSubmit.submittable(""));
        check("unsubmittable null", !DictSubmit.submittable(null));

        long now = 1_700_000_000_000L;
        long hour = DictSubmit.SUBMIT_INTERVAL_MS;
        List<String> learned = Arrays.asList("quokka", "wombat", "numbat");
        Set<String> none = new HashSet<>();

        // Due when never submitted and words qualify, in order.
        DictSubmit.Plan p0 = DictSubmit.plan(learned, none, now, 0L, 100);
        check("plan due first time", p0.due);
        checkEq("plan batch order", learned, p0.batch);
        // The throttle paces attempts, not just successes.
        DictSubmit.Plan p1 = DictSubmit.plan(learned, none, now, now - hour + 1000, 100);
        check("plan throttled inside hour", !p1.due);
        check("plan throttled batch empty", p1.batch.isEmpty());
        DictSubmit.Plan p2 = DictSubmit.plan(learned, none, now, now - hour, 100);
        check("plan due at exact hour", p2.due);
        DictSubmit.Plan p3 = DictSubmit.plan(learned, none, now, now + 60000, 100);
        check("plan future last-submit not due", !p3.due);
        // Submitted words never repeat; unsubmittable skipped.
        Set<String> some = new HashSet<>(Arrays.asList("quokka"));
        DictSubmit.Plan p4 = DictSubmit.plan(learned, some, now, 0L, 100);
        checkEq("plan skips submitted", Arrays.asList("wombat", "numbat"), p4.batch);
        Set<String> all = new HashSet<>(learned);
        DictSubmit.Plan p5 = DictSubmit.plan(learned, all, now, 0L, 100);
        check("plan nothing new not due", !p5.due);
        List<String> mixed = Arrays.asList("quokka", "TOOLONGWORDTHATISOVERTHIRTYCHARSXX", "wombat");
        DictSubmit.Plan p6 = DictSubmit.plan(mixed, none, now, 0L, 100);
        checkEq("plan skips unsubmittable", Arrays.asList("quokka", "wombat"), p6.batch);
        List<String> dupes = Arrays.asList("quokka", "quokka", "wombat");
        DictSubmit.Plan p7 = DictSubmit.plan(dupes, none, now, 0L, 100);
        checkEq("plan dedupes batch", Arrays.asList("quokka", "wombat"), p7.batch);
        // Caps: the server's 100, clamped both ways.
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) many.add("word" + (char) ('a' + (i / 26)) + (char) ('a' + (i % 26)));
        DictSubmit.Plan p8 = DictSubmit.plan(many, none, now, 0L, 100);
        checkEq("plan caps at 100", 100, p8.batch.size());
        checkEq("plan cap keeps order", many.subList(0, 100), p8.batch);
        DictSubmit.Plan p9 = DictSubmit.plan(many, none, now, 0L, 500);
        checkEq("plan clamps huge cap", 100, p9.batch.size());
        DictSubmit.Plan p10 = DictSubmit.plan(many, none, now, 0L, 0);
        checkEq("plan clamps zero cap", 1, p10.batch.size());
        // Empty / null input: never due, never throws.
        DictSubmit.Plan p11 = DictSubmit.plan(new ArrayList<String>(), none, now, 0L, 100);
        check("plan empty learned not due", !p11.due);
        DictSubmit.Plan p12 = DictSubmit.plan(null, null, now, 0L, 100);
        check("plan null input not due", !p12.due);
        // Inputs are never mutated.
        checkEq("plan input unmutated", Arrays.asList("quokka", "wombat", "numbat"), learned);
        checkEq("plan submitted unmutated", 1, some.size());

        // The proposal body: exact JSON, escaped, parseable.
        checkEq("proposal body exact",
                "{\"words\":[\"quokka\",\"don't\"]}",
                DictSubmit.proposalBody(Arrays.asList("quokka", "don't")));
        checkEq("proposal body empty", "{\"words\":[]}",
                DictSubmit.proposalBody(new ArrayList<String>()));
        checkEq("proposal body null", "{\"words\":[]}",
                DictSubmit.proposalBody(null));
        String tricky = DictSubmit.proposalBody(Arrays.asList("a\"b", "c\\d"));
        checkEq("proposal body escapes", "{\"words\":[\"a\\\"b\",\"c\\\\d\"]}", tricky);
        try {
            JSONObject parsed = new JSONObject(
                    DictSubmit.proposalBody(Arrays.asList("quokka", "wombat")));
            checkEq("proposal body parses", 2, parsed.getJSONArray("words").length());
            checkEq("proposal body word 0", "quokka", parsed.getJSONArray("words").getString(0));
        } catch (Exception e) {
            check("proposal body parses", false);
        }
    }

    private static void merge() {
        WordTable saved = WordDict.activeTable();
        try {
            // The dictionary.txt BEFORE the merge: quokka and
            // wombat are unknown words.
            String[] baseWords = {"the", "and", "dog", "run", "runs", "near",
                    "water", "after", "horse", "house", "quick", "brown",
                    "fox", "lazy", "pack", "box", "with", "five", "dozen",
                    "liquor", "jugs"};
            StringBuilder before = new StringBuilder();
            for (String w : baseWords) before.append(w).append('\n');
            byte[] blobA = DictFile.build(
                    before.toString().getBytes(StandardCharsets.UTF_8));
            check("merge blob A builds", blobA != null);
            DictFile.Parsed parsedA = DictFile.parse(blobA);
            checkEq("merge table A count", 21, parsedA.count());
            WordTable tableA = new WordTable(parsedA);
            WordDict.setActiveTable(tableA);

            String msg = "the quokka and the dog run near the water and the quokka runs after the horse";
            int added = tableA.learnFromText(msg + " wombat");
            checkEq("merge learns two words", 2, added);
            int quokkaIdx = tableA.learnedIndex("quokka");
            int wombatIdx = tableA.learnedIndex("wombat");
            check("merge learned indices ordered", quokkaIdx < wombatIdx);
            check("merge learned above base", quokkaIdx >= WordTable.LEARNED_BASE);

            // A scope taught the learned code, as the teach
            // flow would leave it.
            TaughtStore taught = new TaughtStore();
            taught.markTaught("peer:abc", new HashSet<>(Arrays.asList(quokkaIdx)));
            String taughtBefore = taught.serialize();

            // On table A the message uses the learned code.
            List<TeachBlock.Entry> teachA = new ArrayList<>();
            WordDict.WireBuild wireA = WordDict.buildWireScoped(msg, tableA,
                    taught.forScope("peer:abc"), teachA, true);
            checkEq("merge pre-merge uses learned code", quokkaIdx, wireA.maxIndex);
            check("merge pre-merge needs no teach", teachA.isEmpty());
            WordDict.Result decodedA = WordDict.decode(wireA.wire, 2);
            check("merge pre-merge decodes clean", decodedA.note == null);

            // THE MERGE: quokka arrives in a fresh
            // dictionary.txt, appended after the same head.
            StringBuilder after = new StringBuilder(before);
            after.append("quokka\n");
            byte[] blobB = DictFile.build(
                    after.toString().getBytes(StandardCharsets.UTF_8));
            DictFile.Parsed parsedB = DictFile.parse(blobB);
            checkEq("merge table B count", 22, parsedB.count());
            WordTable tableB = new WordTable(parsedB);
            tableB.restoreLearned(tableA.snapshotLearned());
            WordDict.setActiveTable(tableB);

            checkEq("merge base index", 21, (int) tableB.baseIndex("quokka"));
            check("merge quokka no longer learned", tableB.learnedIndex("quokka") == null);
            checkEq("merge wombat keeps code", wombatIdx, (int) tableB.learnedIndex("wombat"));
            checkEq("merge learned count drops", 1, tableB.learnedCount());

            // Sends prefer the base code; no teach block for
            // a word the base list now covers.
            List<TeachBlock.Entry> teachB = new ArrayList<>();
            WordDict.WireBuild wireB = WordDict.buildWireScoped(msg, tableB,
                    taught.forScope("peer:abc"), teachB, true);
            checkEq("merge post-merge uses base code", 21, wireB.maxIndex);
            check("merge post-merge needs no teach", teachB.isEmpty());
            check("merge wire changed", !wireA.wire.equals(wireB.wire));

            // The OLD wire (learned code) still decodes under
            // the new table — the alias rule.
            WordDict.Result decodedOld = WordDict.decode(wireA.wire, 2);
            check("merge old wire decodes clean", decodedOld.note == null);
            checkEq("merge old wire same text", decodedA.text, decodedOld.text);
            WordDict.Result viaOldCode = WordDict.decode(WordDict.codeFor(quokkaIdx), 2);
            checkEq("merge old code decodes", "Quokka", viaOldCode.text);
            check("merge old code no note", viaOldCode.note == null);

            // Taught sets survive the swap untouched.
            checkEq("merge taught store intact", taughtBefore, taught.serialize());
            check("merge taught set intact",
                    taught.forScope("peer:abc").contains(quokkaIdx));

            // The unmerged word still behaves learned: an
            // untaught scope gets it raw plus a teach entry
            // carrying the SAME code as before the merge.
            List<TeachBlock.Entry> teachW = new ArrayList<>();
            WordDict.buildWireScoped("the wombat runs", tableB,
                    new HashSet<Integer>(), teachW, true);
            checkEq("merge wombat still teaches", 1, teachW.size());
            if (!teachW.isEmpty()) {
                checkEq("merge wombat teach word", "wombat", teachW.get(0).word);
                checkEq("merge wombat teach code", wombatIdx, teachW.get(0).index);
            }
            // And a scope taught wombat's code uses it.
            List<TeachBlock.Entry> teachW2 = new ArrayList<>();
            WordDict.WireBuild wireW = WordDict.buildWireScoped("the wombat runs", tableB,
                    new HashSet<>(Arrays.asList(wombatIdx)), teachW2, true);
            checkEq("merge wombat taught uses code", wombatIdx, wireW.maxIndex);
            check("merge wombat taught no re-teach", teachW2.isEmpty());
        } finally {
            WordDict.setActiveTable(saved);
        }
    }
}
