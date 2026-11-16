package app.control.launcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hold detection over a {@code getevent -t} stream (v1.14, the
 * overlay ring's trigger). Pure Java — no Android imports — so the
 * JVM harness exercises the exact shipping code, and the shell
 * bridge (which runs from the same APK classpath under
 * app_process) uses this same class.
 *
 * A hold is one finger down, stationary within a small radius,
 * for longer than the hold threshold. Lift, or movement beyond
 * the radius, cancels the touch outright — a drag that pauses
 * mid-gesture never fires. Only the PRIMARY slot is tracked
 * (slot 0 / the first tracking id); other slots are ignored, so
 * a second finger neither fires nor cancels the first.
 *
 * Two input shapes are handled, auto-detected per stream:
 *   - multi-touch: ABS_MT_SLOT + ABS_MT_POSITION_X/Y +
 *     ABS_MT_TRACKING_ID (down = id assigned, up = id -1);
 *   - single-touch: BTN_TOUCH + ABS_X/ABS_Y.
 * Once any multi-touch code has been seen, single-touch codes
 * are ignored — many touchscreens mirror both, and the MT
 * stream is the authoritative one.
 *
 * PRIVACY: this parser reports hold events only — the anchor
 * point (normalized coordinates) and the fire time. It never
 * retains a touch trail: positions are held only as the current
 * point and the anchor, both overwritten continuously, and
 * nothing here logs, stores, or transmits anything else. The
 * bridge streams only the hold lines this parser emits.
 */
public final class HoldEventParser {

    // evdev codes (type, code) this parser understands.
    public static final int EV_SYN = 0x00;
    public static final int EV_KEY = 0x01;
    public static final int EV_ABS = 0x03;
    public static final int SYN_REPORT = 0x00;
    public static final int BTN_TOUCH = 0x14a;
    public static final int ABS_X = 0x00;
    public static final int ABS_Y = 0x01;
    public static final int ABS_MT_SLOT = 0x2f;
    public static final int ABS_MT_POSITION_X = 0x35;
    public static final int ABS_MT_POSITION_Y = 0x36;
    public static final int ABS_MT_TRACKING_ID = 0x39;

    /** One detected hold: the anchor point, normalized to the
     *  axis ranges (0..1 in the device's native orientation),
     *  and the stream time it fired at. */
    public static final class Hold {
        public final double nx;
        public final double ny;
        public final long atMs;

        public Hold(double nx, double ny, long atMs) {
            this.nx = nx;
            this.ny = ny;
            this.atMs = atMs;
        }
    }

    public interface Listener {
        void onHold(Hold hold);
    }

    /** {@code getevent -t} line:
     *  {@code [  1234.567890] /dev/input/event2: 0003 0035 00000438} */
    private static final Pattern LINE = Pattern.compile(
            "\\[\\s*(\\d+)\\.(\\d{1,6})\\]\\s+\\S+:\\s+([0-9a-fA-F]+)"
                    + "\\s+([0-9a-fA-F]+)\\s+([0-9a-fA-F]+)");

    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final double radiusNorm;
    private final long holdMs;
    private final Listener listener;

    private boolean seenMt;
    private int slot;
    private boolean down;
    private boolean cancelled;
    private boolean fired;
    private boolean havePos;
    private boolean anchorSet;
    private int posX;
    private int posY;
    private double anchorNx;
    private double anchorNy;
    private long anchorMs;

    /**
     * @param minX/maxX/minY/maxY the touchscreen axis ranges from
     *        {@code getevent -p} (device units).
     * @param radiusNorm  hold radius in normalized units (see
     *        {@link HoldCoordinateMapper#radiusNorm}).
     * @param holdMs      stationary time that fires a hold; the
     *        comparison is strictly greater-than.
     */
    public HoldEventParser(int minX, int maxX, int minY, int maxY,
                           double radiusNorm, long holdMs, Listener listener) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.radiusNorm = radiusNorm;
        this.holdMs = holdMs;
        this.listener = listener;
    }

    /** Feed one raw {@code getevent -t} line. Lines that do not
     *  parse (device announcements, other tools' output) are
     *  ignored silently. */
    public void feedLine(String line) {
        if (line == null) return;
        Matcher m = LINE.matcher(line.trim());
        if (!m.find()) return;
        long ms;
        try {
            ms = Long.parseLong(m.group(1)) * 1000L + fracToMs(m.group(2));
            int type = Integer.parseInt(m.group(3), 16);
            int code = Integer.parseInt(m.group(4), 16);
            int value = (int) Long.parseLong(m.group(5), 16);
            feedEvent(type, code, value, ms);
        } catch (Exception ignored) {
            // A malformed event line is skipped, never fatal.
        }
    }

    private static long fracToMs(String frac) {
        String f = frac;
        while (f.length() < 6) f += "0";
        return Long.parseLong(f) / 1000L;
    }

    /** Feed one decoded event. Frames are delimited by SYN_REPORT;
     *  the hold is evaluated at frame boundaries only, so a
     *  position and its down-flag in the same frame are judged
     *  together. */
    public void feedEvent(int type, int code, int value, long timeMs) {
        if (type == EV_SYN && code == SYN_REPORT) {
            evaluateFrame(timeMs);
            return;
        }
        if (type == EV_ABS) {
            switch (code) {
                case ABS_MT_SLOT:
                    slot = value;
                    return;
                case ABS_MT_POSITION_X:
                    seenMt = true;
                    if (slot == 0) {
                        posX = value;
                        havePos = true;
                    }
                    return;
                case ABS_MT_POSITION_Y:
                    seenMt = true;
                    if (slot == 0) {
                        posY = value;
                        havePos = true;
                    }
                    return;
                case ABS_MT_TRACKING_ID:
                    seenMt = true;
                    if (slot != 0) return;
                    if (value >= 0) press();
                    else release();
                    return;
                case ABS_X:
                    if (!seenMt) {
                        posX = value;
                        havePos = true;
                    }
                    return;
                case ABS_Y:
                    if (!seenMt) {
                        posY = value;
                        havePos = true;
                    }
                    return;
                default:
                    return;
            }
        }
        if (type == EV_KEY && code == BTN_TOUCH && !seenMt) {
            if (value != 0) press();
            else release();
        }
    }

    private void press() {
        if (down) return;
        down = true;
        cancelled = false;
        fired = false;
        anchorSet = false;
    }

    private void release() {
        down = false;
        cancelled = false;
        fired = false;
        anchorSet = false;
        havePos = false;
    }

    private void evaluateFrame(long timeMs) {
        if (!down || cancelled || fired || !havePos) return;
        if (!anchorSet) {
            anchorNx = normX(posX);
            anchorNy = normY(posY);
            anchorMs = timeMs;
            anchorSet = true;
            return;
        }
        double dx = normX(posX) - anchorNx;
        double dy = normY(posY) - anchorNy;
        if (dx * dx + dy * dy > radiusNorm * radiusNorm) {
            cancelled = true;
            return;
        }
        if (timeMs - anchorMs > holdMs) {
            fired = true;
            if (listener != null) {
                listener.onHold(new Hold(anchorNx, anchorNy, timeMs));
            }
        }
    }

    private double normX(int x) {
        if (maxX <= minX) return 0.0;
        return (double) (x - minX) / (double) (maxX - minX);
    }

    private double normY(int y) {
        if (maxY <= minY) return 0.0;
        return (double) (y - minY) / (double) (maxY - minY);
    }

    /** Forget all stream state (a fresh {@code getevent} run). */
    public void reset() {
        seenMt = false;
        slot = 0;
        down = false;
        cancelled = false;
        fired = false;
        havePos = false;
        anchorSet = false;
    }
}
