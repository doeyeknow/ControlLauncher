package app.control.launcher;

/**
 * Off-device verification for the v1.21 terminal transcript fix,
 * exercising the exact shipping rules (TranscriptScroll):
 *
 * Legs:
 *   1. AT_BOTTOM: the pin test — exact bottom, one pixel above
 *      it (out, without slop), inside the slop (in), exactly at
 *      the slop boundary (in), one pixel past it (out), content
 *      shorter than the viewport (always in), empty content,
 *      the top and middle of long content (out), a negative
 *      slop (treated as zero), and an overscrolled position.
 *   2. TRIM: the shared cap rule — under the cap unchanged,
 *      exactly at the cap unchanged, one over the cap trimmed
 *      to marker + last KEEP characters, a long transcript
 *      keeping exactly its tail, null/empty safe, keep 0
 *      keeping the marker only, and trim stability: trimming
 *      an already-trimmed transcript is a no-op, so the
 *      save/restore path can never shrink output twice.
 *   3. SHIPPING CONSTANTS: KEEP + marker fits inside CAP — the
 *      precondition trim stability relies on — with the exact
 *      values the activity applies.
 *
 * The Android wiring the harness cannot see (the scroll/layout
 * listeners, onSaveInstanceState/onCreate restore, the
 * pin-captured-before-setText render) is verified by the build
 * and by the on-device checklist in BUILD_NOTES; this harness
 * pins every rule that wiring depends on.
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/TerminalScrollHarness.java
 *   java -cp harness/classes app.control.launcher.TerminalScrollHarness
 * No Android classes, no network.
 */
public final class TerminalScrollHarness {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        atBottom();
        trim();
        shippingConstants();
        System.out.println("TerminalScrollHarness: " + (checks - failures)
                + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    // ---- leg 1: atBottom ----

    private static void atBottom() {
        // Viewport 500, content 2000: max scrollY is 1500.
        check("exact bottom", TranscriptScroll.atBottom(1500, 2000, 500, 0));
        check("one px above, no slop", !TranscriptScroll.atBottom(1499, 2000, 500, 0));
        check("within slop", TranscriptScroll.atBottom(1495, 2000, 500, 8));
        check("exactly at slop boundary",
                TranscriptScroll.atBottom(1492, 2000, 500, 8));
        check("one px past slop", !TranscriptScroll.atBottom(1491, 2000, 500, 8));
        check("top of long content", !TranscriptScroll.atBottom(0, 2000, 500, 8));
        check("middle of long content", !TranscriptScroll.atBottom(700, 2000, 500, 8));
        check("short content always at bottom",
                TranscriptScroll.atBottom(0, 300, 500, 0));
        check("content exactly viewport height",
                TranscriptScroll.atBottom(0, 500, 500, 0));
        check("empty content", TranscriptScroll.atBottom(0, 0, 500, 0));
        check("negative slop treated as zero",
                !TranscriptScroll.atBottom(1499, 2000, 500, -8));
        check("overscrolled past bottom",
                TranscriptScroll.atBottom(1600, 2000, 500, 0));
        check("zero viewport, empty content",
                TranscriptScroll.atBottom(0, 0, 0, 0));
    }

    // ---- leg 2: trim ----

    private static void trim() {
        String cap = TranscriptScroll.CAP + "";
        String keep = TranscriptScroll.KEEP + "";
        String marker = TranscriptScroll.TRIM_MARKER;

        check("short text unchanged",
                TranscriptScroll.trim("hello", TranscriptScroll.CAP,
                        TranscriptScroll.KEEP).equals("hello"));
        check("empty unchanged",
                TranscriptScroll.trim("", TranscriptScroll.CAP,
                        TranscriptScroll.KEEP).equals(""));
        check("null safe",
                TranscriptScroll.trim(null, TranscriptScroll.CAP,
                        TranscriptScroll.KEEP).equals(""));

        String exact = repeat("x", TranscriptScroll.CAP);
        check("exactly at cap unchanged",
                TranscriptScroll.trim(exact, TranscriptScroll.CAP,
                        TranscriptScroll.KEEP).equals(exact));

        String over = repeat("a", TranscriptScroll.CAP + 1);
        String trimmed = TranscriptScroll.trim(over, TranscriptScroll.CAP,
                TranscriptScroll.KEEP);
        check("one over cap: marker at head", trimmed.startsWith(marker));
        check("one over cap: keeps KEEP chars",
                trimmed.length() == marker.length() + TranscriptScroll.KEEP);
        check("one over cap: tail is the original tail",
                trimmed.substring(marker.length()).equals(repeat("a", TranscriptScroll.KEEP)));

        // A long transcript with a distinct tail: the kept text
        // must be the LAST keep characters, nothing else.
        StringBuilder sb = new StringBuilder();
        sb.append("HEAD-MARKER-UNIQUE-0123456789");
        for (int i = 0; i < TranscriptScroll.CAP + 5000; i++) sb.append((char) ('a' + (i % 26)));
        String longText = sb.toString();
        String longTrimmed = TranscriptScroll.trim(longText,
                TranscriptScroll.CAP, TranscriptScroll.KEEP);
        check("long: marker at head", longTrimmed.startsWith(marker));
        check("long: length", longTrimmed.length() == marker.length() + TranscriptScroll.KEEP);
        check("long: keeps the tail",
                longTrimmed.substring(marker.length())
                        .equals(longText.substring(longText.length() - TranscriptScroll.KEEP)));
        check("long: no earlier content leaks",
                !longTrimmed.contains("HEAD-MARKER-UNIQUE-0123456789"));

        // Stability: trimming twice changes nothing the second
        // time, so save + restore + save can never eat output.
        check("trim is stable",
                TranscriptScroll.trim(longTrimmed, TranscriptScroll.CAP,
                        TranscriptScroll.KEEP).equals(longTrimmed));
        check("trim is stable (one-over)",
                TranscriptScroll.trim(trimmed, TranscriptScroll.CAP,
                        TranscriptScroll.KEEP).equals(trimmed));

        check("keep 0 keeps marker only",
                TranscriptScroll.trim(repeat("z", 50), 10, 0).equals(marker));
        check("keep larger than text keeps whole tail",
                TranscriptScroll.trim("abcdefghij", 5, 100).equals(marker + "abcdefghij"));
        check("cap/keep literals sane", cap.equals("120000") && keep.equals("100000"));
    }

    // ---- leg 3: shipping constants ----

    private static void shippingConstants() {
        check("KEEP + marker fits CAP",
                TranscriptScroll.KEEP + TranscriptScroll.TRIM_MARKER.length()
                        < TranscriptScroll.CAP);
        check("CAP positive and KEEP below CAP",
                TranscriptScroll.CAP > 0
                        && TranscriptScroll.KEEP > 0
                        && TranscriptScroll.KEEP < TranscriptScroll.CAP);
        check("marker text exact",
                TranscriptScroll.TRIM_MARKER.equals("[earlier output trimmed]\n"));
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(n * s.length());
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}
