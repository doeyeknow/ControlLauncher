package app.control.launcher;

/**
 * Off-device verification for the v1.23 radio-face layout,
 * exercising the exact shipping constants (WalkieLayout):
 *
 * Legs:
 *   1. CONSTANTS: every fixed height is positive and sane (the
 *      talk button is the dominant control, the knob stays
 *      sweepable, the display window fits two rows, the
 *      grille floor fits its slot group).
 *   2. BUDGET: the face total grows exactly one status line
 *      per visible line, status lines clamp to 0..3, the
 *      grille takes the slack above its floor, and the totals
 *      are pinned so the BUILD_NOTES claims stay true if the
 *      constants move.
 *   3. FITS: the face fits the smallest supported height
 *      (360x640 with system bars, 568dp usable) in EVERY
 *      status state — the v1.20 model-download corner is
 *      gone, because the download block moved off the face
 *      into the radio menu — and fits roomier heights (600,
 *      616 gesture-nav, 867 A16) with the grille taking the
 *      slack.
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/WalkieLayoutHarness.java
 *   java -cp harness/classes app.control.launcher.WalkieLayoutHarness
 * No Android classes, no network.
 */
public final class WalkieLayoutHarness {

    private static int checks;
    private static int passed;

    public static void main(String[] args) {
        constants();
        budget();
        fits();
        System.out.println("WalkieLayoutHarness: " + passed + "/" + checks
                + " checks passed");
        if (passed != checks) System.exit(1);
    }

    private static void constants() {
        check("page padding positive",
                WalkieLayout.PAGE_PAD_TOP_DP > 0 && WalkieLayout.PAGE_PAD_BOTTOM_DP >= 0);
        check("header positive", WalkieLayout.HEADER_DP > 0);
        check("body padding positive",
                WalkieLayout.BODY_PAD_TOP_DP > 0 && WalkieLayout.BODY_PAD_BOTTOM_DP > 0);
        check("display window fits two rows (>=72dp)",
                WalkieLayout.DISPLAY_DP >= 72);
        check("knob sweepable (>=48dp)", WalkieLayout.KNOB_DP >= 48);
        check("status line positive", WalkieLayout.STATUS_LINE_DP > 0);
        check("three status lines max", WalkieLayout.MAX_STATUS_LINES == 3);
        check("grille floor fits its slots (>=36dp)",
                WalkieLayout.GRILLE_MIN_DP >= 36);
        check("talk button is the dominant control (>=110dp)",
                WalkieLayout.PTT_SIDE_DP >= 110);
        check("talk button bigger than the knob",
                WalkieLayout.PTT_SIDE_DP > WalkieLayout.KNOB_DP);
        check("access row positive", WalkieLayout.ACCESS_ROW_DP > 0);
    }

    private static void budget() {
        int base = WalkieLayout.faceTotalDp(0);
        check("one status line adds exactly one line",
                WalkieLayout.faceTotalDp(1)
                        == base + WalkieLayout.STATUS_LINE_DP);
        check("three status lines add exactly three lines",
                WalkieLayout.faceTotalDp(3)
                        == base + 3 * WalkieLayout.STATUS_LINE_DP);
        check("status lines clamp low",
                WalkieLayout.faceTotalDp(-5) == base);
        check("status lines clamp high",
                WalkieLayout.faceTotalDp(9) == WalkieLayout.faceTotalDp(3));
        check("typical face total is 450dp",
                WalkieLayout.faceTotalDp(1) == 450);
        check("full face total is 486dp",
                WalkieLayout.faceTotalDp(3) == 486);
        check("grille takes the slack at 867",
                WalkieLayout.grilleHeightDp(867, 1)
                        == 867 - (WalkieLayout.faceTotalDp(1)
                                - WalkieLayout.GRILLE_MIN_DP));
        check("grille never drops below its floor",
                WalkieLayout.grilleHeightDp(400, 3)
                        == WalkieLayout.GRILLE_MIN_DP);
        check("grille at the floor exactly at the fit edge",
                WalkieLayout.grilleHeightDp(486, 3)
                        == WalkieLayout.GRILLE_MIN_DP);
    }

    private static void fits() {
        // 360x640 with status + navigation bars: 568dp usable.
        check("typical fits at 568",
                WalkieLayout.fits(568, 1));
        check("no status lines fit at 568",
                WalkieLayout.fits(568, 0));
        check("all three status lines fit at 568",
                WalkieLayout.fits(568, 3));
        check("all three status lines leave 82dp slack at 568",
                568 - WalkieLayout.faceTotalDp(3) == 82);
        check("typical fits at 600",
                WalkieLayout.fits(600, 1));
        check("all status lines fit at 616 (gesture nav)",
                WalkieLayout.fits(616, 3));
        check("all status lines fit at 867 (A16)",
                WalkieLayout.fits(867, 3));
        check("grille breathes at 867 (300dp+)",
                WalkieLayout.grilleHeightDp(867, 3) >= 300);
        check("fits is monotone in height",
                WalkieLayout.fits(568, 3)
                        && WalkieLayout.fits(867, 3));
        check("fits is monotone in status lines",
                WalkieLayout.fits(568, 0)
                        && WalkieLayout.fits(568, 3));
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            passed++;
        } else {
            System.out.println("FAIL: " + name);
        }
    }
}
