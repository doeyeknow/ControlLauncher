package app.control.launcher;

import org.json.JSONObject;

/**
 * The bridge's hold-watch diagnostics (v1.44). Pure Java — no
 * Android imports — so the JVM harness exercises the exact
 * shipping code.
 *
 * Why this exists: on 1.43 the ring still opened only inside
 * the launcher while every gate passed (switch on, permission
 * granted, bridge v9 CURRENT, service alive) — and the v1.43
 * diagnostic line was itself undisplayable (see BUILD_NOTES).
 * The failure is in one of four stages the app could not see:
 * the probe picked the wrong input device, no touch frames
 * arrive, the hold never fires, or the overlay window is
 * rejected. The bridge's HOLD_WATCH_STATUS op (protocol v10)
 * reports the picked device plus frame/hold counts, and this
 * class parses that payload and formats the Settings second
 * line from it. Counts and names only — never coordinates,
 * never touch trails.
 *
 * The counts discriminate the stages: frames 0 means the
 * stream is dead or the device is wrong; frames > 0 with
 * holds 0 means the parser never fires; holds > 0 with no
 * ring means the hold reached the bridge but the client
 * dropped it or the window failed (the window part names
 * that). The last watch's snapshot survives its end, so the
 * row still says what the bridge saw after the watch died.
 */
public final class HoldWatchStatus {

    /** A watch is streaming right now (bridge-side). */
    public final boolean active;
    /** The picked device, as "path (name)", or "" when no
     *  device was ever picked. */
    public final String device;
    /** SYN_REPORT frames seen by the current/last watch, or
     *  -1 when unknown. */
    public final long frames;
    /** Holds fired by the current/last watch, or -1 when
     *  unknown. */
    public final long holds;
    /** Why the probe failed, when it did ("" otherwise). A
     *  watch that ran and ended normally leaves this empty —
     *  the app-side end reason names that case. */
    public final String endReason;

    public HoldWatchStatus(boolean active, String device, long frames,
                           long holds, String endReason) {
        this.active = active;
        this.device = device == null ? "" : device;
        this.frames = frames;
        this.holds = holds;
        this.endReason = endReason == null ? "" : endReason;
    }

    /**
     * Parse the HOLD_WATCH_STATUS text payload. Null when there
     * is nothing to parse (null, empty, or malformed JSON) —
     * the caller falls back to the v1.43 line. A JSON object
     * with missing fields parses with honest defaults (unknown
     * counts, no device), never an exception.
     */
    public static HoldWatchStatus parse(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        JSONObject json;
        try {
            json = new JSONObject(t);
        } catch (Exception e) {
            return null;
        }
        return new HoldWatchStatus(
                json.optBoolean("active", false),
                json.optString("device", ""),
                json.optLong("frames", -1L),
                json.optLong("holds", -1L),
                json.optString("endReason", ""));
    }

    /**
     * The short device display for the Settings line: the
     * device name when the "path (name)" form carries one,
     * else the last path segment, else "". A name is what
     * tells a touchscreen from a wrong pick at a glance.
     */
    public static String deviceShort(String device) {
        if (device == null) return "";
        String d = device.trim();
        if (d.isEmpty()) return "";
        int open = d.lastIndexOf('(');
        if (open >= 0) {
            int close = d.lastIndexOf(')');
            String name = close > open + 1
                    ? d.substring(open + 1, close).trim() : "";
            if (!name.isEmpty()) return name;
            d = d.substring(0, open).trim();
            if (d.isEmpty()) return "";
        }
        int slash = d.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < d.length()) {
            return d.substring(slash + 1).trim();
        }
        return d;
    }

    /**
     * The Settings hold-anywhere second line. Empty when the
     * switch is off (the stages are stale noise then). With a
     * bridge status, the line is one compact display line:
     * watch state, device, hold count, frame count, and the
     * window failure when one is recorded — in that order, so
     * the stage-identifying head survives even if the row
     * truncates the tail. Without a status (an old bridge, or
     * a failed fetch), the v1.43 line is kept verbatim.
     *
     * @param watchEndReason/watchEndAtMs the app-side last end
     *        reason/time (elapsedRealtime domain), ""/0 when
     *        the watch never ended.
     * @param lastHoldAtMs when a hold last reached the app
     *        (elapsedRealtime domain), 0 when none ever did.
     * @param showError the last overlay-window failure, "" when
     *        none (or cleared by a good add).
     * @param nowMs the current elapsedRealtime, for "ago".
     */
    public static String formatLine(boolean switchOn, boolean serviceAlive,
                                    String watchEndReason, long watchEndAtMs,
                                    long lastHoldAtMs, String showError,
                                    HoldWatchStatus status, long nowMs) {
        if (!switchOn) return "";
        String watch;
        String reason = watchEndReason == null ? "" : watchEndReason;
        if (serviceAlive) {
            watch = "watch running";
        } else if (reason.isEmpty()) {
            watch = "watch not started";
        } else if (watchEndAtMs == 0) {
            watch = "watch ended: " + reason;
        } else {
            watch = "watch ended " + ago(watchEndAtMs, nowMs) + ": " + reason;
        }
        String error = showError == null ? "" : showError;
        if (status == null) {
            String hold = lastHoldAtMs == 0
                    ? "no hold seen yet"
                    : "last hold " + ago(lastHoldAtMs, nowMs);
            String window = error.isEmpty()
                    ? "window ok" : "window failed: " + error;
            return watch + " · " + hold + " · " + window;
        }
        String device = deviceShort(status.device);
        StringBuilder line = new StringBuilder(watch);
        line.append(" · ").append(device.isEmpty() ? "no device" : device);
        line.append(" · ").append(count(status.holds, "hold"));
        line.append(" · ").append(count(status.frames, "frame"));
        if (!error.isEmpty()) {
            line.append(" · window failed: ").append(error);
        }
        return line.toString();
    }

    private static String count(long value, String singular) {
        if (value < 0) return singular + "s ?";
        if (value == 1) return "1 " + singular;
        return value + " " + singular + "s";
    }

    private static String ago(long atMs, long nowMs) {
        long diff = nowMs - atMs;
        if (diff < 60_000L) return "just now";
        if (diff < 3_600_000L) return (diff / 60_000L) + "m ago";
        return (diff / 3_600_000L) + "h ago";
    }
}
