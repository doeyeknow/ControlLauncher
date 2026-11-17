package app.control.launcher;

/**
 * The terminal transcript's scroll and trim rules (v1.21), pure
 * Java — no Android imports — so the JVM harness exercises the
 * exact shipping code.
 *
 * Adam's report (2026-11-15): "when I send a command to the
 * terminal, the text clears before I can read it" / "I just see
 * text flash." Two mechanisms fit, and the fix kills both:
 *
 * 1. The transcript lived only in an in-memory StringBuilder, so
 *    an activity recreation (rotation, any config change) wiped
 *    it back to the two header lines. The activity now saves and
 *    restores the transcript text; {@link #trim} keeps the saved
 *    form inside the same cap the live transcript obeys.
 * 2. The only bottom-scroll was a one-shot post() after each
 *    render. A layout change after that post — the keyboard
 *    showing or hiding with adjustResize — could reset the
 *    ScrollView's scroll position away from the new output, so
 *    the output flashed and was gone. The activity now re-pins
 *    to the bottom on layout changes, but ONLY while the user
 *    is at (or near) the bottom: {@link #atBottom} is the test,
 *    and a user who deliberately scrolled up to read earlier
 *    output is never yanked.
 */
public final class TranscriptScroll {

    /** Marker inserted at the head of a trimmed transcript. */
    public static final String TRIM_MARKER = "[earlier output trimmed]\n";

    /** Transcript cap: older output is trimmed past this size. */
    public static final int CAP = 120_000;
    /** Characters kept when the transcript is trimmed. */
    public static final int KEEP = 100_000;

    private TranscriptScroll() {}

    /**
     * Whether a scroll position counts as "at the bottom": the
     * visible window's end reaches the content's end within
     * {@code slopPx}. Content shorter than the viewport is
     * always at the bottom (there is nowhere to scroll). A
     * negative slop is treated as zero.
     */
    public static boolean atBottom(int scrollY, int contentHeight,
                                   int viewportHeight, int slopPx) {
        int slop = Math.max(0, slopPx);
        int end = scrollY + viewportHeight;
        return end + slop >= contentHeight;
    }

    /**
     * The transcript trim, identical to the rule the live
     * transcript has always applied when appending past the cap:
     * text at or under {@code cap} is returned unchanged; longer
     * text keeps its last {@code keep} characters with the trim
     * marker at the head. A second trim of an already-trimmed
     * transcript is a no-op whenever keep + marker fits the cap,
     * as the shipping constants do (100,000 + 26 &lt; 120,000).
     */
    public static String trim(String text, int cap, int keep) {
        if (text == null) return "";
        if (text.length() <= cap) return text;
        int k = Math.max(0, Math.min(keep, text.length()));
        return TRIM_MARKER + text.substring(text.length() - k);
    }
}
