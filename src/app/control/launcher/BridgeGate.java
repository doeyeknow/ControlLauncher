package app.control.launcher;

import org.json.JSONObject;

/**
 * Bridge-version gating (v1.15.1). Pure Java — no Android classes —
 * so the harness exercises the exact shipping code.
 *
 * Why this exists: the bridge is a separate app_process that
 * SURVIVES APK upgrades. After an update that bumps the bridge
 * protocol, the running bridge is still the OLD code: it answers
 * PING fine, but it has no HOLD_WATCH op, so the v1.14 overlay
 * watch was refused, the service stopped itself, and Settings
 * reported "Deep view is not running" for a bridge that was
 * running. The client never read the version the bridge already
 * sends in every response, so nothing could tell "stale bridge"
 * from "no bridge".
 *
 * The gate is three states, from one ping:
 *   NOT_RUNNING — the ping failed: no bridge at all.
 *   STALE       — the ping answered, but the bridge version is
 *                 below HOLD_WATCH_VERSION (a missing version
 *                 counts as 0, hence stale: a bridge too old to
 *                 report its version cannot offer the watch).
 *   CURRENT     — the ping answered at or above the required
 *                 version. Only CURRENT may start the overlay
 *                 service.
 *
 * HOLD_WATCH_VERSION must equal ShellBridgeMain.VERSION in the
 * APK that ships the hold watch. If the bridge protocol bumps
 * again, bump both, and remember the running bridge still needs
 * a restart before the new op exists on the phone (see
 * AdbStarter.restartStaleBridge and BUILD_NOTES).
 */
public final class BridgeGate {

    /** Minimum bridge protocol version that offers HOLD_WATCH. */
    public static final int HOLD_WATCH_VERSION = 4;

    public enum State {
        /** No ping has completed yet this process lifetime. */
        UNKNOWN,
        /** The ping failed: the bridge is not running. */
        NOT_RUNNING,
        /** The bridge answered, but too old for the hold watch. */
        STALE,
        /** The bridge answered at the required version. */
        CURRENT
    }

    private BridgeGate() {}

    /**
     * The bridge version from a response object. A missing,
     * null, or unparseable version is 0 — stale by definition.
     */
    public static int parseVersion(JSONObject response) {
        if (response == null) return 0;
        return response.optInt("version", 0);
    }

    /** Classify one ping result into the three gate states. */
    public static State classify(boolean pingOk, int version) {
        if (!pingOk) return State.NOT_RUNNING;
        return version >= HOLD_WATCH_VERSION ? State.CURRENT : State.STALE;
    }

    /**
     * Whether the service may run: the switch is on, the overlay
     * permission is granted, and the bridge is CURRENT. A stale
     * bridge must never start the service — the watch would be
     * refused and die again on every reconcile.
     */
    public static boolean wantService(boolean switchOn, boolean overlayGranted,
                                      State bridge) {
        return switchOn && overlayGranted && bridge == State.CURRENT;
    }

    /**
     * Whether a refused HOLD_WATCH ack means "stale bridge"
     * (defense in depth, for the case where the version gate is
     * somehow bypassed): the old bridge has no HOLD_WATCH op,
     * so its switch hits default and answers ok:false with the
     * allowlist error — and its version, when it reports one.
     * Any other refusal (no touchscreen, token, transport) is
     * not a stale bridge.
     */
    public static boolean isStaleRefusal(JSONObject ack) {
        if (ack == null) return false;
        if (ack.optBoolean("ok", false)) return false;
        int version = parseVersion(ack);
        if (version > 0 && version < HOLD_WATCH_VERSION) return true;
        String error = ack.optString("error", "");
        return error.contains("not on the bridge allowlist");
    }

    /**
     * The Settings summary line for the hold-anywhere row. It
     * names the ACTUAL failing gate — never "Deep view is not
     * running" for a bridge that is running. The bridge state
     * comes from a fresh ping (see OverlayRingService.reconcile),
     * not from the service's liveness alone: the service being
     * down is a symptom, and the stale bridge is the cause.
     */
    public static String stateLine(boolean switchOn, boolean overlayGranted,
                                   State bridge, boolean serviceAlive) {
        if (!switchOn) return "Off — the switch is off";
        if (!overlayGranted) {
            return "Off — overlay permission not granted (tap below to allow it)";
        }
        if (bridge == State.STALE) {
            return "Off — the bridge is running an old version — restart it for hold-anywhere";
        }
        if (bridge == State.NOT_RUNNING) return "Off — Deep view is not running";
        if (serviceAlive) return "On — hold anywhere for a second to open the ring";
        if (bridge == State.UNKNOWN) return "Checking the bridge…";
        return "Starting — the hold watch is starting…";
    }

    /**
     * The version fragment for status lines, e.g. "v4". A bridge
     * that did not report a version gets no fragment — the line
     * reads "bridge running", never "bridge v0 running".
     */
    public static String versionFragment(int version) {
        return version > 0 ? "v" + version + " " : "";
    }

    /** "v4" for a reported version, "unversioned" when the
     *  bridge did not report one. */
    public static String versionName(int version) {
        return version > 0 ? "v" + version : "unversioned";
    }
}
