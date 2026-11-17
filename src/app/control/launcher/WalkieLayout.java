package app.control.launcher;

/**
 * The walkie page's fixed layout, as numbers (v1.23).
 *
 * Adam's order: the walkie page should look like a real
 * walkie-talkie, and it must not scroll (the v1.20 rule
 * stands). The page is a fixed vertical column — header, the
 * radio body, the access row — and the body holds the display
 * window, the channel knob, the status strip, the speaker
 * grille, and the talk button. NOTHING on the face scrolls:
 * the message thread lives in the messages panel (a dialog),
 * where its list scrolls internally, and the switches, roads,
 * and subscription controls live in the radio menu (also a
 * dialog). The model-download block moved off the face with
 * them, so the v1.20 known corner — the download block
 * clipping the roads row on a 360x640 screen — is gone: the
 * face fits the smallest supported height in every state.
 *
 * The grille absorbs the slack: it takes whatever height is
 * left after the fixed chrome, down to a floor, and its drawn
 * pattern stays centered in the area instead of stretching,
 * so the radio keeps its proportions on any height.
 *
 * This class owns those heights in ONE place: the layout code
 * in WalkiePage builds to these numbers, and
 * WalkieLayoutHarness proves the arithmetic (the face fits
 * 360x640 with the system bars in every status state, and
 * roomier heights leave the grille room to breathe). The
 * numbers are conservative estimates of the real measured
 * heights (text metrics vary a few dp by device); the grille
 * absorbs the slack.
 *
 * Pure Java — no Android imports — so the harness exercises
 * the exact shipping constants.
 */
public final class WalkieLayout {

    /** Page padding, top + bottom. */
    public static final int PAGE_PAD_TOP_DP = 8;
    public static final int PAGE_PAD_BOTTOM_DP = 4;

    /** Header row: title + All channels + Configure (compact buttons). */
    public static final int HEADER_DP = 32;

    /** Radio body padding, top + bottom (inside the body). */
    public static final int BODY_PAD_TOP_DP = 10;
    public static final int BODY_PAD_BOTTOM_DP = 10;

    /** Display window: the channel number row + the indicators
     *  row (road/signal, mode, unplayed count), including its
     *  internal padding. Tap = direct tune. */
    public static final int DISPLAY_DP = 88;

    /** The channel knob row: CH −, the knurled tuner band,
     *  CH +. The band keeps the tuner's sweepable height. */
    public static final int KNOB_DP = 56;

    /** Status strip: top margin + one line each for status,
     *  talker, members. Each line is capped at one line
     *  (ellipsized), so the strip is bounded at
     *  STATUS_MARGIN + 3 * STATUS_LINE. */
    public static final int STATUS_MARGIN_DP = 6;
    public static final int STATUS_LINE_DP = 18;
    public static final int MAX_STATUS_LINES = 3;

    /** The speaker grille never shrinks below this: the slot
     *  group needs five rows to read as a grille. Above the
     *  floor it takes the slack (see grilleHeightDp). */
    public static final int GRILLE_MIN_DP = 40;

    /** The hold-to-talk disc — the dominant control on the
     *  face — plus its top margin. */
    public static final int PTT_SIDE_DP = 128;
    public static final int PTT_MARGIN_DP = 8;

    /** Access row: Messages, Menu, Scan (compact buttons),
     *  plus its top margin. */
    public static final int ACCESS_ROW_DP = 36;
    public static final int ACCESS_MARGIN_DP = 6;

    private WalkieLayout() {}

    /** Fixed face height for a state: statusLines visible
     *  status lines (0..3), the grille at its floor. */
    public static int faceTotalDp(int statusLines) {
        int lines = Math.max(0, Math.min(MAX_STATUS_LINES, statusLines));
        return PAGE_PAD_TOP_DP + PAGE_PAD_BOTTOM_DP
                + HEADER_DP
                + BODY_PAD_TOP_DP + BODY_PAD_BOTTOM_DP
                + DISPLAY_DP
                + KNOB_DP
                + STATUS_MARGIN_DP + lines * STATUS_LINE_DP
                + GRILLE_MIN_DP
                + PTT_SIDE_DP + PTT_MARGIN_DP
                + ACCESS_ROW_DP + ACCESS_MARGIN_DP;
    }

    /** Height the grille takes at the given usable page
     *  height: the slack after the fixed chrome, never below
     *  its floor. */
    public static int grilleHeightDp(int usableHeightDp, int statusLines) {
        int fixedWithoutGrille = faceTotalDp(statusLines) - GRILLE_MIN_DP;
        return Math.max(GRILLE_MIN_DP, usableHeightDp - fixedWithoutGrille);
    }

    /** True when the face fits: nothing is pushed
     *  off-screen in this state at this height. */
    public static boolean fits(int usableHeightDp, int statusLines) {
        return faceTotalDp(statusLines) <= usableHeightDp;
    }
}
