package app.control.launcher;

/**
 * Off-device verification for the v1.23 automatic walkie mode,
 * exercising the exact shipping decision (WalkieMode):
 *
 * Legs:
 *   1. CHOICE: stored-form parsing (known values, null/empty/
 *      unknown fall back to AUTO, the v1.24 default) and
 *      round trips. The v1.23 one-time migration from the
 *      old low-data switch is GONE — its absence is proven
 *      by the compile (no migrateChoice) plus the defaults
 *      leg below.
 *   1b. DEFAULTS (v1.24): a fresh install (no stored value)
 *      is AUTO; a stored VOICE/TEXT is respected as the
 *      user's explicit choice; an explicit switch sticks.
 *   2. DECIDE: manual override precedence (manual beats a live
 *      local session and beats any probe), the local-session
 *      shortcut, the probe thresholds with hysteresis (edges
 *      at 400/1200ms, the band keeping the previous decision
 *      in both directions), and no-probe/failed-probe keeping
 *      the last decision (null previous reads as VOICE).
 *   3. SIGNAL: the bars mapping (local link full, fast/slow/
 *      band, unreachable, unmeasured) and its text forms.
 *   4. DESCRIBE: every face line — manual modes, Auto with a
 *      probe, with no probe, unreachable, on a nearby link,
 *      and the missing-model suffix on TEXT only.
 *   5. CONSTANTS: the thresholds order sanely and the idle
 *      re-test interval is bounded (30s..5min).
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/WalkieModeHarness.java
 *   java -cp harness/classes app.control.launcher.WalkieModeHarness
 * No Android classes, no network.
 */
public final class WalkieModeHarness {

    private static int checks;
    private static int passed;

    public static void main(String[] args) {
        choice();
        defaults();
        decide();
        signal();
        describe();
        constants();
        System.out.println("WalkieModeHarness: " + passed + "/" + checks
                + " checks passed");
        if (passed != checks) System.exit(1);
    }

    private static void choice() {
        check("parse auto",
                WalkieMode.parseChoice("auto") == WalkieMode.Choice.AUTO);
        check("parse voice",
                WalkieMode.parseChoice("voice") == WalkieMode.Choice.VOICE);
        check("parse text",
                WalkieMode.parseChoice("text") == WalkieMode.Choice.TEXT);
        check("parse null falls back to auto (v1.24 default)",
                WalkieMode.parseChoice(null) == WalkieMode.Choice.AUTO);
        check("parse empty falls back to auto",
                WalkieMode.parseChoice("") == WalkieMode.Choice.AUTO);
        check("parse unknown falls back to auto",
                WalkieMode.parseChoice("loud") == WalkieMode.Choice.AUTO);
        check("parse is case-sensitive (AUTO is unknown, hence auto)",
                WalkieMode.parseChoice("AUTO") == WalkieMode.Choice.AUTO);
        check("store auto round-trips",
                WalkieMode.parseChoice(WalkieMode.storeChoice(
                        WalkieMode.Choice.AUTO)) == WalkieMode.Choice.AUTO);
        check("store voice round-trips",
                WalkieMode.parseChoice(WalkieMode.storeChoice(
                        WalkieMode.Choice.VOICE)) == WalkieMode.Choice.VOICE);
        check("store text round-trips",
                WalkieMode.parseChoice(WalkieMode.storeChoice(
                        WalkieMode.Choice.TEXT)) == WalkieMode.Choice.TEXT);
    }

    /** v1.24: Auto is the default. A stored value is the
     *  user's explicit choice and is respected; absence of a
     *  stored value means Auto. No migration writes an
     *  implicit choice — there is no migrateChoice to call,
     *  and parseChoice never invents VOICE/TEXT from
     *  nothing. */
    private static void defaults() {
        check("fresh install (no stored value) is AUTO",
                WalkieMode.parseChoice(null) == WalkieMode.Choice.AUTO);
        check("stored voice is respected as an explicit choice",
                WalkieMode.parseChoice("voice") == WalkieMode.Choice.VOICE);
        check("stored text is respected as an explicit choice",
                WalkieMode.parseChoice("text") == WalkieMode.Choice.TEXT);
        check("explicit switch to voice sticks",
                WalkieMode.parseChoice(WalkieMode.storeChoice(
                        WalkieMode.Choice.VOICE)) == WalkieMode.Choice.VOICE);
        check("explicit switch to text sticks",
                WalkieMode.parseChoice(WalkieMode.storeChoice(
                        WalkieMode.Choice.TEXT)) == WalkieMode.Choice.TEXT);
        check("explicit switch back to auto sticks",
                WalkieMode.parseChoice(WalkieMode.storeChoice(
                        WalkieMode.Choice.AUTO)) == WalkieMode.Choice.AUTO);
        check("auto with no probe yet starts at VOICE (measured next)",
                WalkieMode.decide(WalkieMode.Choice.AUTO, null,
                        false, WalkieMode.Probe.NONE, 0)
                        == WalkieMode.Mode.VOICE);
        check("auto default does not change manual precedence",
                WalkieMode.decide(WalkieMode.Choice.VOICE, null,
                        false, WalkieMode.Probe.OK, 5000)
                        == WalkieMode.Mode.VOICE);
    }

    private static void decide() {
        // Manual override beats everything.
        check("manual VOICE beats a slow probe",
                WalkieMode.decide(WalkieMode.Choice.VOICE, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.OK, 5000) == WalkieMode.Mode.VOICE);
        check("manual VOICE beats a live local session + slow probe",
                WalkieMode.decide(WalkieMode.Choice.VOICE, WalkieMode.Mode.TEXT,
                        true, WalkieMode.Probe.OK, 5000) == WalkieMode.Mode.VOICE);
        check("manual TEXT beats a fast probe",
                WalkieMode.decide(WalkieMode.Choice.TEXT, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.OK, 50) == WalkieMode.Mode.TEXT);
        check("manual TEXT beats a live local session",
                WalkieMode.decide(WalkieMode.Choice.TEXT, WalkieMode.Mode.VOICE,
                        true, WalkieMode.Probe.OK, 50) == WalkieMode.Mode.TEXT);
        // Local session shortcut (AUTO).
        check("auto + local session is VOICE even with a slow probe",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        true, WalkieMode.Probe.OK, 5000) == WalkieMode.Mode.VOICE);
        check("auto + local session is VOICE with no probe",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        true, WalkieMode.Probe.NONE, 0) == WalkieMode.Mode.VOICE);
        // Thresholds (AUTO, no local session).
        check("auto + 400ms is VOICE (edge inclusive)",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.OK, 400) == WalkieMode.Mode.VOICE);
        check("auto + 50ms is VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.OK, 50) == WalkieMode.Mode.VOICE);
        check("auto + 1200ms is TEXT (edge inclusive)",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.OK, 1200) == WalkieMode.Mode.TEXT);
        check("auto + 5000ms is TEXT",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.OK, 5000) == WalkieMode.Mode.TEXT);
        // Hysteresis band: the previous decision stands.
        check("auto + 401ms keeps VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.OK, 401) == WalkieMode.Mode.VOICE);
        check("auto + 401ms keeps TEXT",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.OK, 401) == WalkieMode.Mode.TEXT);
        check("auto + 1199ms keeps VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.OK, 1199) == WalkieMode.Mode.VOICE);
        check("auto + 1199ms keeps TEXT",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.OK, 1199) == WalkieMode.Mode.TEXT);
        check("auto + 800ms keeps VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.OK, 800) == WalkieMode.Mode.VOICE);
        check("auto + 800ms keeps TEXT",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.OK, 800) == WalkieMode.Mode.TEXT);
        // No probe / failed probe: the last decision stands.
        check("auto + no probe keeps VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.NONE, 0) == WalkieMode.Mode.VOICE);
        check("auto + no probe keeps TEXT",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.NONE, 0) == WalkieMode.Mode.TEXT);
        check("auto + failed probe keeps VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        false, WalkieMode.Probe.FAILED, 0) == WalkieMode.Mode.VOICE);
        check("auto + failed probe keeps TEXT",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.FAILED, 0) == WalkieMode.Mode.TEXT);
        check("auto + failed probe ignores a stale rtt",
                WalkieMode.decide(WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        false, WalkieMode.Probe.FAILED, 50) == WalkieMode.Mode.TEXT);
        // Null previous reads as VOICE.
        check("null previous + fast probe is VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, null,
                        false, WalkieMode.Probe.OK, 50) == WalkieMode.Mode.VOICE);
        check("null previous + band probe is VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, null,
                        false, WalkieMode.Probe.OK, 800) == WalkieMode.Mode.VOICE);
        check("null previous + no probe is VOICE",
                WalkieMode.decide(WalkieMode.Choice.AUTO, null,
                        false, WalkieMode.Probe.NONE, 0) == WalkieMode.Mode.VOICE);
    }

    private static void signal() {
        check("local session is 3 bars",
                WalkieMode.signalBars(WalkieMode.Probe.FAILED, 0, true) == 3);
        check("fast probe is 3 bars",
                WalkieMode.signalBars(WalkieMode.Probe.OK, 100, false) == 3);
        check("400ms is 3 bars",
                WalkieMode.signalBars(WalkieMode.Probe.OK, 400, false) == 3);
        check("band probe is 2 bars",
                WalkieMode.signalBars(WalkieMode.Probe.OK, 800, false) == 2);
        check("slow probe is 1 bar",
                WalkieMode.signalBars(WalkieMode.Probe.OK, 2000, false) == 1);
        check("1200ms is 1 bar",
                WalkieMode.signalBars(WalkieMode.Probe.OK, 1200, false) == 1);
        check("failed probe is 0 bars",
                WalkieMode.signalBars(WalkieMode.Probe.FAILED, 0, false) == 0);
        check("no probe is -1 bars",
                WalkieMode.signalBars(WalkieMode.Probe.NONE, 0, false) == -1);
        check("3 bars text", "▂▄▆".equals(
                WalkieMode.signalText(WalkieMode.Probe.OK, 100, false)));
        check("2 bars text", "▂▄".equals(
                WalkieMode.signalText(WalkieMode.Probe.OK, 800, false)));
        check("1 bar text", "▂".equals(
                WalkieMode.signalText(WalkieMode.Probe.OK, 2000, false)));
        check("0 bars text", "✕".equals(
                WalkieMode.signalText(WalkieMode.Probe.FAILED, 0, false)));
        check("unmeasured text", "···".equals(
                WalkieMode.signalText(WalkieMode.Probe.NONE, 0, false)));
    }

    private static void describe() {
        check("manual voice line",
                "Voice".equals(WalkieMode.describe(WalkieMode.Choice.VOICE,
                        WalkieMode.Mode.VOICE, WalkieMode.Probe.NONE, 0,
                        false, false)));
        check("manual text line",
                "Text".equals(WalkieMode.describe(WalkieMode.Choice.TEXT,
                        WalkieMode.Mode.TEXT, WalkieMode.Probe.NONE, 0,
                        false, false)));
        check("manual text + missing model names it",
                "Text · model not downloaded".equals(WalkieMode.describe(
                        WalkieMode.Choice.TEXT, WalkieMode.Mode.TEXT,
                        WalkieMode.Probe.NONE, 0, false, true)));
        check("manual voice never carries the model suffix",
                "Voice".equals(WalkieMode.describe(WalkieMode.Choice.VOICE,
                        WalkieMode.Mode.VOICE, WalkieMode.Probe.NONE, 0,
                        false, true)));
        check("auto + fast probe line",
                "Auto · 210 ms · Voice".equals(WalkieMode.describe(
                        WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        WalkieMode.Probe.OK, 210, false, false)));
        check("auto + slow probe line",
                "Auto · 2400 ms · Text".equals(WalkieMode.describe(
                        WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        WalkieMode.Probe.OK, 2400, false, false)));
        check("auto + no probe line",
                "Auto · no probe yet · Voice".equals(WalkieMode.describe(
                        WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        WalkieMode.Probe.NONE, 0, false, false)));
        check("auto + unreachable line",
                "Auto · unreachable · Text".equals(WalkieMode.describe(
                        WalkieMode.Choice.AUTO, WalkieMode.Mode.TEXT,
                        WalkieMode.Probe.FAILED, 0, false, false)));
        check("auto + nearby link line",
                "Auto · nearby link · Voice".equals(WalkieMode.describe(
                        WalkieMode.Choice.AUTO, WalkieMode.Mode.VOICE,
                        WalkieMode.Probe.OK, 2400, true, false)));
        check("auto + text + missing model names it",
                "Auto · 2400 ms · Text · model not downloaded".equals(
                        WalkieMode.describe(WalkieMode.Choice.AUTO,
                                WalkieMode.Mode.TEXT, WalkieMode.Probe.OK,
                                2400, false, true)));
    }

    private static void constants() {
        check("voice threshold below text threshold",
                WalkieMode.VOICE_MAX_RTT_MS < WalkieMode.TEXT_MIN_RTT_MS);
        check("voice threshold positive",
                WalkieMode.VOICE_MAX_RTT_MS > 0);
        check("hysteresis band is real (>= 100ms)",
                WalkieMode.TEXT_MIN_RTT_MS - WalkieMode.VOICE_MAX_RTT_MS >= 100);
        check("probe interval bounded below (30s)",
                WalkieMode.PROBE_INTERVAL_MS >= 30_000);
        check("probe interval bounded above (5min)",
                WalkieMode.PROBE_INTERVAL_MS <= 300_000);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            passed++;
        } else {
            System.out.println("FAIL: " + name);
        }
    }
}
