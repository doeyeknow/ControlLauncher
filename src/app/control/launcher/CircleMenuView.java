package app.control.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * The circle menu (v1.2; nine destinations since v1.7; sub-rings
 * since v1.9): Control's navigation. A long-press on launcher
 * chrome opens a ring of destinations centered on the touch point
 * (clamped so the whole ring stays on screen). Items are spaced
 * evenly around the ring (360 / count), so the count is not baked
 * into the geometry — nine icons sit 40° apart with no overlap.
 *
 * Selection works both ways, on every ring:
 *   - drag from the press point onto an icon and release to choose
 *     it (the icon under the finger highlights and its label shows
 *     at the center while dragging), or
 *   - release at the center, then tap an icon.
 * Tapping outside the ring, or Back, dismisses.
 *
 * Sub-rings (v1.9): holding a finger stationary on a main-ring
 * icon for 1 second morphs the ring into that destination's
 * children — Apps becomes every app, Settings becomes the setting
 * sections, Walkie becomes all 50 channels, and so on. A progress
 * arc fills around the icon during the hold so the wait is
 * visible. Selection in a sub-ring happens on release: the same
 * gesture that dwelled can drag onto a leaf and release to open
 * it, no tap required — but a release with no movement after the
 * morph never chooses the leaf that landed under the finger (see
 * {@link RingMath.Gesture}). Rings with more than
 * {@link RingMath#PAGE_SIZE} items page: drag around the hub —
 * clockwise for the next page, counter-clockwise for the
 * previous one, a quarter-turn per page with a haptic tick — or
 * tap the page dots under the hub. The hub shows the ring's name
 * and acts as Back (tap it, or press Back, to return to the
 * parent ring). The fast paths are untouched: a quick
 * drag-and-release or tap never waits on the dwell timer, and
 * the pager's 450ms long-press arbitration (which protects
 * Apps-cell starring) is not involved here at all.
 *
 * Items: Home, Browser, Walkie, Apps (pager pages / sheet), then
 * Chat, Activity, Connections, Settings, Wallet (activities),
 * and Cameras (pager page, v1.31) — ten destinations, 36° apart.
 */
public class CircleMenuView extends View {

    public static final int ITEM_HOME = 0;
    public static final int ITEM_BROWSER = 1;
    public static final int ITEM_WALKIE = 2;
    public static final int ITEM_APPS = 3;
    public static final int ITEM_CHAT = 4;
    public static final int ITEM_ACTIVITY = 5;
    public static final int ITEM_CONNECTIONS = 6;
    public static final int ITEM_SETTINGS = 7;
    public static final int ITEM_WALLET = 8;
    public static final int ITEM_CAMERAS = 9;

    private static final int[] GLYPHS = {
            PageGlyphs.HOME, PageGlyphs.BROWSER, PageGlyphs.WALKIE, PageGlyphs.APPS,
            PageGlyphs.CHAT, PageGlyphs.ACTIVITY, PageGlyphs.CONNECTIONS,
            PageGlyphs.SETTINGS, PageGlyphs.WALLET, PageGlyphs.CAMERA};
    private static final String[] LABELS = {
            "Home", "Browser", "Walkie", "Apps",
            "Chat", "Activity", "Connections", "Settings", "Wallet", "Cameras"};

    public interface Listener {
        void onChoose(int item);
        void onDismiss();
    }

    /** How many circle menus are currently open, across the
     *  in-launcher instance and the overlay instance (v1.14). A
     *  count, not a flag: the two instances can overlap briefly
     *  (a chrome long-press while the overlay ring is up), and
     *  one instance closing must not report "no menu open" while
     *  the other is still up. The overlay service reads this to
     *  stay out of a gesture the built-in menu already owns;
     *  nothing in this view reads it, so the in-launcher path is
     *  behaviorally untouched. Adjusted only by showAt/close,
     *  guarded by each instance's own open state so the count
     *  stays paired. */
    private static int openMenuCount;

    public static boolean isAnyMenuOpen() {
        return openMenuCount > 0;
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint centerLabel = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;
    private RingItems.Source source;
    private boolean open;
    private boolean dragMode;   // opened by a long-press still held down
    private float cx, cy;       // ring center, view coordinates
    private int highlight = -1; // page-local index on the current ring
    private int downItem = -1;

    // ---- sub-ring state (v1.9) ----
    private int parent = -1;    // -1 = main ring, else the ITEM_* opened
    private int page;
    private List<RingItems.Item> items; // null while loading (sub-ring only)
    private boolean itemsLoading;

    // ---- dwell state ----
    private final RingMath.Dwell dwell =
            new RingMath.Dwell(RingMath.DWELL_MS, 12f);
    private final Handler dwellHandler = new Handler(Looper.getMainLooper());
    private float lastX;
    private float lastY;
    private boolean dwellTicking;
    private final Runnable dwellTick = new Runnable() {
        @Override public void run() {
            dwellTicking = false;
            if (!open || parent >= 0 || highlight < 0) return;
            long now = SystemClock.uptimeMillis();
            if (dwell.update(lastX, lastY, now)) {
                openSubRing(highlight);
                return;
            }
            if (dwell.isTracking()) {
                dwellTicking = true;
                dwellHandler.postDelayed(this, 50);
                invalidate(); // progress arc
            }
        }
    };

    // One gesture's release semantics (v1.9.1): whether a release
    // may choose, given the morphs and page turns the gesture
    // already performed. See RingMath.Gesture.
    private final RingMath.Gesture gesture;

    // Rotational paging (v1.9.1): angle accumulation around the
    // hub, fed on every move in a paged sub-ring.
    private final RingMath.Rotation rotation;

    // Hub / dots state (tap mode, sub-rings).
    private boolean hubDown;
    private int dotDown = -1;

    public CircleMenuView(Context c) {
        super(c);
        gesture = new RingMath.Gesture(Ui.dp(c, 12));
        rotation = new RingMath.Rotation(RingMath.ROTATION_THRESHOLD_DEG,
                Ui.dp(c, RingMath.ROTATION_BAND_INNER_DP),
                Ui.dp(c, RingMath.ROTATION_BAND_OUTER_DP),
                Ui.dp(c, RingMath.ROTATION_MAX_RADIUS_RANGE_DP));
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(android.graphics.Typeface.MONOSPACE);
        centerLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
        centerLabel.setTextAlign(Paint.Align.CENTER);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        // No setColor here: the arc color is read at draw time
        // (v1.15) so the long-lived overlay ring re-themes live.
        numberPaint.setTextAlign(Paint.Align.CENTER);
        numberPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
        dotPaint.setStyle(Paint.Style.FILL);
        setVisibility(GONE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSource(RingItems.Source s) {
        source = s;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isInSubRing() {
        return open && parent >= 0;
    }

    /** Open centered on (x, y) in this view's coordinates, clamped so
     *  the full ring stays on screen. Called while the opening finger
     *  is still down — the pager forwards the rest of that gesture to
     *  {@link #onMenuTouch}. Always opens on the main ring.
     *
     *  The clamp bounds come from {@link #boundsWidth()} /
     *  {@link #boundsHeight()}, NOT from getWidth()/getHeight()
     *  directly: this view starts GONE, and a GONE child of the
     *  stage FrameLayout is never measured — so on the very first
     *  open getWidth()/getHeight() are 0, the clamp collapses to
     *  the reach on both axes, and the ring parks in the top-left
     *  corner (the v1.9.2 bug Adam reported). The parent is laid
     *  out long before any long-press can fire, and this view is
     *  MATCH_PARENT at the parent's origin, so the parent's size
     *  is the view's size. */
    public void showAt(float x, float y) {
        boolean wasOpen = open;
        float reach = ringR() + itemR() + Ui.dp(getContext(), 8);
        cx = RingMath.clampCenter(x, reach, boundsWidth());
        cy = RingMath.clampCenter(y, reach, boundsHeight());
        open = true;
        dragMode = true;
        highlight = -1;
        downItem = -1;
        parent = -1;
        page = 0;
        items = null;
        itemsLoading = false;
        gesture.begin();
        rotation.reset();
        hubDown = false;
        dotDown = -1;
        dwell.reset();
        setVisibility(VISIBLE);
        if (!wasOpen) openMenuCount++;
        invalidate();
    }

    public void close() {
        if (open) openMenuCount = Math.max(0, openMenuCount - 1);
        open = false;
        dragMode = false;
        highlight = -1;
        downItem = -1;
        parent = -1;
        page = 0;
        items = null;
        itemsLoading = false;
        gesture.begin();
        rotation.reset();
        hubDown = false;
        dotDown = -1;
        dwell.reset();
        stopDwellTick();
        setVisibility(GONE);
    }

    /** Back button: up one ring level if in a sub-ring, else the
     *  caller dismisses. Returns true when a level was left. */
    public boolean goBack() {
        if (!open || parent < 0) return false;
        parent = -1;
        page = 0;
        items = null;
        itemsLoading = false;
        highlight = -1;
        downItem = -1;
        gesture.begin();
        rotation.reset();
        hubDown = false;
        dotDown = -1;
        dwell.reset();
        stopDwellTick();
        invalidate();
        return true;
    }

    /** Re-query the source for the current sub-ring (an async load
     *  landed). No-op on the main ring or while closed. */
    public void refreshSubRing() {
        if (!open || parent < 0 || source == null) return;
        List<RingItems.Item> fresh = source.itemsFor(parent);
        items = fresh;
        itemsLoading = fresh == null;
        int pages = pageCount();
        if (page >= pages) page = Math.max(0, pages - 1);
        highlight = -1;
        rotation.reset();
        invalidate();
    }

    private void openSubRing(int mainIndex) {
        if (source == null) return;
        parent = mainIndex;
        page = 0;
        items = source.itemsFor(mainIndex);
        itemsLoading = items == null;
        highlight = -1;
        downItem = -1;
        gesture.onMorph(lastX, lastY);
        rotation.reset();
        dwell.reset();
        stopDwellTick();
        invalidate();
    }

    private int pageCount() {
        if (parent < 0 || items == null) return 0;
        return RingMath.pageCount(items.size(), RingMath.PAGE_SIZE);
    }

    /** Items on the current page (empty when loading or empty). */
    private int pageItemCount() {
        if (parent < 0) return GLYPHS.length;
        if (items == null) return 0;
        int[] r = RingMath.pageRange(page, RingMath.PAGE_SIZE, items.size());
        return r[1] - r[0];
    }

    private RingItems.Item pageItem(int localIndex) {
        int[] r = RingMath.pageRange(page, RingMath.PAGE_SIZE, items.size());
        return items.get(r[0] + localIndex);
    }

    /** Width to clamp the ring center against: this view's own
     *  width once laid out, else the parent's (this view is
     *  MATCH_PARENT at the parent's origin, so they are the
     *  same), else the display width as a last resort. Never 0
     *  in practice — the parent is laid out before any
     *  long-press can fire — but the fallbacks keep the first
     *  open correct even if layout order ever changes. */
    private float boundsWidth() {
        if (getWidth() > 0) return getWidth();
        if (getParent() instanceof View) {
            int pw = ((View) getParent()).getWidth();
            if (pw > 0) return pw;
        }
        return getResources().getDisplayMetrics().widthPixels;
    }

    private float boundsHeight() {
        if (getHeight() > 0) return getHeight();
        if (getParent() instanceof View) {
            int ph = ((View) getParent()).getHeight();
            if (ph > 0) return ph;
        }
        return getResources().getDisplayMetrics().heightPixels;
    }

    /** If the view is laid out (or re-laid out, e.g. rotation)
     *  while the menu is open, re-clamp the center against the
     *  new bounds so the ring can never strand off-screen. A
     *  no-op when the center already fits. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (open && w > 0 && h > 0) {
            float reach = ringR() + itemR() + Ui.dp(getContext(), 8);
            cx = RingMath.clampCenter(cx, reach, w);
            cy = RingMath.clampCenter(cy, reach, h);
            invalidate();
        }
    }

    private float ringR() {
        return Ui.dp(getContext(), RingMath.RING_RADIUS_DP);
    }

    private float itemR() {
        return Ui.dp(getContext(), RingMath.ITEM_RADIUS_DP);
    }

    private float hubR() {
        return Ui.dp(getContext(), RingMath.HUB_RADIUS_DP);
    }

    private float itemX(int localIndex, int count) {
        return RingMath.itemX(cx, ringR(), localIndex, count);
    }

    private float itemY(int localIndex, int count) {
        return RingMath.itemY(cy, ringR(), localIndex, count);
    }

    private int hitItem(float x, float y) {
        int count = pageItemCount();
        if (count <= 0) return -1;
        return RingMath.hitIndex(x, y, cx, cy, ringR(), count, itemR() * 1.6f);
    }

    private boolean hitHub(float x, float y) {
        return dist(x, y, cx, cy) < hubR();
    }

    private float dotsY() {
        return cy + hubR() + Ui.dp(getContext(), 16);
    }

    private int hitDot(float x, float y) {
        int pages = pageCount();
        if (pages <= 1) return -1;
        float dy = dotsY();
        if (Math.abs(y - dy) > Ui.dp(getContext(), 12)) return -1;
        float gap = Ui.dp(getContext(), 16);
        float x0 = cx - (pages - 1) * gap / 2f;
        for (int i = 0; i < pages; i++) {
            if (Math.abs(x - (x0 + i * gap)) < Ui.dp(getContext(), 12)) return i;
        }
        return -1;
    }

    /** One haptic tick per page turn — the TunerView detent
     *  pattern (12ms, amplitude 60). */
    private void tick() {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(12, 60));
            }
        } catch (Exception ignored) {
        }
    }

    private void startDwellTick() {
        if (dwellTicking) return;
        dwellTicking = true;
        dwellHandler.postDelayed(dwellTick, 50);
    }

    private void stopDwellTick() {
        dwellTicking = false;
        dwellHandler.removeCallbacks(dwellTick);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return onMenuTouch(ev);
    }

    /** One handler for both input paths: the still-held opening
     *  gesture forwarded by the pager, and ordinary taps once the
     *  menu is up in tap mode. */
    public boolean onMenuTouch(MotionEvent ev) {
        if (!open) return false;
        float x = ev.getX();
        float y = ev.getY();
        lastX = x;
        lastY = y;
        long now = SystemClock.uptimeMillis();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (dist(x, y, cx, cy) > ringR() + itemR() + Ui.dp(getContext(), 16)) {
                    close();
                    if (listener != null) listener.onDismiss();
                    return true;
                }
                hubDown = false;
                dotDown = -1;
                gesture.begin();
                rotation.reset();
                if (parent >= 0 && hitHub(x, y)) {
                    hubDown = true;
                    highlight = -1;
                    invalidate();
                    return true;
                }
                int dot = hitDot(x, y);
                if (dot >= 0) {
                    dotDown = dot;
                    return true;
                }
                downItem = hitItem(x, y);
                highlight = downItem;
                if (downItem >= 0 && parent < 0) {
                    dwell.start(x, y, now);
                    startDwellTick();
                } else {
                    dwell.reset();
                    stopDwellTick();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (hubDown) {
                    if (!hitHub(x, y)) hubDown = false;
                    return true;
                }
                if (dotDown >= 0) {
                    if (hitDot(x, y) != dotDown) dotDown = -1;
                    return true;
                }
                gesture.onMove(x, y);
                int hit = hitItem(x, y);
                if (hit != highlight) {
                    highlight = hit;
                    if (parent < 0 && hit >= 0) {
                        dwell.start(x, y, now);
                        startDwellTick();
                    } else {
                        dwell.reset();
                        stopDwellTick();
                    }
                } else if (parent < 0 && hit >= 0) {
                    if (dwell.update(x, y, now)) {
                        openSubRing(hit);
                        return true;
                    }
                    startDwellTick();
                }
                // Rotational paging (v1.9.1): dragging around the
                // hub in the ring band turns pages — clockwise
                // next, counter-clockwise previous. Works in both
                // drag mode (the still-down dwell gesture) and tap
                // mode. The helper decides what counts as rotation
                // (band, radius stability); taps, the dwell hold,
                // and radial selection drags never reach a step.
                // This replaces the v1.9 horizontal swipe: a swipe
                // across the ring is geometrically a partial
                // rotation, so keeping both would double-fire.
                if (parent >= 0 && pageCount() > 1) {
                    float angle = (float) Math.toDegrees(
                            Math.atan2(y - cy, x - cx));
                    int steps = rotation.update(angle, dist(x, y, cx, cy));
                    if (steps != 0) {
                        int next = RingMath.clampPage(page + steps, pageCount());
                        if (next != page) {
                            page = next;
                            gesture.onPageTurn();
                            highlight = -1;
                            tick();
                        }
                    }
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                stopDwellTick();
                dwell.reset();
                if (hubDown) {
                    hubDown = false;
                    if (hitHub(x, y)) goBack();
                    return true;
                }
                if (dotDown >= 0) {
                    int dot = hitDot(x, y);
                    if (dot == dotDown) {
                        page = dot;
                        highlight = -1;
                        rotation.reset();
                        invalidate();
                    }
                    dotDown = -1;
                    return true;
                }
                // A gesture that turned a page never chooses on
                // release — it was paging, not selecting. The ring
                // stays open; the next tap chooses.
                if (gesture.pageTurned()) {
                    dragMode = false;
                    downItem = -1;
                    highlight = -1;
                    invalidate();
                    return true;
                }
                if (dragMode) {
                    dragMode = false;
                    if (gesture.isMorphFresh()) {
                        // Released right after a morph, without
                        // moving: stay open in tap mode; do not
                        // choose the leaf that landed under the
                        // finger.
                        highlight = -1;
                        invalidate();
                        return true;
                    }
                    int pick = hitItem(x, y);
                    if (pick >= 0) {
                        choose(pick);
                    } else if (parent >= 0 && hitHub(x, y)) {
                        goBack();
                    } else {
                        highlight = -1;
                        invalidate(); // stay open in tap mode
                    }
                    return true;
                }
                if (gesture.isMorphFresh()) {
                    downItem = -1;
                    highlight = -1;
                    invalidate();
                    return true;
                }
                if (gesture.wasMorphed()) {
                    // The dwell → morph → drag gesture: the finger
                    // moved after the morph, so releasing over a
                    // leaf opens it — no tap required. (downItem
                    // still holds the main-ring index, so the tap
                    // rule below must not apply to this gesture.)
                    int up = hitItem(x, y);
                    if (up >= 0) choose(up);
                    downItem = -1;
                    return true;
                }
                int up = hitItem(x, y);
                if (downItem >= 0 && up == downItem) {
                    choose(downItem);
                }
                downItem = -1;
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                dragMode = false;
                downItem = -1;
                hubDown = false;
                dotDown = -1;
                gesture.begin();
                rotation.reset();
                dwell.reset();
                stopDwellTick();
                return true;
            }
            default:
                return true;
        }
    }

    private void choose(int localIndex) {
        if (parent < 0) {
            close();
            if (listener != null) listener.onChoose(localIndex);
            return;
        }
        RingItems.Item item = pageItem(localIndex);
        close();
        if (source != null) source.onLeafAction(item.action);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!open) return;
        canvas.drawRect(0, 0, getWidth(), getHeight(), withAlpha(165));

        int count = pageItemCount();
        boolean subRing = parent >= 0;

        // center hub + current label
        fill.setColor(Ui.SURFACE2);
        canvas.drawCircle(cx, cy, hubR(), fill);
        centerLabel.setColor(highlight >= 0 ? Ui.ACCENT : Ui.DIM);
        centerLabel.setTextSize(Ui.dp(getContext(), 13));
        String text;
        String sub = null;
        if (subRing) {
            if (itemsLoading) {
                text = LABELS[parent];
                sub = "Loading…";
            } else if (count == 0) {
                text = LABELS[parent];
                sub = source != null ? source.emptyHint(parent) : "";
            } else if (highlight >= 0) {
                text = ellipsize(pageItem(highlight).label, centerLabel,
                        hubR() * 2 - Ui.dp(getContext(), 8));
                sub = "‹ BACK";
            } else {
                text = LABELS[parent];
                sub = "‹ BACK";
            }
        } else {
            text = highlight >= 0 ? LABELS[highlight] : "Menu";
        }
        if (sub != null) {
            canvas.drawText(text, cx, cy - Ui.dp(getContext(), 1), centerLabel);
            centerLabel.setColor(Ui.DIM);
            centerLabel.setTextSize(Ui.dp(getContext(), 9));
            canvas.drawText(ellipsize(sub, centerLabel,
                    hubR() * 2 - Ui.dp(getContext(), 8)),
                    cx, cy + Ui.dp(getContext(), 13), centerLabel);
        } else {
            canvas.drawText(text, cx, cy + Ui.dp(getContext(), 4.5f), centerLabel);
        }

        // Empty/loading hint under the hub (dots live here otherwise).
        if (subRing && count == 0 && !itemsLoading) {
            centerLabel.setColor(Ui.DIM);
            centerLabel.setTextSize(Ui.dp(getContext(), 11));
            String hint = source != null ? source.emptyHint(parent) : "";
            canvas.drawText(ellipsize(hint, centerLabel,
                    Math.min(getWidth() - Ui.dp(getContext(), 48), Ui.dp(getContext(), 300))),
                    cx, dotsY(), centerLabel);
        }

        // Page dots.
        int pages = pageCount();
        if (pages > 1) {
            float gap = Ui.dp(getContext(), 16);
            float x0 = cx - (pages - 1) * gap / 2f;
            float dy = dotsY();
            for (int i = 0; i < pages; i++) {
                dotPaint.setColor(i == page ? Ui.ACCENT : Ui.DIM);
                canvas.drawCircle(x0 + i * gap, dy, Ui.dp(getContext(), 4), dotPaint);
            }
        }

        for (int i = 0; i < count; i++) {
            float ix = itemX(i, count);
            float iy = itemY(i, count);
            boolean hot = i == highlight;
            fill.setColor(hot ? Ui.ACCENT : Ui.SURFACE2);
            canvas.drawCircle(ix, iy, itemR(), fill);
            int ink = hot ? Ui.ACCENT_INK : Ui.TEXT;
            if (subRing) {
                drawLeaf(canvas, pageItem(i), ix, iy, ink);
            } else {
                stroke.setColor(ink);
                stroke.setStrokeWidth(Ui.dp(getContext(), 1.8f));
                PageGlyphs.draw(canvas, GLYPHS[i], ix, iy - Ui.dp(getContext(), 5),
                        Ui.dp(getContext(), 21), stroke);
                label.setColor(ink);
                label.setTextSize(Ui.dp(getContext(), 8.5f));
                canvas.drawText(LABELS[i], ix, iy + Ui.dp(getContext(), 15), label);
            }
            // Dwell progress arc around the held icon.
            if (!subRing && hot && dwell.isTracking() && !dwell.isFired()) {
                float p = dwell.progress(SystemClock.uptimeMillis());
                if (p > 0f) {
                    arcPaint.setColor(Ui.ACCENT);
                    arcPaint.setStrokeWidth(Ui.dp(getContext(), 3));
                    float r = itemR() + Ui.dp(getContext(), 5);
                    canvas.drawArc(ix - r, iy - r, ix + r, iy + r,
                            -90, p * 360f, false, arcPaint);
                }
            }
        }
    }

    private void drawLeaf(Canvas canvas, RingItems.Item item,
                          float ix, float iy, int ink) {
        Drawable appIcon = item.resolveIcon();
        if (appIcon != null) {
            int half = Ui.dp(getContext(), 20);
            int top = (int) (iy - Ui.dp(getContext(), 6)) - half;
            appIcon.setBounds((int) ix - half, top, (int) ix + half, top + half * 2);
            appIcon.draw(canvas);
        } else if (item.numberText != null) {
            numberPaint.setColor(item.numberColor != 0 ? item.numberColor : ink);
            numberPaint.setTextSize(Ui.dp(getContext(), 20));
            numberPaint.setFakeBoldText(true);
            canvas.drawText(item.numberText, ix, iy + Ui.dp(getContext(), 1), numberPaint);
            numberPaint.setFakeBoldText(false);
        } else {
            stroke.setColor(ink);
            stroke.setStrokeWidth(Ui.dp(getContext(), 1.8f));
            PageGlyphs.draw(canvas, item.glyph, ix, iy - Ui.dp(getContext(), 5),
                    Ui.dp(getContext(), 21), stroke);
        }
        label.setColor(ink);
        label.setTextSize(Ui.dp(getContext(), 8.5f));
        canvas.drawText(ellipsize(item.label, label, itemR() * 2 - Ui.dp(getContext(), 4)),
                ix, iy + Ui.dp(getContext(), 15), label);
    }

    private String ellipsize(String s, TextPaint p, float maxWidth) {
        if (s == null) return "";
        return TextUtils.ellipsize(s, p, maxWidth,
                TextUtils.TruncateAt.END).toString();
    }

    private Paint withAlpha(int a) {
        Paint p = new Paint();
        p.setColor(Color.argb(a, 8, 12, 10)); // scrim over the phone-bg tone
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
