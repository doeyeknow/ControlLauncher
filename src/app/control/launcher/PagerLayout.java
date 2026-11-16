package app.control.launcher;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Scroller;

/**
 * A minimal side-by-side page host for the launcher home.
 *
 * Pages are laid out in a row, each exactly one viewport wide, and the
 * host drags between them. Gesture arbitration (the rule Adam asked
 * for): a horizontal swipe is the pager's ONLY when it does not start
 * on a surface that owns horizontal gestures — the current page says
 * which regions those are via {@link PagerPage#ownsHorizontalGesture}
 * (the browser's WebView, the walkie tuner's sweep, …) — and swipes
 * that start within the screen-edge strip always belong to the pager,
 * so there is always a way to leave a page. Everything else (chrome,
 * headers, backgrounds) paginates.
 *
 * v1.3: the strip is three pages — Browser, Home, Walkie — and the
 * bottom edge strip (~24dp) raises the Apps sheet on an upward swipe
 * (see BottomStripListener).
 *
 * v1.5: the strip is four pages — Browser, Home, Walkie, Chat. The
 * pager is generic over child count; nothing here changed but this
 * note.
 */
public class PagerLayout extends ViewGroup {

    public interface PagerPage {
        void onPageShown();
        void onPageHidden();
        void onPageDestroy();
        /** Coords are in this page's own coordinate space (the page fills
         *  the pager viewport when it is the current page). */
        boolean ownsHorizontalGesture(float x, float y);
    }

    public interface Listener {
        void onPageSelected(int index);
    }

    /** Long-press on pager-owned chrome (v1.2 circle menu). Fires
     *  only on surfaces the pager arbitrates — never on surfaces a
     *  page owns (WebView, tuner, PTT, text fields), whose gestures
     *  keep their own meanings. */
    public interface ChromeLongPressListener {
        void onChromeLongPress(float x, float y);
    }

    /** Receives the touch stream while the circle menu is open. */
    public interface MenuTouchHandler {
        boolean onMenuTouch(MotionEvent ev);
    }

    /** Swipe-up that starts in the bottom edge strip (v1.3): the
     *  strip belongs to the Apps sheet on every page, the way the
     *  side edge strips belong to the pager. */
    public interface BottomStripListener {
        void onBottomSwipeUp();
    }

    private final Scroller scroller;
    private final int touchSlop;
    private final int edgePx;
    private final int stripPx;
    private float offset;            // px scrolled; page i sits at i*width
    private int current;             // settled page
    private Listener listener;

    private float downX, downY, downOffset;
    private boolean yieldGesture;    // the start surface owns this gesture
    private boolean dragging;

    // circle-menu long-press state
    private final android.os.Handler lpHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable lpFire = this::fireLongPress;
    private ChromeLongPressListener chromeLongPressListener;
    private MenuTouchHandler menuTouchHandler;
    private boolean menuOpen;
    private float lpX, lpY;
    private long lpDownTime;
    private boolean lpEligible;

    // bottom-strip (Apps sheet) state
    private BottomStripListener bottomStripListener;
    private boolean stripDown;
    private boolean stripFired;
    private float stripDownX, stripDownY;

    public PagerLayout(Context c) {
        super(c);
        scroller = new Scroller(c);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        edgePx = Ui.dp(c, 28);
        stripPx = Ui.dp(c, 24);
    }

    public void setBottomStripListener(BottomStripListener l) {
        bottomStripListener = l;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setChromeLongPressListener(ChromeLongPressListener l) {
        chromeLongPressListener = l;
    }

    public void setMenuTouchHandler(MenuTouchHandler h) {
        menuTouchHandler = h;
    }

    public void setMenuOpen(boolean open) {
        menuOpen = open;
        if (!open) lpHandler.removeCallbacks(lpFire);
    }

    public boolean isMenuOpen() {
        return menuOpen;
    }

    /**
     * Long-press detection lives at dispatch level so it sees every
     * gesture before children do: a stationary press (within slop —
     * a long-press is stationary by definition) on pager-owned chrome
     * for 450ms (just under the framework's 500ms long-press
     * timeout) cancels the child's pending click/long-press and
     * hands the rest of the gesture to the circle menu. Movement
     * beyond slop cancels the press and the pager swipe proceeds as
     * before. Yield surfaces (the ones {@link #computeYield} names)
     * are never eligible, so app cells, the PTT button and text
     * fields keep their own long-press meanings.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (menuOpen) {
            return menuTouchHandler != null && menuTouchHandler.onMenuTouch(ev);
        }
        // Bottom edge strip → Apps sheet. The strip claims a gesture
        // only once it is clearly an upward drag; stationary presses
        // and sideways/other moves fall through untouched (so the
        // Walkie join-sound block's taps and the chrome long-press
        // keep working exactly as before).
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                stripDown = getHeight() > 0
                        && ev.getY() > getHeight() - stripPx;
                stripFired = false;
                stripDownX = ev.getX();
                stripDownY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (stripFired) return true;
                if (stripDown && bottomStripListener != null) {
                    float dy = stripDownY - ev.getY();
                    float dx = Math.abs(ev.getX() - stripDownX);
                    if (dy > touchSlop * 2 && dy > dx) {
                        stripFired = true;
                        lpHandler.removeCallbacks(lpFire);
                        MotionEvent cancel = MotionEvent.obtain(ev.getDownTime(),
                                android.os.SystemClock.uptimeMillis(),
                                MotionEvent.ACTION_CANCEL, stripDownX, stripDownY, 0);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        bottomStripListener.onBottomSwipeUp();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (stripFired) {
                    stripFired = false;
                    stripDown = false;
                    lpHandler.removeCallbacks(lpFire);
                    return true;
                }
                stripDown = false;
                break;
            default:
                break;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lpX = ev.getX();
                lpY = ev.getY();
                lpDownTime = ev.getDownTime();
                lpEligible = !computeYield(lpX, lpY);
                lpHandler.removeCallbacks(lpFire);
                if (lpEligible) lpHandler.postDelayed(lpFire, 450);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - lpX) > touchSlop
                        || Math.abs(ev.getY() - lpY) > touchSlop) {
                    lpHandler.removeCallbacks(lpFire);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lpHandler.removeCallbacks(lpFire);
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void fireLongPress() {
        if (!lpEligible || menuOpen) return;
        menuOpen = true;
        MotionEvent cancel = MotionEvent.obtain(lpDownTime,
                android.os.SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, lpX, lpY, 0);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        if (chromeLongPressListener != null) {
            chromeLongPressListener.onChromeLongPress(lpX, lpY);
        }
    }

    public int getCurrentPage() {
        return current;
    }

    public void goToPage(int index, boolean animate) {
        int count = getChildCount();
        if (count == 0) return;
        index = Math.max(0, Math.min(count - 1, index));
        int w = getWidth();
        if (w == 0) { // not laid out yet; remember via offset on layout
            pendingPage = index;
            return;
        }
        int target = index * w;
        if (!animate) {
            scroller.forceFinished(true);
            setOffset(target);
            settle(index);
        } else if (target != (int) offset) {
            scroller.forceFinished(true);
            scroller.startScroll((int) offset, 0, target - (int) offset, 0, 260);
            pendingPage = index;
            postInvalidateOnAnimation();
        } else {
            settle(index);
        }
    }

    private int pendingPage = -1;

    private void settle(int index) {
        if (index == current) {
            pendingPage = -1;
            return;
        }
        current = index;
        pendingPage = -1;
        if (listener != null) listener.onPageSelected(index);
    }

    private void setOffset(float o) {
        float max = Math.max(0, (getChildCount() - 1) * (float) getWidth());
        offset = Math.max(0, Math.min(max, o));
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int cw = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cw, ch);
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        if (pendingPage >= 0 && !scroller.isFinished()) {
            // mid-animation; keep offset
        } else if (pendingPage >= 0) {
            offset = pendingPage * w;
            settle(pendingPage);
        }
        for (int i = 0; i < getChildCount(); i++) {
            int left = (int) (i * w - offset);
            getChildAt(i).layout(left, 0, left + w, h);
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setOffset(scroller.getCurrX());
            postInvalidateOnAnimation();
        } else if (pendingPage >= 0) {
            settle(pendingPage);
        }
    }

    // ---- touch ----

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) scroller.forceFinished(true);
                downX = ev.getX();
                downY = ev.getY();
                downOffset = offset;
                dragging = false;
                yieldGesture = computeYield(ev.getX(), ev.getY());
                return false;
            case MotionEvent.ACTION_MOVE:
                if (yieldGesture) return false;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    dragging = true;
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!scroller.isFinished()) scroller.forceFinished(true);
                downX = ev.getX();
                downY = ev.getY();
                downOffset = offset;
                dragging = false;
                yieldGesture = computeYield(ev.getX(), ev.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (yieldGesture) return true;
                float dx = ev.getX() - downX;
                if (dragging || Math.abs(dx) > touchSlop) {
                    dragging = true;
                    setOffset(downOffset - dx);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    int w = getWidth();
                    if (w > 0) {
                        int target = Math.round(offset / w);
                        target = Math.max(0, Math.min(getChildCount() - 1, target));
                        pendingPage = target;
                        scroller.startScroll((int) offset, 0,
                                target * w - (int) offset, 0, 240);
                        postInvalidateOnAnimation();
                    }
                }
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    private boolean computeYield(float x, float y) {
        int w = getWidth();
        if (w == 0) return false;
        // Edge strips always belong to the pager.
        if (x < edgePx || x > w - edgePx) return false;
        View child = getChildAt(current);
        if (child instanceof PagerPage) {
            // Page coordinates == pager coordinates for the settled page.
            return ((PagerPage) child).ownsHorizontalGesture(x, y);
        }
        return false;
    }
}
