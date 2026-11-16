package app.control.launcher;

/**
 * Pure-Java ring geometry, paging, dwell, rotation, and release
 * semantics for the circle menu (sub-rings since v1.9; tighter
 * geometry and rotational paging since v1.9.1). No Android
 * imports, so the JVM harness exercises the exact shipping code.
 *
 * Geometry: items sit evenly around a circle starting at the top
 * (-90°), exactly as the main ring has since v1.2 — the same
 * angles, the same hit rule (nearest item within a radius), so a
 * sub-ring feels like the ring it came from. The radii below are
 * the single source of truth: CircleMenuView converts them with
 * Ui.dp and the harness references them directly.
 *
 * Paging: a ring page holds at most PAGE_SIZE items; more items
 * page. The page size is set by the chord rule — adjacent item
 * circles must not overlap — at the shipping ring/item radii
 * (80dp / 24dp): 8 items sit 45° apart on a ~61.2dp chord
 * against a 48dp item diameter, and the 9-item main ring sits
 * 40° apart on a ~54.7dp chord, still clear. The hub (37dp)
 * clears the items' inner edge by 19dp.
 *
 * Rotation: dragging around the hub in the ring band turns pages
 * (see {@link Rotation}). Release: one continuous gesture may
 * dwell, morph, drag onto a leaf, and release to choose it (see
 * {@link Gesture}).
 */
public final class RingMath {

    /** Dwell time that opens a sub-ring: 1 second, per Adam
     *  (v1.9.1; was 2 seconds in v1.9). Still safely above the
     *  pager's 450ms long-press arbitration. */
    public static final long DWELL_MS = 1000L;

    /** Max items on one ring page. */
    public static final int PAGE_SIZE = 8;

    /** Ring radius (center to item center), dp. */
    public static final float RING_RADIUS_DP = 80f;

    /** Item circle radius, dp. */
    public static final float ITEM_RADIUS_DP = 24f;

    /** Center hub radius, dp. */
    public static final float HUB_RADIUS_DP = 37f;

    /** Accumulated rotation that turns one page, degrees.
     *  A quarter-turn per page: four pages per revolution, and
     *  ~126dp of finger travel along the ring — a deliberate
     *  motion, not a wobble, and short of a full circle. */
    public static final float ROTATION_THRESHOLD_DEG = 90f;

    /** Rotation band, dp from the hub center: the icon circle
     *  itself (ring radius minus 16dp) out to the icons' outer
     *  edge. Inside the band the finger is on the ring; nearer
     *  the hub it is aiming at Back, farther out it is leaving. */
    public static final float ROTATION_BAND_INNER_DP = RING_RADIUS_DP - 16f;
    public static final float ROTATION_BAND_OUTER_DP = RING_RADIUS_DP + ITEM_RADIUS_DP;

    /** Largest radius swing allowed inside one rotation run, dp.
     *  A finger following the circle holds a near-constant
     *  radius; a straight chord across the ring sweeps a wide
     *  radius range. Exceeding this re-anchors and discards the
     *  accumulated angle, which is what keeps straight-line
     *  swipes from paging. */
    public static final float ROTATION_MAX_RADIUS_RANGE_DP = 24f;

    private RingMath() {}

    /** Angle in degrees for item {@code index} of {@code count},
     *  starting at the top and going clockwise. */
    public static double itemAngleDeg(int index, int count) {
        return -90.0 + index * (360.0 / count);
    }

    public static float itemX(float cx, float radius, int index, int count) {
        double a = Math.toRadians(itemAngleDeg(index, count));
        return cx + (float) Math.cos(a) * radius;
    }

    public static float itemY(float cy, float radius, int index, int count) {
        double a = Math.toRadians(itemAngleDeg(index, count));
        return cy + (float) Math.sin(a) * radius;
    }

    /** Straight-line distance between adjacent items. */
    public static double chord(float radius, int count) {
        if (count < 2) return Double.POSITIVE_INFINITY;
        return 2.0 * radius * Math.sin(Math.PI / count);
    }

    /** Nearest item within {@code hitRadius} of (x, y), else -1.
     *  Ties go to the lower index (strict less-than scan). */
    public static int hitIndex(float x, float y, float cx, float cy,
                               float radius, int count, float hitRadius) {
        if (count <= 0) return -1;
        float best = Float.MAX_VALUE;
        int bestIdx = -1;
        for (int i = 0; i < count; i++) {
            float dx = x - itemX(cx, radius, i, count);
            float dy = y - itemY(cy, radius, i, count);
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < best) {
                best = d;
                bestIdx = i;
            }
        }
        return best < hitRadius ? bestIdx : -1;
    }

    /** Number of pages for {@code total} items at {@code perPage}. */
    public static int pageCount(int total, int perPage) {
        if (total <= 0) return 0;
        return (total + perPage - 1) / perPage;
    }

    /** [start, end) item indices for {@code page}, clamped to the
     *  valid range. Empty input yields [0, 0). */
    public static int[] pageRange(int page, int perPage, int total) {
        int pages = pageCount(total, perPage);
        if (pages == 0) return new int[] {0, 0};
        int p = clampPage(page, pages);
        int start = p * perPage;
        return new int[] {start, Math.min(start + perPage, total)};
    }

    /** A page index clamped to [0, pages). No pages yields 0. */
    public static int clampPage(int page, int pages) {
        if (pages <= 0) return 0;
        return Math.max(0, Math.min(page, pages - 1));
    }

    /**
     * One axis of the ring center, clamped so the whole ring
     * stays on screen: the center sits in [reach, size - reach],
     * where {@code reach} is ring radius + item radius + margin.
     * A press in the middle keeps its exact position; a press
     * near an edge or corner shifts inward by exactly the
     * overshoot, never off-screen.
     *
     * When the ring cannot fit (size &lt; 2 * reach) the range
     * is empty and the result is {@code reach} — the ring is
     * anchored at the near edge rather than centered, the rule
     * the view has always used.
     *
     * CALLER WARNING (the v1.9.2 top-corner bug): {@code size}
     * must be a REAL, already-laid-out bound. Clamping against
     * 0 collapses to {@code reach} on both axes, which parks the
     * ring in the top-left corner — exactly what happened on the
     * first open, when the GONE menu view still measured 0x0.
     * The view resolves its bounds from its parent (or the
     * display) before calling this; this helper trusts its
     * input and the harness pins the zero-size behavior so the
     * trap stays visible.
     */
    public static float clampCenter(float v, float reach, float size) {
        float hi = size - reach;
        if (hi < reach) return reach;
        return Math.max(reach, Math.min(hi, v));
    }

    /**
     * The dwell state machine: a pointer held stationary over one
     * item for the dwell duration fires exactly once. Movement
     * beyond the tolerance re-anchors and restarts the clock —
     * drifting across an icon is not a dwell. The caller owns the
     * clock (passes {@code nowMs}) and restarts tracking whenever
     * the hovered item changes.
     */
    public static final class Dwell {
        private final long durationMs;
        private final float tolerancePx;
        private boolean tracking;
        private boolean fired;
        private float anchorX;
        private float anchorY;
        private long startMs;

        public Dwell(long durationMs, float tolerancePx) {
            this.durationMs = durationMs;
            this.tolerancePx = tolerancePx;
        }

        /** Begin (or restart) tracking at (x, y). */
        public void start(float x, float y, long nowMs) {
            tracking = true;
            fired = false;
            anchorX = x;
            anchorY = y;
            startMs = nowMs;
        }

        /**
         * Feed a pointer position. Returns true the moment the
         * dwell fires; latched true until {@link #reset()} or
         * {@link #start(float, float, long)}. Moving beyond the
         * tolerance re-anchors and restarts (returns false).
         */
        public boolean update(float x, float y, long nowMs) {
            if (!tracking) return false;
            if (fired) return true;
            float dx = x - anchorX;
            float dy = y - anchorY;
            if (dx * dx + dy * dy > tolerancePx * tolerancePx) {
                anchorX = x;
                anchorY = y;
                startMs = nowMs;
                return false;
            }
            if (nowMs - startMs >= durationMs) {
                fired = true;
                return true;
            }
            return false;
        }

        /** 0..1 progress toward firing; 0 when not tracking. */
        public float progress(long nowMs) {
            if (!tracking) return 0f;
            if (fired) return 1f;
            float p = (float) (nowMs - startMs) / (float) durationMs;
            return Math.max(0f, Math.min(1f, p));
        }

        public boolean isTracking() {
            return tracking;
        }

        public boolean isFired() {
            return fired;
        }

        public void reset() {
            tracking = false;
            fired = false;
        }
    }

    /**
     * Rotational paging (v1.9.1): accumulates the pointer's
     * angular displacement around the hub while it stays in the
     * ring band, and emits one page step per threshold crossed —
     * clockwise (visually) is +1, the next page; counter-clockwise
     * is -1, the previous page. Continued rotation keeps turning
     * pages; the remainder past a threshold carries into the next
     * step, so a steady turn pages evenly.
     *
     * Angles use the screen convention — atan2(dy, dx) with y
     * down — so increasing angle IS visually clockwise. The
     * caller feeds every move with the pointer's angle and radius
     * about the hub; the helper itself decides what counts:
     *
     * <ul>
     * <li>Outside the band: no accumulation, and the run
     *     re-anchors on re-entry (a radial drag in from the hub
     *     or out from beyond the ring contributes nothing until
     *     the finger is on the ring).</li>
     * <li>Radius swinging wider than the allowed range inside one
     *     run: the run is discarded and re-anchored. A finger
     *     following the circle holds its radius; a straight chord
     *     across the ring does not, so chords never page.</li>
     * <li>Direction reversal unwinds the accumulation (net angular
     *     displacement): rotating back the way you came returns
     *     it toward zero instead of banking progress in both
     *     directions.</li>
     * <li>A stationary pointer never fires, even with a remainder
     *     banked from a fast flick — a step needs real movement
     *     in the sample that crosses the threshold.</li>
     * </ul>
     */
    public static final class Rotation {
        private final float thresholdDeg;
        private final float bandInnerPx;
        private final float bandOuterPx;
        private final float maxRadiusRangePx;
        private boolean anchored;
        private float lastAngleDeg;
        private float minRadiusPx;
        private float maxRadiusPx;
        private float accumulatedDeg;

        public Rotation(float thresholdDeg, float bandInnerPx,
                        float bandOuterPx, float maxRadiusRangePx) {
            this.thresholdDeg = thresholdDeg;
            this.bandInnerPx = bandInnerPx;
            this.bandOuterPx = bandOuterPx;
            this.maxRadiusRangePx = maxRadiusRangePx;
        }

        public void reset() {
            anchored = false;
            accumulatedDeg = 0f;
        }

        /**
         * Feed the pointer's angle (degrees, screen convention)
         * and radius about the hub. Returns +1 when a clockwise
         * threshold is crossed (next page), -1 for
         * counter-clockwise (previous page), else 0. At most one
         * step per call.
         */
        public int update(float angleDeg, float radiusPx) {
            if (radiusPx < bandInnerPx || radiusPx > bandOuterPx) {
                reset();
                return 0;
            }
            if (!anchored) {
                anchored = true;
                lastAngleDeg = angleDeg;
                minRadiusPx = radiusPx;
                maxRadiusPx = radiusPx;
                accumulatedDeg = 0f;
                return 0;
            }
            if (radiusPx < minRadiusPx) minRadiusPx = radiusPx;
            if (radiusPx > maxRadiusPx) maxRadiusPx = radiusPx;
            if (maxRadiusPx - minRadiusPx > maxRadiusRangePx) {
                lastAngleDeg = angleDeg;
                minRadiusPx = radiusPx;
                maxRadiusPx = radiusPx;
                accumulatedDeg = 0f;
                return 0;
            }
            float d = angleDeg - lastAngleDeg;
            while (d > 180f) d -= 360f;
            while (d <= -180f) d += 360f;
            lastAngleDeg = angleDeg;
            if (Math.abs(d) < 0.5f) return 0;
            accumulatedDeg += d;
            if (accumulatedDeg >= thresholdDeg) {
                accumulatedDeg -= thresholdDeg;
                return 1;
            }
            if (accumulatedDeg <= -thresholdDeg) {
                accumulatedDeg += thresholdDeg;
                return -1;
            }
            return 0;
        }
    }

    /**
     * One touch gesture's release semantics (v1.9.1). The view
     * drives this; the harness drives the same sequences.
     *
     * Rules, in precedence order:
     * <ol>
     * <li>If the gesture turned a page (rotation), the release
     *     never chooses — the gesture was paging, not selecting.
     *     The ring stays open; the next tap chooses.</li>
     * <li>If the gesture morphed the ring (dwell) and the pointer
     *     never moved beyond the tolerance after the morph, the
     *     release never chooses — the leaf that happened to sit
     *     under the finger at the morph must not open by
     *     accident. The ring stays open.</li>
     * <li>Otherwise the release chooses the leaf under the finger
     *     — including the dwell → morph → drag → release gesture,
     *     which needs no tap.</li>
     * </ol>
     */
    public static final class Gesture {
        private final float tolerancePx;
        private boolean morphed;
        private boolean movedAfterMorph;
        private boolean pageTurned;
        private float morphX;
        private float morphY;

        public Gesture(float tolerancePx) {
            this.tolerancePx = tolerancePx;
        }

        /** A new gesture begins (finger down, menu opened). */
        public void begin() {
            morphed = false;
            movedAfterMorph = false;
            pageTurned = false;
        }

        /** The ring morphed under the still-down finger. */
        public void onMorph(float x, float y) {
            morphed = true;
            movedAfterMorph = false;
            morphX = x;
            morphY = y;
        }

        /** Feed a pointer position; only movement beyond the
         *  tolerance after a morph counts. Note: a highlight
         *  change alone does NOT count — a stationary finger
         *  receiving move events must not arm a choice. */
        public void onMove(float x, float y) {
            if (morphed && !movedAfterMorph) {
                float dx = x - morphX;
                float dy = y - morphY;
                if (dx * dx + dy * dy > tolerancePx * tolerancePx) {
                    movedAfterMorph = true;
                }
            }
        }

        /** The gesture turned a page. */
        public void onPageTurn() {
            pageTurned = true;
        }

        public boolean wasMorphed() {
            return morphed;
        }

        /** True while the morph is fresh — the finger has not
         *  moved since the ring changed under it. */
        public boolean isMorphFresh() {
            return morphed && !movedAfterMorph;
        }

        public boolean pageTurned() {
            return pageTurned;
        }

        /** Whether releasing over a leaf ({@code overLeaf}) chooses
         *  it, under the precedence rules above. */
        public boolean releaseChooses(boolean overLeaf) {
            if (!overLeaf) return false;
            if (pageTurned) return false;
            if (morphed && !movedAfterMorph) return false;
            return true;
        }
    }
}
