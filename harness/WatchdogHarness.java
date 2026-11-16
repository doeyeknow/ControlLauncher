package app.control.launcher;

/**
 * Off-device verification for the v1.16 bridge auto-restart
 * watchdog, exercising the exact shipping pure-Java code
 * (WatchdogPolicy):
 *
 * Legs:
 *   1. DECIDE: Deep view off means no action for every bridge
 *      state (the watchdog never starts the bridge for
 *      someone who never enabled Deep view); a restart in
 *      flight means no second restart; CURRENT and UNKNOWN
 *      mean no restart; NOT_RUNNING means the normal starter;
 *      STALE means the stop-then-start path.
 *   2. BACKOFF: the schedule is 30s, 1m, 2m, 5m, 10m, then
 *      NO_SCHEDULED_RETRY (triggers only); it is strictly
 *      increasing, capped, and zero/negative failure counts
 *      mean "now".
 *   3. FAILURE CLASSIFICATION: the discovery stage's verbatim
 *      "No wireless-debugging service found" classifies as
 *      WIRELESS_OFF; connect failures, pairing failures, a
 *      bridge that would not stop, null, and empty classify
 *      as OTHER.
 *   4. STATE LINE: Deep view off says so; the wireless-off
 *      blocker names the switch; CURRENT + alive says
 *      watching; CURRENT + dead service says starting;
 *      STALE names the old code; NOT_RUNNING names the
 *      restart, the retry with its blocker, and the
 *      triggers-only wait once the schedule is exhausted;
 *      UNKNOWN says checking. No line claims the watch is
 *      running when Deep view is off.
 *   5. CONSTANTS: the ping interval is five minutes, and the
 *      schedule holds five retries before triggers-only.
 *
 * Run (from ~/workspace/control-launcher), with the real
 * org.json jar ONLY on the classpath — android.jar must not
 * come first, or its stub org.json shadows the real one:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar -d harness/classes \
 *     harness/WatchdogHarness.java
 *   java -cp harness/classes:harness/org-json.jar \
 *     app.control.launcher.WatchdogHarness
 *
 * What this harness cannot prove: the service lifecycle, the
 * boot receiver, the unlock trigger, or a real restart
 * against a real bridge on a phone — all of that is Adam's
 * on-device test.
 */
public class WatchdogHarness {

    private static int checks;
    private static int failures;
    private static final java.util.List<String> failed = new java.util.ArrayList<>();

    public static void main(String[] args) {
        decide();
        backoff();
        classifyFailure();
        stateLine();
        constants();
        System.out.println("WatchdogHarness: " + checks + " checks, "
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

    // ---- legs ----

    private static void decide() {
        for (BridgeGate.State s : BridgeGate.State.values()) {
            eq(WatchdogPolicy.Action.NONE,
                    WatchdogPolicy.decide(false, s, false),
                    "deep view off + " + s + " -> NONE");
            eq(WatchdogPolicy.Action.NONE,
                    WatchdogPolicy.decide(true, s, true),
                    "restart in flight + " + s + " -> NONE");
        }
        eq(WatchdogPolicy.Action.NONE,
                WatchdogPolicy.decide(true, BridgeGate.State.CURRENT, false),
                "current -> NONE");
        eq(WatchdogPolicy.Action.NONE,
                WatchdogPolicy.decide(true, BridgeGate.State.UNKNOWN, false),
                "unknown -> NONE");
        eq(WatchdogPolicy.Action.RESTART_DEAD,
                WatchdogPolicy.decide(true, BridgeGate.State.NOT_RUNNING, false),
                "not running -> RESTART_DEAD");
        eq(WatchdogPolicy.Action.RESTART_STALE,
                WatchdogPolicy.decide(true, BridgeGate.State.STALE, false),
                "stale -> RESTART_STALE");
    }

    private static void backoff() {
        eq(0L, WatchdogPolicy.backoffDelayMs(0), "0 failures -> now");
        eq(0L, WatchdogPolicy.backoffDelayMs(-3), "negative failures -> now");
        eq(30_000L, WatchdogPolicy.backoffDelayMs(1), "1st failure -> 30s");
        eq(60_000L, WatchdogPolicy.backoffDelayMs(2), "2nd failure -> 1m");
        eq(120_000L, WatchdogPolicy.backoffDelayMs(3), "3rd failure -> 2m");
        eq(300_000L, WatchdogPolicy.backoffDelayMs(4), "4th failure -> 5m");
        eq(600_000L, WatchdogPolicy.backoffDelayMs(5), "5th failure -> 10m");
        eq(WatchdogPolicy.NO_SCHEDULED_RETRY,
                WatchdogPolicy.backoffDelayMs(6), "6th failure -> triggers only");
        eq(WatchdogPolicy.NO_SCHEDULED_RETRY,
                WatchdogPolicy.backoffDelayMs(100), "100th failure -> triggers only");
        long prev = 0;
        for (int i = 1; i <= WatchdogPolicy.scheduledRetries(); i++) {
            long d = WatchdogPolicy.backoffDelayMs(i);
            check(d > prev, "backoff strictly increases at " + i);
            prev = d;
        }
        check(!WatchdogPolicy.isBackoffExhausted(5),
                "5 failures does not exhaust the schedule");
        check(WatchdogPolicy.isBackoffExhausted(6),
                "6 failures exhausts the schedule");
        eq(5, WatchdogPolicy.scheduledRetries(), "five scheduled retries");
    }

    private static void classifyFailure() {
        eq(WatchdogPolicy.Blocked.WIRELESS_OFF,
                WatchdogPolicy.classifyFailure(
                        "No wireless-debugging service found. Android only "
                        + "offers it while Wireless debugging is ON."),
                "verbatim discovery message -> WIRELESS_OFF");
        eq(WatchdogPolicy.Blocked.OTHER,
                WatchdogPolicy.classifyFailure(
                        "Stage 2 (connect) failed: not paired"),
                "connect failure -> OTHER");
        eq(WatchdogPolicy.Blocked.OTHER,
                WatchdogPolicy.classifyFailure(
                        "The old bridge did not stop."),
                "unstoppable bridge -> OTHER");
        eq(WatchdogPolicy.Blocked.OTHER,
                WatchdogPolicy.classifyFailure(
                        "Stage 1 (discovery) failed: mDNS error"),
                "discovery error -> OTHER");
        eq(WatchdogPolicy.Blocked.OTHER,
                WatchdogPolicy.classifyFailure(null), "null -> OTHER");
        eq(WatchdogPolicy.Blocked.OTHER,
                WatchdogPolicy.classifyFailure(""), "empty -> OTHER");
        eq("first line",
                WatchdogPolicy.firstLine("first line\nsecond line"),
                "firstLine takes the first line");
        eq("", WatchdogPolicy.firstLine(null), "firstLine of null is empty");
        eq("", WatchdogPolicy.firstLine("  \n  "), "firstLine of blank is empty");
    }

    private static void stateLine() {
        for (BridgeGate.State s : BridgeGate.State.values()) {
            String line = WatchdogPolicy.stateLine(false, true, s,
                    WatchdogPolicy.Blocked.NONE, 0, "");
            check(line.startsWith("Off"),
                    "deep view off + " + s + " starts with Off: " + line);
            check(!line.contains("Watching"),
                    "deep view off + " + s + " never claims watching");
        }
        eq("Waiting — wireless debugging is off. Turn it on and the bridge restarts itself.",
                WatchdogPolicy.stateLine(true, true,
                        BridgeGate.State.NOT_RUNNING,
                        WatchdogPolicy.Blocked.WIRELESS_OFF, 3, ""),
                "wireless-off blocker line is exact");
        eq("Watching — the bridge is up, and restarts itself if it stops",
                WatchdogPolicy.stateLine(true, true,
                        BridgeGate.State.CURRENT,
                        WatchdogPolicy.Blocked.NONE, 0, ""),
                "current + alive says watching");
        eq("Starting the watch…",
                WatchdogPolicy.stateLine(true, false,
                        BridgeGate.State.CURRENT,
                        WatchdogPolicy.Blocked.NONE, 0, ""),
                "current + dead service says starting");
        check(WatchdogPolicy.stateLine(true, true, BridgeGate.State.STALE,
                WatchdogPolicy.Blocked.NONE, 0, "").contains("old code"),
                "stale line names the old code");
        eq("Bridge down — restarting it…",
                WatchdogPolicy.stateLine(true, true,
                        BridgeGate.State.NOT_RUNNING,
                        WatchdogPolicy.Blocked.NONE, 0, ""),
                "down with no failures says restarting");
        String retry = WatchdogPolicy.stateLine(true, true,
                BridgeGate.State.NOT_RUNNING,
                WatchdogPolicy.Blocked.OTHER, 2, "Stage 2 (connect) failed");
        check(retry.contains("retrying") && retry.contains("Stage 2"),
                "retry line carries the blocker: " + retry);
        eq("Bridge down — will retry on the next unlock or when Deep view opens.",
                WatchdogPolicy.stateLine(true, true,
                        BridgeGate.State.NOT_RUNNING,
                        WatchdogPolicy.Blocked.OTHER, 6, "x"),
                "exhausted schedule says triggers-only");
        eq("Checking the bridge…",
                WatchdogPolicy.stateLine(true, true,
                        BridgeGate.State.UNKNOWN,
                        WatchdogPolicy.Blocked.NONE, 0, ""),
                "unknown says checking");
        // The wireless-off blocker wins over the stale line:
        // the switch is what the user must fix either way.
        check(WatchdogPolicy.stateLine(true, true, BridgeGate.State.STALE,
                WatchdogPolicy.Blocked.WIRELESS_OFF, 1, "")
                .contains("wireless debugging is off"),
                "wireless-off wins over the stale line");
    }

    private static void constants() {
        eq(300_000L, WatchdogPolicy.PING_INTERVAL_MS,
                "ping interval is five minutes");
        check(WatchdogPolicy.PING_INTERVAL_MS > 0, "ping interval is positive");
        check(WatchdogPolicy.NO_SCHEDULED_RETRY < 0,
                "no-scheduled-retry is negative (a sentinel, never a delay)");
    }
}
