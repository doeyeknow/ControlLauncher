package app.control.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Touchscreen discovery from {@code getevent -p} output (v1.14).
 * Pure Java — no Android imports — so the JVM harness exercises
 * the exact shipping code, and the shell bridge uses this same
 * class to find the touchscreen without ever hardcoding an event
 * node.
 *
 * {@code getevent -p} prints one block per input device:
 * <pre>
 * add device 1: /dev/input/event2
 *   name:     "sec_touchscreen"
 *   events:
 *     EV_KEY (0001): 014a ...
 *     EV_ABS (0003): 0000  : value 0, min 0, max 1079, fuzz 0, ...
 *                     0035  : value 0, min 0, max 1079, ...
 *                     0036  : value 0, min 0, max 2399, ...
 *                     0039  : value 0, min 0, max 65535, ...
 * </pre>
 * A device qualifies as a touchscreen when it reports a position
 * pair with a real range plus its down signal: multi-touch needs
 * ABS_MT_POSITION_X/Y (0035/0036) + ABS_MT_TRACKING_ID (0039);
 * single-touch needs ABS_X/Y (0000/0001) + BTN_TOUCH (014a).
 *
 * Selection prefers multi-touch over single-touch, and among
 * equals a device whose name mentions touch (a stylus digitizer
 * reports MT axes too, and must not win over the touchscreen
 * when both are listed). Ties go to the first device listed.
 */
public final class TouchDeviceProbe {

    /** One qualifying input device: its node, its name, which
     *  protocol it speaks, and the position axis ranges the hold
     *  parser normalizes against. */
    public static final class Device {
        public final String path;
        public final String name;
        public final boolean multiTouch;
        public final int minX;
        public final int maxX;
        public final int minY;
        public final int maxY;

        public Device(String path, String name, boolean multiTouch,
                      int minX, int maxX, int minY, int maxY) {
            this.path = path;
            this.name = name;
            this.multiTouch = multiTouch;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    private static final Pattern ADD = Pattern.compile(
            "^add device \\d+:\\s+(\\S+)");
    private static final Pattern NAME = Pattern.compile(
            "^\\s*name:\\s+\"(.*)\"");
    /** Unanchored on purpose: the first axis entry shares its
     *  line with the {@code EV_ABS (0003):} header, so the code
     *  is not at the start of the line. */
    private static final Pattern AXIS = Pattern.compile(
            "([0-9a-fA-F]{4})\\s*:\\s*value\\s+(-?\\d+)\\s*,\\s*min\\s+(-?\\d+)"
                    + "\\s*,\\s*max\\s+(-?\\d+)");
    private static final Pattern KEY_LINE = Pattern.compile(
            "EV_KEY\\s*\\(0001\\)\\s*:(.*)");

    private TouchDeviceProbe() {}

    /** Parse every device block; only qualifying touchscreens are
     *  returned, in listing order. Unparseable output yields an
     *  empty list, never an exception. */
    public static List<Device> parse(String output) {
        List<Device> out = new ArrayList<>();
        if (output == null) return out;
        Builder cur = null;
        for (String raw : output.split("\\n")) {
            String line = raw.trim();
            Matcher add = ADD.matcher(line);
            if (add.find()) {
                if (cur != null) cur.finish(out);
                cur = new Builder(add.group(1));
                continue;
            }
            if (cur == null) continue;
            Matcher name = NAME.matcher(line);
            if (name.find()) {
                cur.name = name.group(1);
                continue;
            }
            Matcher axis = AXIS.matcher(line);
            if (axis.find()) {
                int code;
                try {
                    code = Integer.parseInt(axis.group(1), 16);
                } catch (Exception e) {
                    continue;
                }
                int min;
                int max;
                try {
                    min = Integer.parseInt(axis.group(3));
                    max = Integer.parseInt(axis.group(4));
                } catch (Exception e) {
                    continue;
                }
                cur.axis(code, min, max);
                continue;
            }
            Matcher keys = KEY_LINE.matcher(line);
            if (keys.find()) {
                for (String token : keys.group(1).trim().split("\\s+")) {
                    if ("014a".equalsIgnoreCase(token)) cur.btnTouch = true;
                }
            }
        }
        if (cur != null) cur.finish(out);
        return out;
    }

    /** Pick the touchscreen to watch: multi-touch first, a
     *  touch-named device first among equals, else the first
     *  qualifying device. Null when none qualifies. */
    public static Device pick(List<Device> devices) {
        Device best = null;
        int bestScore = -1;
        for (Device d : devices) {
            int score = (d.multiTouch ? 2 : 0)
                    + (d.name != null && d.name.toLowerCase().contains("touch") ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static final class Builder {
        final String path;
        String name = "";
        boolean btnTouch;
        boolean hasMtX, hasMtY, hasTracking;
        boolean hasStX, hasStY;
        int mtMinX, mtMaxX, mtMinY, mtMaxY;
        int stMinX, stMaxX, stMinY, stMaxY;

        Builder(String path) {
            this.path = path;
        }

        void axis(int code, int min, int max) {
            switch (code) {
                case 0x35:
                    hasMtX = true;
                    mtMinX = min;
                    mtMaxX = max;
                    break;
                case 0x36:
                    hasMtY = true;
                    mtMinY = min;
                    mtMaxY = max;
                    break;
                case 0x39:
                    hasTracking = true;
                    break;
                case 0x00:
                    hasStX = true;
                    stMinX = min;
                    stMaxX = max;
                    break;
                case 0x01:
                    hasStY = true;
                    stMinY = min;
                    stMaxY = max;
                    break;
                default:
                    break;
            }
        }

        void finish(List<Device> out) {
            if (path == null || !path.startsWith("/dev/input/event")) return;
            if (hasMtX && hasMtY && hasTracking && mtMaxX > mtMinX && mtMaxY > mtMinY) {
                out.add(new Device(path, name, true, mtMinX, mtMaxX, mtMinY, mtMaxY));
            } else if (hasStX && hasStY && btnTouch && stMaxX > stMinX && stMaxY > stMinY) {
                out.add(new Device(path, name, false, stMinX, stMaxX, stMinY, stMaxY));
            }
        }
    }
}
