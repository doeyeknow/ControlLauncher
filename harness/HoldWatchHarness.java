package app.control.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-device verification for the v1.14 hold-watch core,
 * exercising the exact shipping pure-Java code:
 *
 * Legs:
 *   1. PARSER (MT): a stationary finger fires exactly once, only
 *      strictly after the threshold; movement beyond the radius
 *      cancels; a lift before the threshold never fires; a new
 *      touch after a fire can fire again; other slots are
 *      ignored; single-touch codes are ignored once MT is seen.
 *   2. PARSER (ST): BTN_TOUCH + ABS_X/ABS_Y hold and cancel the
 *      same way.
 *   3. PARSER (lines): canned {@code getevent -t} text — noise
 *      lines ignored, ffffffff tracking id is a lift, fractional
 *      timestamps parse, malformed lines never throw.
 *   4. PROBE: {@code getevent -p} samples — MT touchscreen picked
 *      over a stylus digitizer, ST-only fallback, no device, and
 *      malformed input.
 *   5. MAPPER: all four rotations on a 1080x2400 panel (current
 *      size swapped at 90/270); the center maps to the center in
 *      every rotation; corners follow the documented convention;
 *      radiusNorm math.
 *   6. TIMER (v1.43): evaluateAt fires a still finger's hold
 *      with no further frames (change-only hardware), never
 *      before the threshold, never after a cancel or a lift,
 *      never twice when the frame path already fired, and a
 *      second touch can fire after the first.
 *   7. COUNTERS (v1.44): the parser counts SYN_REPORT frames
 *      and holds fired; a cancelled touch grows frames but
 *      not holds; a timer-path fire grows holds with no new
 *      frame; reset() zeroes both; the feedLine path counts
 *      frames from canned getevent text and ignores noise.
 *   8. STATUS (v1.44): HoldWatchStatus.parse tolerates
 *      missing fields and malformed input; deviceShort
 *      prefers the device name; formatLine composes the
 *      Settings second line — compact with bridge data,
 *      v1.43 wording without it, empty with the switch off.
 *
 * Run: compile the three shipping classes + this file with javac
 * and run the main method. No Android classes, no network.
 */
public class HoldWatchHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        parserMt();
        parserSt();
        parserLines();
        probe();
        mapper();
        timerEval();
        counters();
        status();
        System.out.println("HoldWatchHarness: " + checks + " checks, "
                + failures + " failures");
        for (String f : failed) System.out.println("  FAIL: " + f);
        if (failures > 0) System.exit(1);
    }

    // ---- helpers ----

    private static void check(boolean cond, String name) {
        checks++;
        if (!cond) {
            failures++;
            failed.add(name);
        }
    }

    private static void eq(double a, double b, double eps, String name) {
        check(Math.abs(a - b) <= eps, name + " (got " + a + ", want " + b + ")");
    }

    private static void eq(String a, String b, String name) {
        check(a.equals(b), name + " (got \"" + a + "\", want \"" + b + "\")");
    }

    private static final class Sink implements HoldEventParser.Listener {
        final List<HoldEventParser.Hold> holds = new ArrayList<>();

        @Override public void onHold(HoldEventParser.Hold hold) {
            holds.add(hold);
        }
    }

    /** Parser over a 1080x2400 axis range, 0.03 radius, 1000ms. */
    private static HoldEventParser parser(Sink sink) {
        return new HoldEventParser(0, 1079, 0, 2399, 0.03, 1000L, sink);
    }

    private static void syn(HoldEventParser p, long ms) {
        p.feedEvent(HoldEventParser.EV_SYN, HoldEventParser.SYN_REPORT, 0, ms);
    }

    private static void mtDown(HoldEventParser p, int x, int y, long ms) {
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_TRACKING_ID, 7, ms);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_POSITION_X, x, ms);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_POSITION_Y, y, ms);
        syn(p, ms);
    }

    private static void mtMove(HoldEventParser p, int x, int y, long ms) {
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_POSITION_X, x, ms);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_POSITION_Y, y, ms);
        syn(p, ms);
    }

    private static void mtUp(HoldEventParser p, long ms) {
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_TRACKING_ID, -1, ms);
        syn(p, ms);
    }

    // ---- leg 1: MT parser ----

    private static void parserMt() {
        // Stationary hold fires once, strictly after the threshold.
        Sink s = new Sink();
        HoldEventParser p = parser(s);
        mtDown(p, 540, 1200, 1000);
        mtMove(p, 540, 1200, 1500);
        check(s.holds.isEmpty(), "mt: no fire at 500ms");
        mtMove(p, 540, 1200, 2000);
        check(s.holds.isEmpty(), "mt: no fire at exactly 1000ms");
        mtMove(p, 540, 1200, 2001);
        check(s.holds.size() == 1, "mt: fires at 1001ms");
        eq(s.holds.get(0).nx, 540.0 / 1079.0, 1e-9, "mt: hold nx is the anchor");
        eq(s.holds.get(0).ny, 1200.0 / 2399.0, 1e-9, "mt: hold ny is the anchor");
        mtMove(p, 540, 1200, 3000);
        check(s.holds.size() == 1, "mt: fires exactly once per touch");
        mtUp(p, 3100);

        // A tiny wobble inside the radius still fires.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        mtMove(p, 545, 1205, 600);
        mtMove(p, 538, 1198, 1200);
        check(s.holds.size() == 1, "mt: wobble inside radius still fires");

        // Movement beyond the radius cancels, permanently.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        mtMove(p, 700, 1200, 500);
        mtMove(p, 700, 1200, 2500);
        check(s.holds.isEmpty(), "mt: drag past radius cancels");
        mtUp(p, 2600);
        // A fresh touch after the cancelled one can fire.
        mtDown(p, 540, 1200, 3000);
        mtMove(p, 540, 1200, 4100);
        check(s.holds.size() == 1, "mt: fresh touch after cancel fires");

        // Lift before the threshold never fires.
        s = new Sink();
        p = parser(s);
        mtDown(p, 100, 100, 0);
        mtUp(p, 900);
        check(s.holds.isEmpty(), "mt: quick tap never fires");

        // A second finger on slot 1 neither fires nor cancels slot 0.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_SLOT, 1, 100);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_TRACKING_ID, 8, 100);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_POSITION_X, 100, 100);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_POSITION_Y, 100, 100);
        syn(p, 100);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_SLOT, 0, 200);
        mtMove(p, 540, 1200, 1200);
        check(s.holds.size() == 1, "mt: slot 1 ignored, slot 0 fires");
        eq(s.holds.get(0).nx, 540.0 / 1079.0, 1e-9, "mt: slot 1 position never anchors");

        // Single-touch codes ignored once MT has been seen.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_X, 50, 100);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_Y, 60, 100);
        syn(p, 100);
        mtMove(p, 540, 1200, 1200);
        check(s.holds.size() == 1, "mt: mirrored ST codes do not move the anchor");

        // No position, no fire (a ring needs a place to open).
        s = new Sink();
        p = parser(s);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_MT_TRACKING_ID, 3, 0);
        syn(p, 0);
        syn(p, 2000);
        check(s.holds.isEmpty(), "mt: down without any position never fires");
        mtUp(p, 2100);

        // Reset clears a half-held touch.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        p.reset();
        mtMove(p, 540, 1200, 2000);
        check(s.holds.isEmpty(), "mt: reset clears stream state");
    }

    // ---- leg 2: ST parser ----

    private static void parserSt() {
        Sink s = new Sink();
        HoldEventParser p = parser(s);
        p.feedEvent(HoldEventParser.EV_KEY, HoldEventParser.BTN_TOUCH, 1, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_X, 300, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_Y, 900, 0);
        syn(p, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_X, 300, 500);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_Y, 900, 500);
        syn(p, 500);
        check(s.holds.isEmpty(), "st: no fire at 500ms");
        syn(p, 1100);
        check(s.holds.size() == 1, "st: fires after threshold");
        eq(s.holds.get(0).nx, 300.0 / 1079.0, 1e-9, "st: hold nx is the anchor");
        p.feedEvent(HoldEventParser.EV_KEY, HoldEventParser.BTN_TOUCH, 0, 1200);
        syn(p, 1200);

        // ST drag cancels.
        s = new Sink();
        p = parser(s);
        p.feedEvent(HoldEventParser.EV_KEY, HoldEventParser.BTN_TOUCH, 1, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_X, 300, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_Y, 900, 0);
        syn(p, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_X, 900, 300);
        syn(p, 300);
        syn(p, 2000);
        check(s.holds.isEmpty(), "st: drag past radius cancels");
    }

    // ---- leg 3: raw lines ----

    private static void parserLines() {
        Sink s = new Sink();
        HoldEventParser p = parser(s);
        String[] stream = {
                "add device 1: /dev/input/event2",
                "[  1234.100000] /dev/input/event2: 0003 0039 00000000",
                "[  1234.100000] /dev/input/event2: 0003 0035 0000021c",
                "[  1234.100000] /dev/input/event2: 0003 0036 000004b0",
                "[  1234.100000] /dev/input/event2: 0003 003a 00000032",
                "[  1234.100000] /dev/input/event2: 0000 0000 00000000",
                "[  1234.600000] /dev/input/event2: 0000 0000 00000000",
                "this line is not an event",
                "[  1235.200000] /dev/input/event2: 0000 0000 00000000",
                "[  1235.300000] /dev/input/event2: 0003 0039 ffffffff",
                "[  1235.300000] /dev/input/event2: 0000 0000 00000000",
        };
        for (String line : stream) p.feedLine(line);
        check(s.holds.size() == 1, "lines: canned MT stream fires once");
        eq(s.holds.get(0).nx, 540.0 / 1079.0, 1e-9, "lines: hex position parses");
        eq(s.holds.get(0).ny, 1200.0 / 2399.0, 1e-9, "lines: hex y parses");
        check(s.holds.get(0).atMs == 1235200L, "lines: timestamp parses to ms");

        // Fractional timestamps with fewer digits, and a second touch.
        s = new Sink();
        p = parser(s);
        p.feedLine("[  10.5] /dev/input/event2: 0003 0039 00000001");
        p.feedLine("[  10.5] /dev/input/event2: 0003 0035 00000064");
        p.feedLine("[  10.5] /dev/input/event2: 0003 0036 00000064");
        p.feedLine("[  10.5] /dev/input/event2: 0000 0000 00000000");
        p.feedLine("[  11.6] /dev/input/event2: 0000 0000 00000000");
        check(s.holds.size() == 1, "lines: short fraction parses (10.5 -> 11.6)");
        check(s.holds.get(0).atMs == 11600L, "lines: short fraction ms value");

        // Null / empty / garbage never throws.
        p.feedLine(null);
        p.feedLine("");
        p.feedLine("[broken");
        check(s.holds.size() == 1, "lines: garbage lines are inert");
    }

    // ---- leg 4: probe ----

    private static final String GETEVENT_P =
            "add device 1: /dev/input/event0\n"
            + "  bus:      0018\n"
            + "  name:     \"sec_touchscreen\"\n"
            + "  events:\n"
            + "    EV_KEY (0001): 014a  0000\n"
            + "    EV_ABS (0003): 0000  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0\n"
            + "                    0001  : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0\n"
            + "                    0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0\n"
            + "                    0036  : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0\n"
            + "                    0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0\n"
            + "add device 2: /dev/input/event1\n"
            + "  name:     \"gpio_keys\"\n"
            + "  events:\n"
            + "    EV_KEY (0001): 0072  0073\n"
            + "add device 3: /dev/input/event5\n"
            + "  name:     \"sec_e-pen\"\n"
            + "  events:\n"
            + "    EV_ABS (0003): 0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0\n"
            + "                    0036  : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0\n"
            + "                    0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0\n";

    private static final String GETEVENT_P_ST_ONLY =
            "add device 1: /dev/input/event3\n"
            + "  name:     \"touchpanel\"\n"
            + "  events:\n"
            + "    EV_KEY (0001): 014a\n"
            + "    EV_ABS (0003): 0000  : value 0, min 0, max 720, fuzz 0, flat 0, resolution 0\n"
            + "                    0001  : value 0, min 0, max 1280, fuzz 0, flat 0, resolution 0\n";

    private static void probe() {
        List<TouchDeviceProbe.Device> devs = TouchDeviceProbe.parse(GETEVENT_P);
        check(devs.size() == 2, "probe: touchscreen + stylus qualify, keys do not");
        TouchDeviceProbe.Device picked = TouchDeviceProbe.pick(devs);
        check(picked != null, "probe: a device is picked");
        check("/dev/input/event0".equals(picked.path),
                "probe: touch-named MT device wins over the stylus");
        check(picked.multiTouch, "probe: picked device is multi-touch");
        check(picked.minX == 0 && picked.maxX == 1079, "probe: x range parses");
        check(picked.minY == 0 && picked.maxY == 2399, "probe: y range parses");

        List<TouchDeviceProbe.Device> st = TouchDeviceProbe.parse(GETEVENT_P_ST_ONLY);
        check(st.size() == 1, "probe: ST-only panel qualifies");
        check(!st.get(0).multiTouch, "probe: ST panel is single-touch");
        check(st.get(0).maxX == 720 && st.get(0).maxY == 1280,
                "probe: ST ranges parse");

        check(TouchDeviceProbe.parse("").isEmpty(), "probe: empty output, no devices");
        check(TouchDeviceProbe.parse(null).isEmpty(), "probe: null output, no devices");
        check(TouchDeviceProbe.pick(new ArrayList<>()) == null,
                "probe: pick of nothing is null");
        check(TouchDeviceProbe.parse("add device 1: /dev/input/event9\n"
                + "  name:     \"mystery\"\n").isEmpty(),
                "probe: device without axes does not qualify");
        // MT axes without a tracking id do not qualify.
        check(TouchDeviceProbe.parse("add device 1: /dev/input/event9\n"
                + "  name:     \"touchscreen\"\n"
                + "  events:\n"
                + "    EV_ABS (0003): 0035  : value 0, min 0, max 100, fuzz 0\n"
                + "                    0036  : value 0, min 0, max 100, fuzz 0\n").isEmpty(),
                "probe: MT axes without tracking id do not qualify");
        // A zero range does not qualify.
        check(TouchDeviceProbe.parse("add device 1: /dev/input/event9\n"
                + "  name:     \"touchscreen\"\n"
                + "  events:\n"
                + "    EV_KEY (0001): 014a\n"
                + "    EV_ABS (0003): 0000  : value 0, min 0, max 0, fuzz 0\n"
                + "                    0001  : value 0, min 0, max 100, fuzz 0\n").isEmpty(),
                "probe: zero-range axis does not qualify");
    }

    // ---- leg 5: mapper ----

    private static void mapper() {
        // Natural 1080x2400; current size swaps at 90/270.
        float[] r0 = HoldCoordinateMapper.map(0.5, 0.5,
                HoldCoordinateMapper.ROTATION_0, 1080, 2400);
        eq(r0[0], 540f, 0.01f, "map: rotation 0 center x");
        eq(r0[1], 1200f, 0.01f, "map: rotation 0 center y");
        float[] r90 = HoldCoordinateMapper.map(0.5, 0.5,
                HoldCoordinateMapper.ROTATION_90, 2400, 1080);
        eq(r90[0], 1200f, 0.01f, "map: rotation 90 center x");
        eq(r90[1], 540f, 0.01f, "map: rotation 90 center y");
        float[] r180 = HoldCoordinateMapper.map(0.5, 0.5,
                HoldCoordinateMapper.ROTATION_180, 1080, 2400);
        eq(r180[0], 540f, 0.01f, "map: rotation 180 center x");
        eq(r180[1], 1200f, 0.01f, "map: rotation 180 center y");
        float[] r270 = HoldCoordinateMapper.map(0.5, 0.5,
                HoldCoordinateMapper.ROTATION_270, 2400, 1080);
        eq(r270[0], 1200f, 0.01f, "map: rotation 270 center x");
        eq(r270[1], 540f, 0.01f, "map: rotation 270 center y");

        // Corners, per the documented clockwise convention.
        float[] c0 = HoldCoordinateMapper.map(0.0, 0.0,
                HoldCoordinateMapper.ROTATION_0, 1080, 2400);
        eq(c0[0], 0f, 0.01f, "map: r0 top-left x");
        eq(c0[1], 0f, 0.01f, "map: r0 top-left y");
        float[] c90 = HoldCoordinateMapper.map(0.0, 0.0,
                HoldCoordinateMapper.ROTATION_90, 2400, 1080);
        eq(c90[0], 2400f, 0.01f, "map: r90 native top-left -> current top-right x");
        eq(c90[1], 0f, 0.01f, "map: r90 native top-left -> current top-right y");
        float[] c180 = HoldCoordinateMapper.map(0.0, 0.0,
                HoldCoordinateMapper.ROTATION_180, 1080, 2400);
        eq(c180[0], 1080f, 0.01f, "map: r180 native top-left -> bottom-right x");
        eq(c180[1], 2400f, 0.01f, "map: r180 native top-left -> bottom-right y");
        float[] c270 = HoldCoordinateMapper.map(0.0, 0.0,
                HoldCoordinateMapper.ROTATION_270, 2400, 1080);
        eq(c270[0], 0f, 0.01f, "map: r270 native top-left -> bottom-left x");
        eq(c270[1], 1080f, 0.01f, "map: r270 native top-left -> bottom-left y");

        // A non-center point rotates consistently: rotating the
        // device four quarter-turns returns to the start.
        float[] p0 = HoldCoordinateMapper.map(0.25, 0.75,
                HoldCoordinateMapper.ROTATION_0, 1080, 2400);
        eq(p0[0], 270f, 0.01f, "map: r0 quarter point x");
        eq(p0[1], 1800f, 0.01f, "map: r0 quarter point y");
        float[] p90 = HoldCoordinateMapper.map(0.25, 0.75,
                HoldCoordinateMapper.ROTATION_90, 2400, 1080);
        eq(p90[0], 600f, 0.01f, "map: r90 quarter point x");
        eq(p90[1], 270f, 0.01f, "map: r90 quarter point y");

        // Radius math: 12dp at density 3 on a 1080-wide panel.
        eq(HoldCoordinateMapper.radiusNorm(3.0f, 1080, 2400, 12f),
                36.0 / 1080.0, 1e-9, "map: radiusNorm uses the short side");
        eq(HoldCoordinateMapper.radiusNorm(0f, 1080, 2400, 12f),
                0.03, 1e-9, "map: radiusNorm falls back on bad input");
        eq(HoldCoordinateMapper.radiusNorm(3.0f, 0, 0, 12f),
                0.03, 1e-9, "map: radiusNorm falls back on zero size");
    }

    // ---- leg 6: timer-driven evaluation (v1.43) ----

    private static void timerEval() {
        // A still finger fires with NO further frames: the down
        // frame sets the anchor, the timer judges the rest.
        Sink s = new Sink();
        HoldEventParser p = parser(s);
        mtDown(p, 540, 1200, 1000);
        p.evaluateAt(1500);
        check(s.holds.isEmpty(), "timer: no fire at 500ms");
        p.evaluateAt(2000);
        check(s.holds.isEmpty(), "timer: no fire at exactly 1000ms");
        p.evaluateAt(2001);
        check(s.holds.size() == 1, "timer: fires at 1001ms with no frames");
        eq(s.holds.get(0).nx, 540.0 / 1079.0, 1e-9, "timer: hold nx is the anchor");
        eq(s.holds.get(0).ny, 1200.0 / 2399.0, 1e-9, "timer: hold ny is the anchor");
        p.evaluateAt(3000);
        check(s.holds.size() == 1, "timer: fires exactly once per touch");
        mtUp(p, 3100);

        // The frame path firing first does not double-fire.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        mtMove(p, 540, 1200, 1500);
        check(s.holds.size() == 1, "timer: frame path fires first");
        p.evaluateAt(2000);
        check(s.holds.size() == 1, "timer: no second fire after a frame fire");
        mtUp(p, 2100);

        // A cancel (movement past the radius) blocks the timer.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        mtMove(p, 700, 1200, 500);
        p.evaluateAt(2500);
        check(s.holds.isEmpty(), "timer: drag past radius cancels");
        mtUp(p, 2600);

        // A lift before the threshold blocks the timer.
        s = new Sink();
        p = parser(s);
        mtDown(p, 100, 100, 0);
        mtUp(p, 900);
        p.evaluateAt(2000);
        check(s.holds.isEmpty(), "timer: quick tap never fires");

        // A time earlier than the anchor never fires.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 5000);
        p.evaluateAt(1000);
        check(s.holds.isEmpty(), "timer: earlier time never fires");
        p.evaluateAt(6001);
        check(s.holds.size() == 1, "timer: later time fires after an early probe");
        mtUp(p, 6100);

        // A second touch can fire via the timer after the first.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        p.evaluateAt(1500);
        check(s.holds.size() == 1, "timer: first touch fires");
        mtUp(p, 1600);
        mtDown(p, 200, 200, 3000);
        p.evaluateAt(4001);
        check(s.holds.size() == 2, "timer: second touch fires");
        eq(s.holds.get(1).nx, 200.0 / 1079.0, 1e-9, "timer: second hold nx is its anchor");
        mtUp(p, 4100);

        // Single-touch holds fire via the timer too.
        s = new Sink();
        p = parser(s);
        p.feedEvent(HoldEventParser.EV_KEY, HoldEventParser.BTN_TOUCH, 1, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_X, 300, 0);
        p.feedEvent(HoldEventParser.EV_ABS, HoldEventParser.ABS_Y, 400, 0);
        syn(p, 0);
        p.evaluateAt(1001);
        check(s.holds.size() == 1, "timer: single-touch fires with no frames");
    }

    // ---- leg 7: stream counters (v1.44) ----

    private static void counters() {
        // A fresh parser counts nothing.
        Sink s = new Sink();
        HoldEventParser p = parser(s);
        check(p.framesSeen() == 0, "counters: fresh parser, 0 frames");
        check(p.holdsFired() == 0, "counters: fresh parser, 0 holds");

        // Each SYN_REPORT is one frame; events between frames
        // do not count.
        mtDown(p, 540, 1200, 1000);
        check(p.framesSeen() == 1, "counters: down frame counts 1");
        check(p.holdsFired() == 0, "counters: no hold before the threshold");
        mtMove(p, 540, 1200, 1500);
        mtMove(p, 540, 1200, 2001);
        check(p.framesSeen() == 3, "counters: three frames after two moves");
        check(p.holdsFired() == 1, "counters: frame-path fire counts 1");
        check(s.holds.size() == 1, "counters: one hold delivered");
        mtUp(p, 2100);
        check(p.framesSeen() == 4, "counters: lift frame counts too");

        // A cancelled touch grows frames but never holds.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        mtMove(p, 700, 1200, 500);
        mtMove(p, 700, 1200, 2500);
        mtUp(p, 2600);
        check(p.framesSeen() == 4, "counters: cancelled touch still counts frames");
        check(p.holdsFired() == 0, "counters: cancelled touch fires nothing");

        // A timer-path fire grows holds with no new frame.
        s = new Sink();
        p = parser(s);
        mtDown(p, 540, 1200, 0);
        p.evaluateAt(1500);
        check(p.framesSeen() == 1, "counters: timer fire adds no frame");
        check(p.holdsFired() == 1, "counters: timer fire counts 1 hold");
        mtUp(p, 1600);
        check(p.framesSeen() == 2, "counters: lift frame after a timer fire");

        // reset() zeroes both counters with the stream state.
        p.reset();
        check(p.framesSeen() == 0, "counters: reset zeroes frames");
        check(p.holdsFired() == 0, "counters: reset zeroes holds");

        // The feedLine path: canned getevent text — one SYN
        // line is one frame, noise lines count nothing.
        s = new Sink();
        p = parser(s);
        p.feedLine("[  1234.567890] /dev/input/event2: 0003 0039 00000007");
        p.feedLine("[  1234.567890] /dev/input/event2: 0003 0035 0000021c");
        p.feedLine("[  1234.567890] /dev/input/event2: 0003 0036 000004b0");
        p.feedLine("add device 1: /dev/input/event2");
        p.feedLine("not an event line at all");
        check(p.framesSeen() == 0, "counters: events and noise are not frames");
        p.feedLine("[  1234.567890] /dev/input/event2: 0000 0000 00000000");
        check(p.framesSeen() == 1, "counters: SYN_REPORT line counts 1 frame");
        p.feedLine("[  1235.568890] /dev/input/event2: 0000 0000 00000000");
        check(p.framesSeen() == 2, "counters: second SYN line counts 2 frames");
        check(p.holdsFired() == 1, "counters: line-fed hold fires and counts");
    }

    // ---- leg 8: hold-watch status (v1.44) ----

    private static void status() {
        // Parse: a full payload round-trips every field.
        HoldWatchStatus full = HoldWatchStatus.parse(
                "{\"active\":true,\"device\":\"/dev/input/event2 (sec_touchscreen)\","
                        + "\"frames\":12,\"holds\":0,\"endReason\":\"\"}");
        check(full != null, "status: full payload parses");
        check(full.active, "status: active parses");
        eq(full.device, "/dev/input/event2 (sec_touchscreen)", "status: device parses");
        check(full.frames == 12, "status: frames parse");
        check(full.holds == 0, "status: holds parse");
        eq(full.endReason, "", "status: endReason parses");

        // Parse: missing fields get honest defaults, never an
        // exception; an ended watch with a probe failure parses.
        HoldWatchStatus sparse = HoldWatchStatus.parse("{\"active\":false}");
        check(sparse != null, "status: sparse payload parses");
        check(!sparse.active, "status: sparse active defaults false");
        eq(sparse.device, "", "status: sparse device defaults empty");
        check(sparse.frames == -1, "status: sparse frames default unknown");
        check(sparse.holds == -1, "status: sparse holds default unknown");
        HoldWatchStatus failed = HoldWatchStatus.parse(
                "{\"active\":false,\"device\":\"\",\"frames\":0,\"holds\":0,"
                        + "\"endReason\":\"No touchscreen input device found.\"}");
        eq(failed.endReason, "No touchscreen input device found.",
                "status: probe failure parses");

        // Parse: nothing to parse yields null (the caller falls
        // back to the v1.43 line), never an exception.
        check(HoldWatchStatus.parse(null) == null, "status: null parses to null");
        check(HoldWatchStatus.parse("") == null, "status: empty parses to null");
        check(HoldWatchStatus.parse("   ") == null, "status: blank parses to null");
        check(HoldWatchStatus.parse("not json") == null, "status: junk parses to null");
        check(HoldWatchStatus.parse("{bad") == null, "status: broken object parses to null");
        check(HoldWatchStatus.parse("[1,2]") == null, "status: array parses to null");

        // deviceShort: the name wins, then the node, then "".
        eq(HoldWatchStatus.deviceShort("/dev/input/event2 (sec_touchscreen)"),
                "sec_touchscreen", "status: deviceShort takes the name");
        eq(HoldWatchStatus.deviceShort("/dev/input/event2"),
                "event2", "status: deviceShort falls back to the node");
        eq(HoldWatchStatus.deviceShort("event5"),
                "event5", "status: deviceShort keeps a bare name");
        eq(HoldWatchStatus.deviceShort("/dev/input/event2 ()"),
                "event2", "status: deviceShort ignores empty parens");
        eq(HoldWatchStatus.deviceShort("  /dev/input/event3 (fts_ts)  "),
                "fts_ts", "status: deviceShort trims");
        eq(HoldWatchStatus.deviceShort(""), "", "status: deviceShort of empty is empty");
        eq(HoldWatchStatus.deviceShort(null), "", "status: deviceShort of null is empty");

        // formatLine: switch off is always empty.
        eq(HoldWatchStatus.formatLine(false, true, "", 0, 0, "", full, 100000L),
                "", "status: switch off formats empty");

        // formatLine: running + bridge data is one compact line.
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 0, "", full, 100000L),
                "watch running · sec_touchscreen · 0 holds · 12 frames",
                "status: running line with bridge data");
        HoldWatchStatus one = new HoldWatchStatus(true,
                "/dev/input/event2 (sec_touchscreen)", 1, 1, "");
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 0, "", one, 100000L),
                "watch running · sec_touchscreen · 1 hold · 1 frame",
                "status: singular hold and frame");
        HoldWatchStatus unknown = new HoldWatchStatus(true, "", -1, -1, "");
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 0, "", unknown, 100000L),
                "watch running · no device · holds ? · frames ?",
                "status: unknown counts show as ?");
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 0,
                "BadTokenException", full, 100000L),
                "watch running · sec_touchscreen · 0 holds · 12 frames"
                        + " · window failed: BadTokenException",
                "status: window failure appends to the compact line");

        // formatLine: without bridge data, the v1.43 wording.
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 0, "", null, 100000L),
                "watch running · no hold seen yet · window ok",
                "status: no-status line keeps the v1.43 wording");
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 99990L, "", null, 100000L),
                "watch running · last hold just now · window ok",
                "status: recent hold reads just now");
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 40000L, "", null, 100000L),
                "watch running · last hold 1m ago · window ok",
                "status: minute-old hold reads 1m ago");
        eq(HoldWatchStatus.formatLine(true, true, "", 0, 100000L - 3600000L,
                "BadTokenException", null, 100000L),
                "watch running · last hold 1h ago · window failed: BadTokenException",
                "status: hour-old hold and window failure");

        // formatLine: an ended watch names its reason and age.
        eq(HoldWatchStatus.formatLine(true, false,
                "The bridge ended the hold watch.", 100000L - 120000L,
                0, "", full, 100000L),
                "watch ended 2m ago: The bridge ended the hold watch."
                        + " · sec_touchscreen · 0 holds · 12 frames",
                "status: ended watch names reason, age, and counts");
        eq(HoldWatchStatus.formatLine(true, false,
                "The bridge ended the hold watch.", 0, 0, "", null, 100000L),
                "watch ended: The bridge ended the hold watch."
                        + " · no hold seen yet · window ok",
                "status: ended watch without a time omits the age");
        eq(HoldWatchStatus.formatLine(true, false, "", 0, 0, "", null, 100000L),
                "watch not started · no hold seen yet · window ok",
                "status: never-started watch reads not started");
    }
}
