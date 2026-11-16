package app.control.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The tuning dial. A horizontal band carrying one detent per channel;
 * the needle sits in the middle and the band slides beneath it. Drag to
 * sweep; every detent you cross fires a haptic tick, and when you let
 * go the band settles onto the nearest channel with a short glide —
 * the feel of landing on a frequency.
 */
public class TunerView extends View {

    public interface OnTuneListener {
        /** Fired live while dragging (nearest detent) and on settle. */
        void onPreview(int channel);
        /** Fired once when the band settles on a channel. */
        void onSettled(int channel);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float offsetPx;          // band scroll position in px (channel units * spacing)
    private float spacingPx;         // px per channel
    private int current;             // settled channel (1..BANK_SIZE)
    private int lastTickChannel;     // for haptics while dragging
    private float downX;
    private float startOffset;
    private boolean dragging;
    private OnTuneListener listener;
    private final Handler animator = new Handler();
    private float animFrom;
    private float animTo;
    private long animStart;
    private static final long ANIM_MS = 140;

    public TunerView(Context context) {
        super(context);
        init(context);
    }

    public TunerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context c) {
        spacingPx = Ui.dp(c, 26);
        current = 1;
        offsetPx = 0;
        lastTickChannel = 1;
    }

    public void setOnTuneListener(OnTuneListener l) {
        listener = l;
    }

    public int getChannel() {
        return current;
    }

    /** Jump straight to a channel (direct tune / scan step). */
    public void tuneTo(int channel, boolean animate) {
        int ch = clamp(channel);
        float target = (ch - 1) * spacingPx;
        if (animate) {
            startGlide(target);
        } else {
            animator.removeCallbacksAndMessages(null);
            offsetPx = target;
            invalidate();
        }
        if (ch != current) {
            current = ch;
            if (listener != null) {
                listener.onPreview(ch);
                listener.onSettled(ch);
            }
        }
    }

    private int clamp(int ch) {
        return Math.max(1, Math.min(Profile.BANK_SIZE, ch));
    }

    private int nearestChannel() {
        return clamp(Math.round(offsetPx / spacingPx) + 1);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float centerX = w / 2f;
        float baseline = h * 0.68f;

        // band background (the mockup's tuner panel tone)
        paint.setColor(Ui.PANEL);
        canvas.drawRect(0, h * 0.18f, w, h * 0.92f, paint);

        // ticks + numbers
        for (int ch = 1; ch <= Profile.BANK_SIZE; ch++) {
            float x = centerX + (ch - 1) * spacingPx - offsetPx;
            if (x < -spacingPx || x > w + spacingPx) continue;
            boolean major = (ch % 5 == 0) || ch == 1;
            paint.setColor(ch == nearestChannel() ? Ui.ACCENT : Ui.DIM);
            paint.setStrokeWidth(Ui.dp(getContext(), major ? 2 : 1));
            float tickTop = baseline - Ui.dp(getContext(), major ? 20 : 12);
            canvas.drawLine(x, tickTop, x, baseline, paint);
            if (major || ch == nearestChannel()) {
                paint.setTextSize(Ui.dp(getContext(), 11));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(ch), x, baseline + Ui.dp(getContext(), 15), paint);
            }
        }

        // needle — the space's amber signal color
        paint.setColor(Ui.SIGNAL);
        paint.setStrokeWidth(Ui.dp(getContext(), 3));
        canvas.drawLine(centerX, h * 0.10f, centerX, h * 0.95f, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                animator.removeCallbacksAndMessages(null);
                dragging = true;
                downX = e.getX();
                startOffset = offsetPx;
                lastTickChannel = nearestChannel();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                offsetPx = clampOffset(startOffset + (downX - e.getX()));
                int near = nearestChannel();
                if (near != lastTickChannel) {
                    lastTickChannel = near;
                    tick();
                    if (listener != null) listener.onPreview(near);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                int target = nearestChannel();
                startGlide((target - 1) * spacingPx);
                if (target != current) {
                    current = target;
                    if (listener != null) listener.onSettled(target);
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private float clampOffset(float v) {
        float max = (Profile.BANK_SIZE - 1) * spacingPx;
        return Math.max(0, Math.min(max, v));
    }

    private void startGlide(float target) {
        animFrom = offsetPx;
        animTo = target;
        animStart = System.currentTimeMillis();
        animator.removeCallbacksAndMessages(null);
        animator.post(new Runnable() {
            @Override public void run() {
                float t = Math.min(1f, (System.currentTimeMillis() - animStart) / (float) ANIM_MS);
                float ease = 1f - (1f - t) * (1f - t);
                offsetPx = animFrom + (animTo - animFrom) * ease;
                invalidate();
                if (t < 1f) animator.postDelayed(this, 16);
            }
        });
    }

    private void tick() {
        try {
            Vibrator v = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(12, 60));
            }
        } catch (Exception ignored) {
        }
    }

    /** FRS-style frequency readout for a channel number. Channels 1–22
     *  use the real public FRS mapping; 23–50 continue the same 25 kHz
     *  pattern as an in-app extension (see BUILD_NOTES). */
    public static double frequencyMHz(int channel) {
        if (channel >= 1 && channel <= 7) return 462.5625 + (channel - 1) * 0.025;
        if (channel >= 8 && channel <= 14) return 467.5625 + (channel - 8) * 0.025;
        if (channel >= 15 && channel <= 22) return 462.5500 + (channel - 15) * 0.025;
        return 462.7500 + (channel - 23) * 0.025;
    }

    public static String frequencyText(int channel) {
        return String.format(java.util.Locale.US, "%.4f MHz", frequencyMHz(channel));
    }
}
