package app.control.launcher;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-device verification for the v1.15.1 stale-bridge fix,
 * exercising the exact shipping pure-Java code (BridgeGate):
 *
 * Legs:
 *   1. VERSION PARSING: a reported version parses; a missing
 *      version is 0; a null object is 0; version 0 stays 0.
 *   2. CLASSIFY: a failed ping is NOT_RUNNING whatever the
 *      version; an answered ping below the required version
 *      (including 0 / unreported) is STALE; at or above it is
 *      CURRENT, including a future version.
 *   3. WANT-SERVICE: the overlay service may start only when
 *      the switch is on, the overlay permission is granted,
 *      AND the bridge is CURRENT — every other combination
 *      stays off, so a stale bridge never starts a watch that
 *      would just be refused and die again.
 *   4. STALE REFUSAL: a refused HOLD_WATCH ack from an old
 *      bridge (allowlist error, with or without a reported
 *      version) reads as stale; other refusals (no
 *      touchscreen, token, transport) and ok acks do not.
 *   5. STATE LINE: the Settings summary names the actual
 *      failing gate — switch, permission, stale bridge, bridge
 *      not running — and never says "Deep view is not running"
 *      for a bridge that is running; the checking / starting /
 *      on lines follow from the fresh ping plus liveness.
 *   6. VERSION FRAGMENTS: "v4 " / "v4" for a reported version,
 *      empty / "unversioned" when none was reported — status
 *      lines never read "v0".
 *
 * Run (from ~/workspace/control-launcher), with the real
 * org.json jar ONLY on the classpath — android.jar must not
 * come first, or its stub org.json shadows the real one:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar -d harness/classes \
 *     harness/BridgeGateHarness.java
 *   java -cp harness/classes:harness/org-json.jar \
 *     app.control.launcher.BridgeGateHarness
 *
 * What this harness cannot prove: the stop-then-start restart
 * against a real bridge, the reconcile/service lifecycle on a
 * phone, or the Settings row in hand — all of that is Adam's
 * on-device test.
 */
public class BridgeGateHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        versionParsing();
        classify();
        wantService();
        staleRefusal();
        stateLine();
        versionFragments();
        System.out.println("BridgeGateHarness: " + checks + " checks, "
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

    private static void eq(Object a, Object b, String name) {
        check(a == null ? b == null : a.equals(b),
                name + " (got " + a + ", want " + b + ")");
    }

    private static JSONObject obj(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            throw new RuntimeException("bad fixture JSON: " + json, e);
        }
    }

    // ---- legs ----

    private static void versionParsing() {
        eq(4, BridgeGate.parseVersion(obj("{\"ok\":true,\"version\":4}")),
                "reported v4 parses");
        eq(3, BridgeGate.parseVersion(obj("{\"ok\":true,\"version\":3}")),
                "reported v3 parses");
        eq(0, BridgeGate.parseVersion(obj("{\"ok\":true}")),
                "missing version is 0");
        eq(0, BridgeGate.parseVersion(obj("{\"ok\":false,\"error\":\"x\"}")),
                "missing version on an error is 0");
        eq(0, BridgeGate.parseVersion(obj("{\"ok\":true,\"version\":0}")),
                "reported version 0 stays 0");
        eq(0, BridgeGate.parseVersion(null), "null object is 0");
    }

    private static void classify() {
        eq(BridgeGate.State.NOT_RUNNING, BridgeGate.classify(false, 4),
                "failed ping is NOT_RUNNING even at v4");
        eq(BridgeGate.State.NOT_RUNNING, BridgeGate.classify(false, 0),
                "failed ping is NOT_RUNNING at v0");
        eq(BridgeGate.State.STALE, BridgeGate.classify(true, 3),
                "answered v3 is STALE");
        eq(BridgeGate.State.STALE, BridgeGate.classify(true, 0),
                "answered unreported version is STALE");
        eq(BridgeGate.State.STALE, BridgeGate.classify(true, 1),
                "answered v1 is STALE");
        eq(BridgeGate.State.CURRENT, BridgeGate.classify(true, 4),
                "answered v4 is CURRENT");
        eq(BridgeGate.State.CURRENT, BridgeGate.classify(true, 5),
                "answered future v5 is CURRENT");
        eq(4, BridgeGate.HOLD_WATCH_VERSION, "required version is 4");
    }

    private static void wantService() {
        check(BridgeGate.wantService(true, true, BridgeGate.State.CURRENT),
                "switch+permission+CURRENT starts the service");
        check(!BridgeGate.wantService(true, true, BridgeGate.State.STALE),
                "STALE never starts the service");
        check(!BridgeGate.wantService(true, true, BridgeGate.State.NOT_RUNNING),
                "NOT_RUNNING never starts the service");
        check(!BridgeGate.wantService(true, true, BridgeGate.State.UNKNOWN),
                "UNKNOWN never starts the service");
        check(!BridgeGate.wantService(false, true, BridgeGate.State.CURRENT),
                "switch off never starts the service");
        check(!BridgeGate.wantService(true, false, BridgeGate.State.CURRENT),
                "permission missing never starts the service");
        check(!BridgeGate.wantService(false, false, BridgeGate.State.STALE),
                "everything off stays off");
    }

    private static void staleRefusal() {
        check(BridgeGate.isStaleRefusal(obj(
                "{\"ok\":false,\"version\":3,"
                        + "\"error\":\"Operation is not on the bridge allowlist.\"}")),
                "v3 allowlist refusal is stale");
        check(BridgeGate.isStaleRefusal(obj(
                "{\"ok\":false,"
                        + "\"error\":\"Operation is not on the bridge allowlist.\"}")),
                "allowlist refusal without a version is stale");
        check(BridgeGate.isStaleRefusal(obj(
                "{\"ok\":false,\"version\":3,\"error\":\"something else\"}")),
                "v3 refusal with another error is stale by version");
        check(!BridgeGate.isStaleRefusal(obj(
                "{\"ok\":false,\"version\":4,"
                        + "\"error\":\"No touchscreen input device found.\"}")),
                "v4 no-touchscreen refusal is not stale");
        check(!BridgeGate.isStaleRefusal(obj(
                "{\"ok\":false,\"version\":4,"
                        + "\"error\":\"Hold watch refused: transport or token.\"}")),
                "v4 auth refusal is not stale");
        check(!BridgeGate.isStaleRefusal(obj("{\"ok\":true,\"op\":\"HOLD_WATCH\"}")),
                "ok ack is not a refusal");
        check(!BridgeGate.isStaleRefusal(obj("{\"ok\":false,\"error\":\"\"}")),
                "empty refusal is not stale");
        check(!BridgeGate.isStaleRefusal(null), "null ack is not stale");
    }

    private static void stateLine() {
        eq("Off — the switch is off",
                BridgeGate.stateLine(false, true, BridgeGate.State.CURRENT, true),
                "switch off wins over everything");
        eq("Off — overlay permission not granted (tap below to allow it)",
                BridgeGate.stateLine(true, false, BridgeGate.State.CURRENT, true),
                "permission missing named");
        eq("Off — the bridge is running an old version — restart it for hold-anywhere",
                BridgeGate.stateLine(true, true, BridgeGate.State.STALE, false),
                "stale bridge named, never 'not running'");
        eq("Off — the bridge is running an old version — restart it for hold-anywhere",
                BridgeGate.stateLine(true, true, BridgeGate.State.STALE, true),
                "stale bridge named even while the service is still up");
        eq("Off — Deep view is not running",
                BridgeGate.stateLine(true, true, BridgeGate.State.NOT_RUNNING, false),
                "bridge down named");
        eq("Checking the bridge…",
                BridgeGate.stateLine(true, true, BridgeGate.State.UNKNOWN, false),
                "no ping yet reads as checking");
        eq("On — hold anywhere for a second to open the ring",
                BridgeGate.stateLine(true, true, BridgeGate.State.UNKNOWN, true),
                "service alive with no ping yet reads as on");
        eq("Starting — the hold watch is starting…",
                BridgeGate.stateLine(true, true, BridgeGate.State.CURRENT, false),
                "current bridge, service not up yet reads as starting");
        eq("On — hold anywhere for a second to open the ring",
                BridgeGate.stateLine(true, true, BridgeGate.State.CURRENT, true),
                "current bridge + live service reads as on");
    }

    private static void versionFragments() {
        eq("v4 ", BridgeGate.versionFragment(4), "fragment v4");
        eq("v3 ", BridgeGate.versionFragment(3), "fragment v3");
        eq("", BridgeGate.versionFragment(0), "fragment empty at 0");
        eq("", BridgeGate.versionFragment(-1), "fragment empty below 0");
        eq("v4", BridgeGate.versionName(4), "name v4");
        eq("unversioned", BridgeGate.versionName(0), "name unversioned at 0");
    }
}
