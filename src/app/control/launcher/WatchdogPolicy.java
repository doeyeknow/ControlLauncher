package app.control.launcher;

/**
 * Bridge auto-restart watchdog policy (v1.16). Pure Java — no
 * Android classes — so the harness exercises the exact shipping
 * code.
 *
 * Why this exists: the bridge is a separate app_process started
 * through wireless debugging, and it never survives a reboot.
 * Adam asked for it to come back by itself. A plain background
 * process can never hold shell powers (it runs as the app UID),
 * so the watchdog cannot BE the bridge — it watches the bridge
 * and re-runs the existing in-app ADB restart whenever the
 * bridge dies or goes stale. Pair once, and the app keeps the
 * bridge up by itself while wireless debugging stays on.
 *
 * The policy is deliberately boring:
 *   - The watchdog only acts while Deep view is enabled (the
 *     KEY_DEEP_VIEW_ON flag: set when a bridge start succeeds
 *     or Deep view finds the bridge up, cleared by "Turn off
 *     deep control"). It never starts the bridge for someone
 *     who never enabled Deep view.
 *   - A CURRENT bridge earns nothing but the next ping, five
 *     minutes later. A dead bridge earns the normal starter.
 *     A stale bridge earns the stop-then-start path. A
 *     restart already in flight earns nothing — never two
 *     restarts at once.
 *   - Failed restarts back off: 30s, 1m, 2m, 5m, 10m, and
 *     then NO scheduled retry — only an unlock or a Deep
 *     view / Settings visit tries again. No prompt spam, no
 *     repeated failure toasts, no tight loop.
 *   - The one blocker worth naming is wireless debugging
 *     being off: the restart's discovery stage says so
 *     verbatim ("No wireless-debugging service found"), and
 *     the Settings row repeats it plainly. Every other
 *     failure keeps its real reason as the blocker text.
 */
public final class WatchdogPolicy {

    /** How often a healthy bridge is re-pinged: five minutes.
     *  Infrequent on purpose — the watch costs a foreground
     *  service and a socket ping, and nothing more. */
    public static final long PING_INTERVAL_MS = 300_000L;

    /** Returned by {@link #backoffDelayMs} when the schedule is
     *  exhausted: no more timed retries, triggers only. */
    public static final long NO_SCHEDULED_RETRY = -1L;

    /** Backoff after consecutive failed restarts, in order.
     *  The first failure waits 30s, the fifth waits 10 minutes;
     *  a sixth consecutive failure exhausts the schedule. */
    private static final long[] BACKOFF_MS = {
            30_000L, 60_000L, 120_000L, 300_000L, 600_000L,
    };

    /** What the watchdog should do after one ping. */
    public enum Action {
        /** Nothing: Deep view off, bridge current, no ping
         *  yet, or a restart already running. */
        NONE,
        /** The bridge is down: run the normal starter. */
        RESTART_DEAD,
        /** The bridge answered but is too old: run the
         *  stop-then-start path. */
        RESTART_STALE
    }

    /** Why the last restart failed, as the Settings row names
     *  it. Only wireless-debugging-off gets its own wording —
     *  it is the one blocker the user fixes with a switch. */
    public enum Blocked {
        NONE,
        WIRELESS_OFF,
        OTHER
    }

    private WatchdogPolicy() {}

    /**
     * The restart action for one ping result. The ping itself
     * always happens (the worker pings on every wake); this
     * decides only whether a restart follows it.
     */
    public static Action decide(boolean deepViewOn, BridgeGate.State bridge,
                                boolean restartInFlight) {
        if (!deepViewOn) return Action.NONE;
        if (restartInFlight) return Action.NONE;
        if (bridge == BridgeGate.State.NOT_RUNNING) return Action.RESTART_DEAD;
        if (bridge == BridgeGate.State.STALE) return Action.RESTART_STALE;
        return Action.NONE;
    }

    /**
     * How long to wait before the next scheduled retry after
     * {@code consecutiveFailures} failed restarts in a row.
     * Zero or fewer failures means "now" (0). Past the end of
     * the schedule, NO_SCHEDULED_RETRY: only an unlock or a
     * foreground visit tries again.
     */
    public static long backoffDelayMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0L;
        if (consecutiveFailures > BACKOFF_MS.length) return NO_SCHEDULED_RETRY;
        return BACKOFF_MS[consecutiveFailures - 1];
    }

    /** Whether the failure count has exhausted the schedule. */
    public static boolean isBackoffExhausted(int consecutiveFailures) {
        return consecutiveFailures > BACKOFF_MS.length;
    }

    /** Number of scheduled retries before triggers-only. */
    public static int scheduledRetries() {
        return BACKOFF_MS.length;
    }

    /**
     * Classify a restart failure from the summary AdbStarter
     * reported. The discovery stage's verbatim "No
     * wireless-debugging service found" is the wireless-off
     * marker; anything else — connect failures, pairing,
     * a bridge that would not stop — keeps its own reason
     * and classifies as OTHER. Null or empty is OTHER: an
     * unknown failure is still a failure.
     */
    public static Blocked classifyFailure(String summary) {
        if (summary != null
                && summary.contains("No wireless-debugging service found")) {
            return Blocked.WIRELESS_OFF;
        }
        return Blocked.OTHER;
    }

    /** First line of a failure summary, for the Settings row.
     *  Empty when there is nothing to show. */
    public static String firstLine(String summary) {
        if (summary == null) return "";
        String line = summary.trim();
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl).trim();
        return line;
    }

    /**
     * The Settings / Deep view summary line for the watchdog.
     * It names the actual state — never "watching" when Deep
     * view is off, and never a generic failure when the
     * blocker is the wireless-debugging switch. The bridge
     * state and blocker come from the last fresh ping and the
     * last restart outcome, never from the service's liveness
     * alone.
     */
    public static String stateLine(boolean deepViewOn, boolean serviceAlive,
                                   BridgeGate.State bridge, Blocked blocked,
                                   int consecutiveFailures, String blockerText) {
        if (!deepViewOn) return "Off — Deep view is off";
        if (blocked == Blocked.WIRELESS_OFF) {
            return "Waiting — wireless debugging is off. Turn it on and the bridge restarts itself.";
        }
        if (bridge == BridgeGate.State.CURRENT) {
            if (serviceAlive) {
                return "Watching — the bridge is up, and restarts itself if it stops";
            }
            return "Starting the watch…";
        }
        if (bridge == BridgeGate.State.STALE) {
            return "Bridge is running old code — restarting it for the current version…";
        }
        if (bridge == BridgeGate.State.NOT_RUNNING) {
            if (isBackoffExhausted(consecutiveFailures)) {
                return "Bridge down — will retry on the next unlock or when Deep view opens.";
            }
            if (consecutiveFailures > 0) {
                String line = "Bridge down — retrying…";
                if (blocked == Blocked.OTHER && blockerText != null
                        && !blockerText.isEmpty()) {
                    line += " " + blockerText;
                }
                return line;
            }
            return "Bridge down — restarting it…";
        }
        return "Checking the bridge…";
    }
}
