package app.control.launcher;

/**
 * Off-device verification for the circle-menu rings, exercising
 * the exact shipping pure-Java code (RingMath):
 *
 * Legs:
 *   1. GEOMETRY: item angles start at the top and go clockwise;
 *      item positions match the angles; the chord rule that sets
 *      the page size (8 items fit at the shipping 80dp ring /
 *      24dp item radii with room to spare, the 9-item main ring
 *      clears, 11 would overlap); hub clearance; hit testing
 *      (on-item, center, between items, beyond the hit radius).
 *   2. PAGING: page counts and [start, end) ranges, including
 *      boundaries (exact multiples, one over, empty),
 *      out-of-range page clamping, and clampPage.
 *   3. DWELL: the state machine fires at exactly DWELL_MS
 *      stationary, latches, resets on movement beyond the
 *      tolerance (but not within it), never fires on a quick
 *      tap/drag, and reports linear progress. All times derive
 *      from RingMath.DWELL_MS — no second copy of the threshold.
 *   4. ROTATION (v1.9.1): clockwise past the threshold turns one
 *      page, counter-clockwise goes back, wobble / dwell /
 *      radial drags / out-of-band motion never turn, direction
 *      reversal unwinds, continuous rotation turns evenly, angle
 *      wrap is handled, and straight chords across the ring
 *      never page.
 *   5. GESTURE (v1.9.1): release semantics — dwell → morph →
 *      move → release chooses; morph → release without moving
 *      chooses nothing; moving off into empty space chooses
 *      nothing; a page-turning gesture never chooses; a plain
 *      tap/drag chooses; stationary move events never arm a
 *      choice after a morph.
 *   6. CENTERING (v1.9.2): the ring center clamps so the whole
 *      ring stays on screen — interior presses keep their exact
 *      position, all four edges and all four corners shift
 *      inward by the overshoot, and a grid sweep proves the
 *      full ring (center ± reach) stays inside the viewport.
 *      The zero-size case pins the trap that caused the
 *      top-corner bug: clamping against 0 collapses to the
 *      reach, so the view must resolve real bounds (parent /
 *      display) before calling — that resolution is Android
 *      code this harness cannot run, and is verified by
 *      inspection + the build.
 *
 * What this harness cannot prove: anything drawn on a screen
 * (the progress arc, the dots, the hub), touch arbitration with
 * the pager, haptics, or the feel of the hold and the rotation.
 * Those need Adam's phone.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac --release 17 -encoding UTF-8 -sourcepath src -d harness/classes \
 *     harness/RingMathHarness.java
 *   java -cp harness/classes app.control.launcher.RingMathHarness
 */
public final class RingMathHarness {

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

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    /** Feeds (angleDeg, radiusPx) samples to a Rotation, returns
     *  the net steps emitted. */
    private static int runRotation(RingMath.Rotation rot,
                                   float[] angles, float[] radii) {
        int steps = 0;
        for (int i = 0; i < angles.length; i++) {
            steps += rot.update(angles[i], radii[i]);
        }
        return steps;
    }

    private static RingMath.Rotation newRotation() {
        return new RingMath.Rotation(RingMath.ROTATION_THRESHOLD_DEG,
                RingMath.ROTATION_BAND_INNER_DP,
                RingMath.ROTATION_BAND_OUTER_DP,
                RingMath.ROTATION_MAX_RADIUS_RANGE_DP);
    }

    /** Angles sweeping from {@code fromDeg} to {@code toDeg} in
     *  {@code stepDeg} increments (sign follows the direction),
     *  all at one radius. */
    private static int sweep(float fromDeg, float toDeg, float stepDeg,
                             float radius) {
        RingMath.Rotation rot = newRotation();
        int steps = 0;
        float dir = Math.signum(toDeg - fromDeg);
        float a = fromDeg;
        steps += rot.update(a, radius);
        while ((dir > 0 && a < toDeg) || (dir < 0 && a > toDeg)) {
            a += dir * Math.abs(stepDeg);
            if ((dir > 0 && a > toDeg) || (dir < 0 && a < toDeg)) a = toDeg;
            steps += rot.update(a, radius);
        }
        return steps;
    }

    /** A straight chord at perpendicular distance {@code d} from
     *  the hub, traversed end to end. Returns net steps. */
    private static int chordSweep(float d, boolean vertical) {
        RingMath.Rotation rot = newRotation();
        int steps = 0;
        float outer = RingMath.ROTATION_BAND_OUTER_DP;
        for (float t = -outer; t <= outer; t += 4f) {
            float x = vertical ? d : t;
            float y = vertical ? t : -d;
            float angle = (float) Math.toDegrees(Math.atan2(y, x));
            float radius = (float) Math.sqrt(x * x + y * y);
            steps += rot.update(angle, radius);
        }
        return steps;
    }

    public static void main(String[] args) {
        final float RING = RingMath.RING_RADIUS_DP;
        final float ITEM = RingMath.ITEM_RADIUS_DP;
        final float HUB = RingMath.HUB_RADIUS_DP;
        final int P = RingMath.PAGE_SIZE;
        final double DIA = 2.0 * ITEM;

        // ---- 1. Geometry ----
        check("geometry: ring radius is 80dp", RING == 80f, "");
        check("geometry: item radius is 24dp", ITEM == 24f, "");
        check("geometry: hub radius is 37dp", HUB == 37f, "");
        check("geometry: first item at the top",
                RingMath.itemAngleDeg(0, 9) == -90.0, "");
        check("geometry: nine items 40 degrees apart",
                near(RingMath.itemAngleDeg(1, 9), -50.0, 1e-9)
                        && near(RingMath.itemAngleDeg(8, 9), 230.0, 1e-9), "");
        check("geometry: one item sits at the top",
                RingMath.itemAngleDeg(0, 1) == -90.0, "");
        check("geometry: item 0 position is straight up",
                near(RingMath.itemX(100f, RING, 0, 9), 100f, 1e-4)
                        && near(RingMath.itemY(100f, RING, 0, 9), 20f, 1e-4), "");
        double chord9 = RingMath.chord(RING, 9);
        check("geometry: 9 items (main ring) clear each other",
                chord9 > DIA, "chord=" + chord9 + " dia=" + DIA);
        double chord8 = RingMath.chord(RING, 8);
        check("geometry: 8 items clear each other with room to spare",
                chord8 > DIA + 10.0, "chord=" + chord8 + " dia=" + DIA);
        double chord11 = RingMath.chord(RING, 11);
        check("geometry: 11 items would overlap",
                chord11 < DIA, "chord=" + chord11 + " dia=" + DIA);
        check("geometry: page size is 8", P == 8, "");
        check("geometry: single item has infinite chord",
                Double.isInfinite(RingMath.chord(RING, 1)), "");
        check("geometry: hub clears the items' inner edge",
                RING - ITEM - HUB > 0, "gap=" + (RING - ITEM - HUB));
        check("geometry: item hit zones do not reach the hub",
                RING - ITEM * 1.6f > HUB, "");

        // Hit testing at the shipping radii: ring 80, item 24,
        // hit radius 24*1.6 = 38.4.
        float cx = 200f, cy = 200f, hit = ITEM * 1.6f;
        float x0 = RingMath.itemX(cx, RING, 0, 9);
        float y0 = RingMath.itemY(cy, RING, 0, 9);
        check("hit: dead on item 0",
                RingMath.hitIndex(x0, y0, cx, cy, RING, 9, hit) == 0, "");
        check("hit: center hits nothing",
                RingMath.hitIndex(cx, cy, cx, cy, RING, 9, hit) == -1, "");
        float x3 = RingMath.itemX(cx, RING, 3, 9);
        float y3 = RingMath.itemY(cy, RING, 3, 9);
        check("hit: near item 3 finds item 3",
                RingMath.hitIndex(x3 + 10, y3 - 10, cx, cy, RING, 9, hit) == 3, "");
        check("hit: far outside hits nothing",
                RingMath.hitIndex(cx + 300, cy + 300, cx, cy, RING, 9, hit) == -1, "");
        check("hit: empty ring hits nothing",
                RingMath.hitIndex(x0, y0, cx, cy, RING, 0, hit) == -1, "");
        float x1 = RingMath.itemX(cx, RING, 1, 9);
        float y1 = RingMath.itemY(cy, RING, 1, 9);
        check("hit: nearest wins between two items",
                RingMath.hitIndex((x0 + x1) / 2f + 15f, (y0 + y1) / 2f,
                        cx, cy, RING, 9, hit) == 1, "");

        // ---- 2. Paging ----
        check("paging: empty list has no pages",
                RingMath.pageCount(0, P) == 0, "");
        check("paging: 1..8 items fit one page",
                RingMath.pageCount(1, P) == 1 && RingMath.pageCount(8, P) == 1, "");
        check("paging: 9 items need two pages",
                RingMath.pageCount(9, P) == 2, "");
        check("paging: 50 channels need seven pages",
                RingMath.pageCount(50, P) == 7, "");
        check("paging: 57 items need eight pages",
                RingMath.pageCount(57, P) == 8, "");
        int[] r0 = RingMath.pageRange(0, P, 50);
        check("paging: first page is [0,8)",
                r0[0] == 0 && r0[1] == 8, "");
        int[] r6 = RingMath.pageRange(6, P, 50);
        check("paging: last page of 50 is [48,50)",
                r6[0] == 48 && r6[1] == 50, "");
        int[] rLast = RingMath.pageRange(2, P, 27);
        check("paging: partial last page is [16,24)",
                rLast[0] == 16 && rLast[1] == 24, "");
        int[] rHigh = RingMath.pageRange(99, P, 50);
        check("paging: page past the end clamps to the last page",
                rHigh[0] == 48 && rHigh[1] == 50, "");
        int[] rNeg = RingMath.pageRange(-3, P, 50);
        check("paging: negative page clamps to the first page",
                rNeg[0] == 0 && rNeg[1] == 8, "");
        int[] rEmpty = RingMath.pageRange(0, P, 0);
        check("paging: empty range is [0,0)",
                rEmpty[0] == 0 && rEmpty[1] == 0, "");
        // Every item of 1..60 appears on exactly one page.
        boolean covers = true;
        for (int total = 1; total <= 60 && covers; total++) {
            boolean[] seen = new boolean[total];
            int pages = RingMath.pageCount(total, P);
            for (int p = 0; p < pages; p++) {
                int[] range = RingMath.pageRange(p, P, total);
                for (int i = range[0]; i < range[1]; i++) {
                    if (seen[i]) covers = false;
                    seen[i] = true;
                }
            }
            for (boolean b : seen) if (!b) covers = false;
        }
        check("paging: pages 1..60 cover every item exactly once", covers, "");
        check("paging: clampPage keeps a valid page",
                RingMath.clampPage(5, 7) == 5, "");
        check("paging: clampPage clamps past the last page",
                RingMath.clampPage(99, 7) == 6, "");
        check("paging: clampPage clamps a negative page",
                RingMath.clampPage(-3, 7) == 0, "");
        check("paging: clampPage with no pages is 0",
                RingMath.clampPage(3, 0) == 0, "");
        check("paging: clampPage on a single page is 0",
                RingMath.clampPage(3, 1) == 0, "");

        // ---- 3. Dwell ----
        final long D = RingMath.DWELL_MS;
        check("dwell: duration is 1000ms", D == 1000L, "");
        RingMath.Dwell d = new RingMath.Dwell(D, 12f);
        check("dwell: no fire before tracking starts",
                !d.update(0, 0, 5000) && d.progress(5000) == 0f, "");
        d.start(0, 0, 1000);
        check("dwell: no fire 1ms early",
                !d.update(0, 0, 1000 + D - 1), "");
        check("dwell: fires at exactly DWELL_MS",
                d.update(0, 0, 1000 + D), "");
        check("dwell: latched after firing",
                d.update(0, 0, 9000) && d.isFired() && d.progress(9000) == 1f, "");
        d.reset();
        check("dwell: reset clears tracking",
                !d.isTracking() && !d.isFired() && d.progress(0) == 0f, "");

        RingMath.Dwell m = new RingMath.Dwell(D, 12f);
        m.start(0, 0, 0);
        check("dwell: movement beyond tolerance restarts the clock",
                !m.update(0, 0, D / 2) && !m.update(20, 0, D / 2)
                        && !m.update(20, 0, D / 2 + D - 1) && m.update(20, 0, D / 2 + D), "");
        RingMath.Dwell w = new RingMath.Dwell(D, 12f);
        w.start(0, 0, 0);
        check("dwell: small wobble does not restart",
                !w.update(5, 5, D / 2) && w.update(5, 5, D), "");
        RingMath.Dwell edge = new RingMath.Dwell(D, 12f);
        edge.start(0, 0, 0);
        check("dwell: movement exactly at tolerance does not restart",
                !edge.update(12, 0, D / 2) && edge.update(12, 0, D), "");
        RingMath.Dwell tap = new RingMath.Dwell(D, 12f);
        tap.start(0, 0, 0);
        check("dwell: a quick tap never fires",
                !tap.update(0, 0, 300), "");
        tap.reset();
        RingMath.Dwell drag = new RingMath.Dwell(D, 12f);
        drag.start(0, 0, 0);
        boolean dragFired = false;
        for (int t = 0; t <= 3000; t += 100) {
            if (drag.update(t / 10f, 0, t)) dragFired = true;
        }
        check("dwell: a slow steady drag never fires", !dragFired, "");
        RingMath.Dwell prog = new RingMath.Dwell(D, 12f);
        prog.start(100, 100, 500);
        check("dwell: progress is linear",
                near(prog.progress(500), 0f, 1e-6)
                        && near(prog.progress(500 + D / 2), 0.5f, 1e-6)
                        && near(prog.progress(500 + D), 1f, 1e-6), "");
        RingMath.Dwell restart = new RingMath.Dwell(D, 12f);
        restart.start(0, 0, 0);
        restart.update(0, 0, D);
        restart.start(50, 50, 5000);
        check("dwell: starting again re-arms after firing",
                !restart.isFired() && !restart.update(50, 50, 5000 + 100)
                        && restart.update(50, 50, 5000 + D), "");

        // ---- 4. Rotation ----
        check("rotation: threshold is 90 degrees",
                RingMath.ROTATION_THRESHOLD_DEG == 90f, "");
        check("rotation: band is the icon circle",
                RingMath.ROTATION_BAND_INNER_DP == RING - 16f
                        && RingMath.ROTATION_BAND_OUTER_DP == RING + ITEM, "");
        check("rotation: clockwise 90 degrees turns one page",
                sweep(0, 90, 10, 80) == 1, "");
        check("rotation: 89 degrees turns nothing",
                sweep(0, 89, 10, 80) == 0, "");
        check("rotation: counter-clockwise 90 degrees goes back",
                sweep(0, -90, 10, 80) == -1, "");
        check("rotation: one full turn is four pages",
                sweep(0, 360, 15, 80) == 4, "");
        check("rotation: two full turns are eight pages",
                sweep(0, 720, 15, 80) == 8, "");
        check("rotation: counter-clockwise full turn is minus four",
                sweep(0, -360, 15, 80) == -4, "");
        check("rotation: wobble around a point turns nothing",
                sweep(0, 5, 5, 80) + sweep(5, -5, 5, 80) == 0, "");
        {
            RingMath.Rotation hold = newRotation();
            int steps = 0;
            for (int i = 0; i < 20; i++) steps += hold.update(30f, 80f);
            check("rotation: a stationary hold turns nothing", steps == 0, "");
        }
        {
            // Radial drag: constant angle, radius 20 -> 120.
            RingMath.Rotation radial = newRotation();
            int steps = 0;
            for (float r = 20; r <= 120; r += 5) steps += radial.update(30f, r);
            check("rotation: a radial drag turns nothing", steps == 0, "");
        }
        {
            // Direction reversal unwinds: +80, back -80, then +90.
            RingMath.Rotation rev = newRotation();
            int steps = 0;
            float a = 0;
            steps += rev.update(a, 80f);
            for (int i = 0; i < 8; i++) { a += 10; steps += rev.update(a, 80f); }
            for (int i = 0; i < 8; i++) { a -= 10; steps += rev.update(a, 80f); }
            check("rotation: reversal to the start turns nothing", steps == 0, "");
            for (int i = 0; i < 9; i++) { a += 10; steps += rev.update(a, 80f); }
            check("rotation: a fresh 90 after reversal turns one page",
                    steps == 1, "steps=" + steps);
        }
        {
            // Partial reversal unwinds net: +80, -30, +40 = 90.
            RingMath.Rotation net = newRotation();
            int steps = 0;
            float a = 0;
            steps += net.update(a, 80f);
            for (int i = 0; i < 8; i++) { a += 10; steps += net.update(a, 80f); }
            for (int i = 0; i < 3; i++) { a -= 10; steps += net.update(a, 80f); }
            for (int i = 0; i < 4; i++) { a += 10; steps += net.update(a, 80f); }
            check("rotation: net displacement pages",
                    steps == 1, "steps=" + steps);
        }
        {
            // Angle wrap: sweep through +180/-180.
            RingMath.Rotation wrap = newRotation();
            int steps = 0;
            float a = 170f;
            steps += wrap.update(a, 80f);
            for (int i = 0; i < 18; i++) {
                a += 5;
                if (a > 180f) a -= 360f;
                steps += wrap.update(a, 80f);
            }
            check("rotation: sweeping through the wrap pages once",
                    steps == 1, "steps=" + steps);
        }
        check("rotation: inside the hub turns nothing",
                sweep(0, 360, 15, 40) == 0, "");
        check("rotation: beyond the ring turns nothing",
                sweep(0, 360, 15, 120) == 0, "");
        {
            // Leaving the band resets the run.
            RingMath.Rotation exit = newRotation();
            int steps = 0;
            float a = 0;
            steps += exit.update(a, 80f);
            for (int i = 0; i < 8; i++) { a += 10; steps += exit.update(a, 80f); }
            steps += exit.update(a, 30f); // out of band
            steps += exit.update(a, 80f); // back in, re-anchored
            for (int i = 0; i < 8; i++) { a += 10; steps += exit.update(a, 80f); }
            check("rotation: leaving the band resets accumulation",
                    steps == 0, "steps=" + steps);
        }
        {
            // Radius wobble while rotating still pages evenly.
            RingMath.Rotation wob = newRotation();
            int steps = 0;
            for (int i = 0; i <= 24; i++) {
                float ang = i * 15f;
                float rad = 80f + (i % 2 == 0 ? 8f : -8f);
                steps += wob.update(ang, rad);
            }
            check("rotation: radius wobble still pages four per turn",
                    steps == 4, "steps=" + steps);
        }
        check("rotation: rotating at the inner band edge pages",
                sweep(0, 360, 15, RingMath.ROTATION_BAND_INNER_DP) == 4, "");
        check("rotation: rotating at the outer band edge pages",
                sweep(0, 360, 15, RingMath.ROTATION_BAND_OUTER_DP) == 4, "");
        {
            // One big jump still needs the threshold; a stationary
            // finger never fires on a banked remainder.
            RingMath.Rotation flick = newRotation();
            int steps = flick.update(0f, 80f);
            steps += flick.update(100f, 80f);
            check("rotation: a 100-degree flick turns one page",
                    steps == 1, "steps=" + steps);
            int still = 0;
            for (int i = 0; i < 10; i++) still += flick.update(100f, 80f);
            check("rotation: a stationary finger never re-fires",
                    still == 0, "");
        }
        // Straight chords across the ring never page, at any
        // offset — horizontal and vertical.
        boolean chordsClean = true;
        String chordDetail = "";
        for (float dist : new float[] {0f, 30f, 60f, 64f, 66f, 68f, 70f, 76f, 80f, 84f, 90f, 96f, 100f}) {
            int s = chordSweep(dist, false);
            if (s != 0) { chordsClean = false; chordDetail = "d=" + dist + " steps=" + s; }
        }
        check("rotation: horizontal chords never page", chordsClean, chordDetail);
        boolean vChordsClean = true;
        String vDetail = "";
        for (float dist : new float[] {0f, 66f, 80f, 100f}) {
            int s = chordSweep(dist, true);
            if (s != 0) { vChordsClean = false; vDetail = "d=" + dist + " steps=" + s; }
        }
        check("rotation: vertical chords never page", vChordsClean, vDetail);

        // ---- 5. Gesture ----
        RingMath.Gesture g = new RingMath.Gesture(12f);
        g.begin();
        check("gesture: a plain tap/drag release chooses",
                g.releaseChooses(true), "");
        check("gesture: releasing over empty space chooses nothing",
                !g.releaseChooses(false), "");

        RingMath.Gesture morph = new RingMath.Gesture(12f);
        morph.begin();
        morph.onMorph(100, 100);
        morph.onMove(130, 100);
        check("gesture: dwell, morph, move, release chooses",
                morph.releaseChooses(true) && !morph.isMorphFresh(), "");

        RingMath.Gesture fresh = new RingMath.Gesture(12f);
        fresh.begin();
        fresh.onMorph(100, 100);
        check("gesture: morph, release without moving chooses nothing",
                !fresh.releaseChooses(true) && fresh.isMorphFresh(), "");

        RingMath.Gesture wobble = new RingMath.Gesture(12f);
        wobble.begin();
        wobble.onMorph(100, 100);
        wobble.onMove(105, 105);
        check("gesture: a small wobble after morph still chooses nothing",
                !wobble.releaseChooses(true) && wobble.isMorphFresh(), "");

        RingMath.Gesture edgeMove = new RingMath.Gesture(12f);
        edgeMove.begin();
        edgeMove.onMorph(100, 100);
        edgeMove.onMove(112, 100);
        check("gesture: movement exactly at tolerance does not arm",
                !edgeMove.releaseChooses(true) && edgeMove.isMorphFresh(), "");
        edgeMove.onMove(113, 100);
        check("gesture: movement past tolerance arms the release",
                edgeMove.releaseChooses(true) && !edgeMove.isMorphFresh(), "");

        RingMath.Gesture off = new RingMath.Gesture(12f);
        off.begin();
        off.onMorph(100, 100);
        off.onMove(160, 100);
        check("gesture: morph, move, release over empty space chooses nothing",
                !off.releaseChooses(false), "");

        RingMath.Gesture turned = new RingMath.Gesture(12f);
        turned.begin();
        turned.onPageTurn();
        check("gesture: a page-turning release never chooses",
                !turned.releaseChooses(true) && turned.pageTurned(), "");

        RingMath.Gesture both = new RingMath.Gesture(12f);
        both.begin();
        both.onMorph(100, 100);
        both.onMove(160, 100);
        both.onPageTurn();
        check("gesture: a page turn wins over a moved morph",
                !both.releaseChooses(true), "");

        RingMath.Gesture still = new RingMath.Gesture(12f);
        still.begin();
        still.onMorph(100, 100);
        for (int i = 0; i < 10; i++) still.onMove(100, 100);
        check("gesture: stationary move events never arm a choice",
                !still.releaseChooses(true) && still.isMorphFresh(), "");

        RingMath.Gesture next = new RingMath.Gesture(12f);
        next.begin();
        next.onMorph(100, 100);
        next.onPageTurn();
        next.begin();
        check("gesture: a new gesture starts clean",
                !next.wasMorphed() && !next.pageTurned()
                        && !next.isMorphFresh() && next.releaseChooses(true), "");

        // ---- 6. Centering (v1.9.2) ----
        // Shipping reach in dp: ring 80 + item 24 + 8 margin.
        final float REACH = RING + ITEM + 8f;
        final float W = 360f; // a phone-width viewport in dp
        final float H = 640f;
        check("center: interior press keeps its exact position",
                RingMath.clampCenter(180f, REACH, W) == 180f
                        && RingMath.clampCenter(320f, REACH, H) == 320f, "");
        check("center: left edge clamps to the reach",
                RingMath.clampCenter(0f, REACH, W) == REACH
                        && RingMath.clampCenter(50f, REACH, W) == REACH, "");
        check("center: right edge clamps to size minus reach",
                RingMath.clampCenter(W, REACH, W) == W - REACH
                        && RingMath.clampCenter(300f, REACH, W) == W - REACH, "");
        check("center: top edge clamps to the reach",
                RingMath.clampCenter(0f, REACH, H) == REACH, "");
        check("center: bottom edge clamps to size minus reach",
                RingMath.clampCenter(H, REACH, H) == H - REACH, "");
        check("center: exactly at the boundary stays put",
                RingMath.clampCenter(REACH, REACH, W) == REACH
                        && RingMath.clampCenter(W - REACH, REACH, W) == W - REACH, "");
        check("center: top-left corner clamps both axes",
                RingMath.clampCenter(0f, REACH, W) == REACH
                        && RingMath.clampCenter(0f, REACH, H) == REACH, "");
        check("center: top-right corner clamps both axes",
                RingMath.clampCenter(W, REACH, W) == W - REACH
                        && RingMath.clampCenter(0f, REACH, H) == REACH, "");
        check("center: bottom-left corner clamps both axes",
                RingMath.clampCenter(0f, REACH, W) == REACH
                        && RingMath.clampCenter(H, REACH, H) == H - REACH, "");
        check("center: bottom-right corner clamps both axes",
                RingMath.clampCenter(W, REACH, W) == W - REACH
                        && RingMath.clampCenter(H, REACH, H) == H - REACH, "");
        check("center: overshoot shifts inward by exactly the overshoot",
                RingMath.clampCenter(REACH - 30f, REACH, W) == REACH
                        && RingMath.clampCenter(W - REACH + 30f, REACH, W) == W - REACH, "");
        check("center: viewport too small for the ring anchors at the reach",
                RingMath.clampCenter(100f, REACH, 2 * REACH - 1f) == REACH, "");
        check("center: zero-size bound collapses to the reach (the trap)",
                RingMath.clampCenter(180f, REACH, 0f) == REACH,
                "the view must resolve real bounds first; see RingMath doc");
        boolean gridOk = true;
        for (float px = -50f; px <= W + 50f; px += 25f) {
            for (float py = -50f; py <= H + 50f; py += 25f) {
                float ccx = RingMath.clampCenter(px, REACH, W);
                float ccy = RingMath.clampCenter(py, REACH, H);
                if (ccx - REACH < -0.001f || ccx + REACH > W + 0.001f
                        || ccy - REACH < -0.001f || ccy + REACH > H + 0.001f) {
                    gridOk = false;
                }
            }
        }
        check("center: full ring stays inside the viewport on a grid sweep",
                gridOk, "");

        System.out.println("----");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
