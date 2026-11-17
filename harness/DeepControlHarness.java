package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Off-device verification for v1.33: the Deep controls
 * running-processes shaping (ProcessRules) and the
 * package-disable fix (PmOutcome), exercising the exact shipping
 * pure-Java code.
 *
 * Legs:
 *   1. PS PARSING: the header, blank lines, and malformed lines
 *      are skipped; app, system, and kernel-thread rows parse
 *      with every field; ps order is preserved.
 *   2. PACKAGE CANDIDATE: a bare package name maps to itself,
 *      a ":suffix" process maps to its base package, and
 *      system/daemon/kernel names map to nothing.
 *   3. MAPPING: a candidate counts as the row's package only
 *      when it is installed — an uninstalled lookalike stays
 *      unmapped, never guessed.
 *   4. REFUSE MATCHING: REFUSE_ALL and REFUSE_DESTRUCTIVE
 *      members get plain-words reasons; ordinary packages get
 *      none; the lists hold the documented members.
 *   5. WARNING FLAGS: system users warn, app users with an
 *      ordinary package do not, refuse-listed apps warn, and
 *      isolated-process users (uN_iNNN) count as app users.
 *   6. STATUS + DETAIL LINES: the one-line status and the
 *      Details text carry exactly what ps reported, humanized
 *      memory, the mapping fact, and the governance fact —
 *      nothing invented.
 *   7. PM COMMANDS: disable builds exactly
 *      `pm disable-user --user 0 <pkg>` (no bare "disable"
 *      element anywhere in the argv); enable builds exactly
 *      `pm enable <pkg>`.
 *   8. PM VERIFY: pm's confirming state line is success; error
 *      lines, empty output, garbage, a state line for the wrong
 *      action, and a state line bundled with an error are all
 *      failures that carry pm's own words.
 *   9. END TO END: a realistic ps fixture shapes into the rows
 *      the screen would render, with Control's own process
 *      flagged and a secondary process mapped to its package.
 *  10. CLASSIFICATION (v1.34): groupFor puts a row in
 *      CONTROLLABLE exactly when it has a known mapping, is
 *      not refuse-listed, and is not system-marked — the rows
 *      whose menu offers Kill and Disable/Enable. System-marked,
 *      refuse-listed, and unmapped rows are SYSTEM, even with a
 *      mapping; the mapping rule itself is unchanged.
 *  11. FILTER (v1.34): inGroup returns exactly one group in ps
 *      order, the groups partition the fetch, counts match, an
 *      empty group yields an empty list with the honest empty
 *      text, and listStatus names the total, both group sizes,
 *      and the cap honestly under every selection.
 *  12. OVERRIDE DECISION (v1.36): Control itself refuses under
 *      override on and off; every refuse-listed package
 *      refuses with the override off and allows with it on;
 *      ordinary packages allow either way; null/empty refuse
 *      (fail closed); refusal wordings match the bridge's
 *      long-standing strings; the lockout reason names the
 *      re-enable problem.
 *  13. OVERRIDE CONSEQUENCES (v1.36): every refuse-listed
 *      package except Control itself has a specific
 *      consequence (never the generic fallback); the known
 *      packages name what breaks; the recovery text carries
 *      the exact adb re-enable command.
 *  14. OVERRIDE SCOPE (v1.36): the override op is
 *      SET_APP_ENABLED and nothing else; overrideApplies is
 *      true only for switch-on + protected + not-Control; the
 *      audit marker, menu labels, and switch copy are pinned.
 *  15. OLD-BRIDGE FAIL-CLOSED (v1.36): with the override
 *      field absent (exactly what a pre-v7 bridge computes —
 *      it never reads the field), every protected package
 *      refuses; BridgeGate requires v10 and ShellBridgeMain
 *      reports v10, so a v9 bridge classifies STALE.
 *  16. PROCESS MENUS (v1.37): Details is always first; a row
 *      with no known package carries the explicit disabled
 *      explainer and nothing else; Control's own row carries
 *      its disabled explainer and no Kill/toggle; every other
 *      mapped row — including protected rows — offers Kill
 *      and Enable/Disable, with the label following the fresh
 *      enabled state (null defaults to Disable); a secondary
 *      process maps to its package and gets the full menu.
 *  17. PROTOCOL BUMPED (v1.38, v1.43, v1.44): the bridge reports v10 and the
 *      gate requires v10 — a v9 bridge classifies STALE, so
 *      Adam restarts the bridge once and the new hold-watch
 *      diagnostics op is actually running.
 *  18. METERS (v1.39): byte formatting (B/KB/MB/GB, shared-unit
 *      used/total pairs), used fractions (clamped, NaN on
 *      impossible inputs), percent labels, /proc/stat parsing
 *      (aggregate line only, idle + iowait, malformed lines
 *      rejected) and delta usage (NaN on no/backwards deltas),
 *      battery labels (charging/full) and the low-battery warn
 *      rule, traffic labels and the mobile-share fraction
 *      (NaN when mobile counters are unknown). Unavailable is
 *      a value, never a faked zero.
 *  19. HUB ROWS (v1.39): the hub lists 18 groups; every index
 *      maps to a valid PageGlyphs constant, spot mappings match
 *      the documented table, and out-of-range indices fall back
 *      to INFO — a row never loses its icon.
 *
 * Run (from ~/workspace/control-launcher):
 *   ~/workspace/run-launcher-harnesses.sh DeepControl
 *
 * What this harness cannot prove: the ps dialect on a real
 * phone (toybox columns are assumed — a differently-shaped ps
 * line is skipped, never misread), pm's exact wording on
 * Android 16 (the "new state:" line is the documented pm
 * output; anything else fails honestly), the switch itself
 * (Android UI — the default-OFF pref and the gating are
 * proven by inspection plus the on-device checklist), or the
 * screen in hand. The meter platform reads (ActivityManager,
 * BatteryManager, StatFs, TrafficStats, /proc/stat) are
 * Android-only and proven on-device; the shaping they feed is
 * pinned here.
 */
public class DeepControlHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    private static final Set<String> INSTALLED = new HashSet<>(Arrays.asList(
            "app.control.launcher", "com.example.app", "com.android.systemui",
            "com.google.android.gms", "com.example.game"));

    public static void main(String[] args) {
        psParsing();
        packageCandidate();
        mapping();
        refuseMatching();
        warningFlags();
        statusAndDetail();
        pmCommands();
        pmVerify();
        endToEnd();
        classification();
        filter();
        overrideDecision();
        overrideConsequences();
        overrideScope();
        oldBridgeFailClosed();
        processMenus();
        protocolBumped();
        meters();
        hubRows();
        System.out.println("DeepControlHarness: " + checks + " checks, "
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

    private static ProcessRules.Row only(String ps, Set<String> installed) {
        List<ProcessRules.Row> rows = ProcessRules.parse(ps, installed);
        eq(1, rows.size(), "fixture parses to exactly one row: " + ps);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---- legs ----

    private static void psParsing() {
        String header = "USER           PID  PPID  VSZ   RSS WCHAN            ADDR S NAME\n";
        eq(0, ProcessRules.parse(header, INSTALLED).size(), "header alone parses to no rows");
        eq(0, ProcessRules.parse("", INSTALLED).size(), "empty output parses to no rows");
        eq(0, ProcessRules.parse(null, INSTALLED).size(), "null output parses to no rows");
        eq(0, ProcessRules.parse("\n\n", INSTALLED).size(), "blank lines parse to no rows");

        ProcessRules.Row app = only(header
                + "u0_a123       1234   567 123456 78900 0                   0 S com.example.app\n",
                INSTALLED);
        eq("u0_a123", app.user, "app row user");
        eq(1234, app.pid, "app row pid");
        eq(567, app.ppid, "app row ppid");
        eq(123456L, app.vszKb, "app row vsz");
        eq(78900L, app.rssKb, "app row rss");
        eq("S", app.state, "app row state");
        eq("com.example.app", app.name, "app row name");

        ProcessRules.Row sys = only(header
                + "system         789     1  98765  4321 0                   0 S system_server\n",
                INSTALLED);
        eq("system_server", sys.name, "system row name");
        eq(789, sys.pid, "system row pid");

        ProcessRules.Row kern = only(header
                + "root            42     2      0     0 0                   0 I [kworker/0:1H]\n",
                INSTALLED);
        eq("[kworker/0:1H]", kern.name, "kernel thread name keeps brackets");
        eq("I", kern.state, "kernel thread state");

        ProcessRules.Row remote = only(header
                + "u0_a123       2345  1234  55555  1111 0                   0 R com.example.app:remote\n",
                INSTALLED);
        eq("com.example.app:remote", remote.name, "secondary process keeps its suffix");
        eq("R", remote.state, "running state letter");

        // Malformed lines are skipped, never misread.
        eq(0, ProcessRules.parse(header + "u0_a123 12\n", INSTALLED).size(),
                "too-few-columns line skipped");
        eq(0, ProcessRules.parse(header + "u0_a123 abc 1 2 3 0 0 S x.y\n", INSTALLED).size(),
                "non-numeric pid line skipped");
        eq(0, ProcessRules.parse(header + "u0_a123 1 2 xx 3 0 0 S x.y\n", INSTALLED).size(),
                "non-numeric vsz line skipped");
        eq(0, ProcessRules.parse(header + "u0_a123 1 2 3 4 0 0 S \n", INSTALLED).size(),
                "empty name line skipped");

        // Order is ps order, and a bad line in the middle drops out.
        List<ProcessRules.Row> rows = ProcessRules.parse(header
                + "root             1     0  1668   1436 0                   0 S init\n"
                + "garbage line\n"
                + "u0_a123       1234   567 123456 78900 0                   0 S com.example.app\n",
                INSTALLED);
        eq(2, rows.size(), "bad middle line drops, good rows survive");
        eq(1, rows.get(0).pid, "first row stays first");
        eq(1234, rows.get(1).pid, "second row stays second");
    }

    private static void packageCandidate() {
        eq("com.example.app", ProcessRules.packageCandidate("com.example.app"),
                "bare package name is its own candidate");
        eq("com.example.app", ProcessRules.packageCandidate("com.example.app:remote"),
                "secondary process maps to its base package");
        eq("com.example.app", ProcessRules.packageCandidate("com.example.app:"),
                "trailing colon still maps to the base");
        eq(null, ProcessRules.packageCandidate("system_server"), "system_server has no candidate");
        eq(null, ProcessRules.packageCandidate("zygote64"), "zygote64 has no candidate");
        eq(null, ProcessRules.packageCandidate("[kworker/0:1H]"), "kernel thread has no candidate");
        eq(null, ProcessRules.packageCandidate("app_process"), "app_process has no candidate");
        eq(null, ProcessRules.packageCandidate("1bad.name"), "leading digit is not a package");
        eq(null, ProcessRules.packageCandidate("nodots"), "single segment is not a package");
        eq(null, ProcessRules.packageCandidate(""), "empty name has no candidate");
        eq(null, ProcessRules.packageCandidate(null), "null name has no candidate");
        eq("com.example", ProcessRules.packageCandidate("com.example"),
                "two-segment name is a candidate");
    }

    private static void mapping() {
        ProcessRules.Row mapped = only(
                "u0_a123 1234 567 100 200 0 0 S com.example.app\n", INSTALLED);
        eq("com.example.app", mapped.packageName, "installed candidate maps");

        ProcessRules.Row uninstalled = only(
                "u0_a124 1235 567 100 200 0 0 S com.not.installed\n", INSTALLED);
        eq(null, uninstalled.packageName, "uninstalled lookalike stays unmapped");

        ProcessRules.Row suffix = only(
                "u0_a123 1236 567 100 200 0 0 S com.example.game:downloader\n", INSTALLED);
        eq("com.example.game", suffix.packageName, "secondary process maps to its package");

        ProcessRules.Row noSet = only(
                "u0_a123 1237 567 100 200 0 0 S com.example.app\n", null);
        eq(null, noSet.packageName, "null installed set maps nothing, never guesses");

        ProcessRules.Row sys = only(
                "system 789 1 100 200 0 0 S system_server\n", INSTALLED);
        eq(null, sys.packageName, "system process has no mapping");

        ProcessRules.Row customName = only(
                "u0_a5 777 1 100 200 0 0 S com.google.android.gms.persistent\n", INSTALLED);
        eq(null, customName.packageName,
                "custom process name (package + suffix, no colon) stays unmapped, never guessed");
    }

    private static void refuseMatching() {
        check(ProcessRules.refuseReason("app.control.launcher") != null,
                "Control itself is refuse-listed");
        check(ProcessRules.refuseReason("android") != null, "android is refuse-listed");
        check(ProcessRules.refuseReason("com.android.systemui") != null,
                "systemui is refuse-listed");
        check(ProcessRules.refuseReason("com.google.android.gms") != null,
                "Play services is refuse-listed (destructive)");
        check(ProcessRules.refuseReason("com.android.bluetooth") != null,
                "bluetooth is refuse-listed (destructive)");
        eq(null, ProcessRules.refuseReason("com.example.app"),
                "ordinary package has no refuse reason");
        eq(null, ProcessRules.refuseReason(null), "null package has no refuse reason");
        check(ProcessRules.refuseReason("com.google.android.gms").contains("Force-stop"),
                "destructive reason names the protected actions");
        check(ProcessRules.REFUSE_ALL.contains("com.android.phone"),
                "REFUSE_ALL holds the phone package");
        check(ProcessRules.REFUSE_DESTRUCTIVE.contains("com.android.vending"),
                "REFUSE_DESTRUCTIVE holds the Play Store");
        check(!ProcessRules.REFUSE_ALL.contains("com.example.app"),
                "REFUSE_ALL does not hold ordinary packages");
    }

    private static void warningFlags() {
        ProcessRules.Row appRow = only(
                "u0_a123 1234 567 100 200 0 0 S com.example.app\n", INSTALLED);
        check(!appRow.systemUser, "app user is not a system user");
        check(!appRow.warning, "ordinary app row carries no warning");

        ProcessRules.Row sysRow = only(
                "system 789 1 100 200 0 0 S system_server\n", INSTALLED);
        check(sysRow.systemUser, "system user is a system user");
        check(sysRow.warning, "system row warns");

        ProcessRules.Row rootRow = only(
                "root 42 2 0 0 0 0 I [kworker/0:1H]\n", INSTALLED);
        check(rootRow.systemUser, "root user is a system user");
        check(rootRow.warning, "kernel row warns");

        ProcessRules.Row shellRow = only(
                "shell 999 1 100 200 0 0 S app_process\n", INSTALLED);
        check(shellRow.systemUser, "shell user is a system user");

        ProcessRules.Row isolated = only(
                "u0_i123 555 1 100 200 0 0 S com.example.app:isolated\n", INSTALLED);
        check(!isolated.systemUser, "isolated-process user counts as an app user");
        eq("com.example.app", isolated.packageName, "isolated process still maps");

        ProcessRules.Row gms = only(
                "u0_a5 777 1 100 200 0 0 S com.google.android.gms\n", INSTALLED);
        check(!gms.systemUser, "Play services runs as an app user");
        check(gms.warning, "refuse-listed app row warns");
        check(gms.refuseReason != null, "refuse-listed app row carries the reason");

        ProcessRules.Row control = only(
                "u0_a99 888 1 100 200 0 0 S app.control.launcher\n", INSTALLED);
        check(control.warning, "Control's own row warns");
    }

    private static void statusAndDetail() {
        ProcessRules.Row row = only(
                "u0_a123 1234 567 123456 78900 0 0 S com.example.app\n", INSTALLED);
        String status = ProcessRules.statusLine(row);
        check(status.contains("PID 1234"), "status line names the PID");
        check(status.contains("S"), "status line carries the state letter");
        check(status.contains("77 MB"), "status line humanizes RSS (78900 KB -> 77 MB): " + status);
        check(!status.contains("system"), "ordinary status line has no system marker");

        eq("512 KB", ProcessRules.humanKb(512), "sub-MB memory stays KB");
        eq("1 MB", ProcessRules.humanKb(1024), "exactly 1 MB");
        eq("2 MB", ProcessRules.humanKb(1536), "1.5 MB rounds to 2 MB");
        eq("0 KB", ProcessRules.humanKb(0), "zero memory");

        eq("Running", ProcessRules.stateWord("R"), "R is Running");
        eq("Sleeping", ProcessRules.stateWord("S"), "S is Sleeping");
        eq("Waiting on disk", ProcessRules.stateWord("D"), "D is disk wait");
        eq("Zombie", ProcessRules.stateWord("Z"), "Z is Zombie");
        eq("State Q", ProcessRules.stateWord("Q"), "unknown state letter named as-is");
        eq("unknown", ProcessRules.stateWord(""), "empty state is unknown");

        String detail = ProcessRules.detailText(row);
        check(detail.contains("com.example.app"), "detail names the process");
        check(detail.contains("PID: 1234"), "detail names the PID");
        check(detail.contains("Parent PID: 567"), "detail names the parent PID");
        check(detail.contains("u0_a123"), "detail names the user");
        check(detail.contains("Sleeping"), "detail words the state");
        check(detail.contains("Package: com.example.app"), "detail names the package");

        ProcessRules.Row unmapped = only(
                "system 789 1 100 200 0 0 S system_server\n", INSTALLED);
        String unmappedDetail = ProcessRules.detailText(unmapped);
        check(unmappedDetail.contains("no package mapping is known"),
                "unmapped detail says so honestly");
        check(unmappedDetail.contains("system process"),
                "unmapped system detail says it is a system process");

        ProcessRules.Row gms = only(
                "u0_a5 777 1 100 200 0 0 S com.google.android.gms\n", INSTALLED);
        check(ProcessRules.detailText(gms).contains("Protected:"),
                "refuse-listed detail carries the protection");
    }

    private static void pmCommands() {
        String[] disable = PmOutcome.disableCommand("com.example.app");
        eq(5, disable.length, "disable argv has 5 elements");
        eq("/system/bin/pm", disable[0], "disable argv[0] is pm");
        eq("disable-user", disable[1], "disable argv[1] is disable-user");
        eq("--user", disable[2], "disable argv[2] is --user");
        eq("0", disable[3], "disable argv[3] is user 0");
        eq("com.example.app", disable[4], "disable argv[4] is the package");
        for (String element : disable) {
            check(!"disable".equals(element),
                    "no bare \"disable\" element in the disable argv (found: " + element + ")");
        }

        String[] enable = PmOutcome.enableCommand("com.example.app");
        eq(3, enable.length, "enable argv has 3 elements");
        eq("/system/bin/pm", enable[0], "enable argv[0] is pm");
        eq("enable", enable[1], "enable argv[1] is enable");
        eq("com.example.app", enable[2], "enable argv[2] is the package");
    }

    private static void pmVerify() {
        PmOutcome.Result dOk = PmOutcome.verify(
                "Package com.example.app new state: disabled-user\n", false);
        check(dOk.ok, "disable-user state line is success");
        check(dOk.message.contains("disabled-user"), "success message carries pm's line");

        PmOutcome.Result dLegacy = PmOutcome.verify(
                "Package com.example.app new state: disabled\n", false);
        check(dLegacy.ok, "plain disabled state line is success for disable");

        PmOutcome.Result eOk = PmOutcome.verify(
                "Package com.example.app new state: enabled\n", true);
        check(eOk.ok, "enabled state line is success");

        PmOutcome.Result secFail = PmOutcome.verify(
                "Error: java.lang.SecurityException: Permission denial: attempt to change "
                        + "component state from pid=2000\n", false);
        check(!secFail.ok, "SecurityException refusal is a failure");
        check(secFail.message.contains("SecurityException"),
                "failure message carries pm's own words");

        PmOutcome.Result unknownFail = PmOutcome.verify(
                "Error: Unknown package: com.example.app\n", false);
        check(!unknownFail.ok, "unknown-package error is a failure");

        PmOutcome.Result emptyFail = PmOutcome.verify("", false);
        check(!emptyFail.ok, "empty output is a failure");
        check(emptyFail.message.contains("did not confirm"),
                "empty output says not confirmed");

        PmOutcome.Result nullFail = PmOutcome.verify(null, true);
        check(!nullFail.ok, "null output is a failure");

        PmOutcome.Result garbageFail = PmOutcome.verify("hello world\n", false);
        check(!garbageFail.ok, "unrecognized output is a failure");
        check(garbageFail.message.contains("hello world"),
                "garbage failure echoes pm's output");

        PmOutcome.Result wrongState = PmOutcome.verify(
                "Package com.example.app new state: enabled\n", false);
        check(!wrongState.ok, "an enabled line does not confirm a disable");

        PmOutcome.Result mixed = PmOutcome.verify(
                "Error: something went wrong\nPackage com.example.app new state: disabled-user\n",
                false);
        check(!mixed.ok, "an error marker wins over a state line");

        PmOutcome.Result excOnly = PmOutcome.verify(
                "java.lang.IllegalArgumentException: Unknown package\n", true);
        check(!excOnly.ok, "bare exception text is a failure");
    }

    private static void endToEnd() {
        String ps = "USER           PID  PPID  VSZ   RSS WCHAN            ADDR S NAME\n"
                + "root             1     0  1668   1436 0                   0 S init\n"
                + "system         789     1  98765  43210 0                   0 S system_server\n"
                + "u0_a99         888     1  55555  12345 0                   0 S app.control.launcher\n"
                + "u0_a123       1234   567 123456  78900 0                   0 S com.example.app\n"
                + "u0_a123       2345  1234  44444   9999 0                   0 R com.example.app:remote\n"
                + "u0_a5          777     1  77777  22222 0                   0 S com.google.android.gms\n"
                + "root            42     2      0      0 0                   0 I [kworker/0:1H]\n";
        List<ProcessRules.Row> rows = ProcessRules.parse(ps, INSTALLED);
        eq(7, rows.size(), "fixture shapes to 7 rows");
        eq("init", rows.get(0).name, "first row is init");
        check(rows.get(0).warning, "init warns (system user)");
        eq(null, rows.get(1).packageName, "system_server unmapped");
        eq("app.control.launcher", rows.get(2).packageName, "Control row maps to itself");
        check(rows.get(2).warning, "Control row warns (refuse-listed)");
        eq("com.example.app", rows.get(3).packageName, "app row maps");
        eq("com.example.app", rows.get(4).packageName, "remote process maps to its package");
        check(rows.get(5).warning, "gms row warns");
        eq("[kworker/0:1H]", rows.get(6).name, "kernel row last, in ps order");
        long mapped = 0;
        for (ProcessRules.Row r : rows) if (r.packageName != null) mapped++;
        eq(4L, mapped, "4 of 7 rows have a known mapping");
    }

    private static void classification() {
        ProcessRules.Row app = only(
                "u0_a123 1234 567 100 200 0 0 S com.example.app\n", INSTALLED);
        eq(ProcessRules.Group.CONTROLLABLE, ProcessRules.groupFor(app),
                "mapped, clean, app-user row is controllable");

        ProcessRules.Row remote = only(
                "u0_a123 2345 1234 100 200 0 0 R com.example.app:remote\n", INSTALLED);
        eq(ProcessRules.Group.CONTROLLABLE, ProcessRules.groupFor(remote),
                "mapped secondary process is controllable");

        ProcessRules.Row isolated = only(
                "u0_i123 555 1 100 200 0 0 S com.example.app:isolated\n", INSTALLED);
        eq(ProcessRules.Group.CONTROLLABLE, ProcessRules.groupFor(isolated),
                "mapped isolated-process row is controllable");

        // System-marked WITH a mapping is still SYSTEM.
        ProcessRules.Row sysui = only(
                "system 789 1 100 200 0 0 S com.android.systemui\n", INSTALLED);
        eq("com.android.systemui", sysui.packageName, "systemui row maps");
        check(sysui.systemUser, "systemui row is system-marked");
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(sysui),
                "system-marked row is SYSTEM even with a mapping");

        // Refuse-listed WITH a mapping is still SYSTEM.
        ProcessRules.Row gms = only(
                "u0_a5 777 1 100 200 0 0 S com.google.android.gms\n", INSTALLED);
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(gms),
                "refuse-listed row is SYSTEM even with a mapping");
        ProcessRules.Row control = only(
                "u0_a99 888 1 100 200 0 0 S app.control.launcher\n", INSTALLED);
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(control),
                "Control's own refuse-listed row is SYSTEM");

        // Unmapped rows are SYSTEM.
        ProcessRules.Row server = only(
                "system 789 1 100 200 0 0 S system_server\n", INSTALLED);
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(server),
                "system_server is SYSTEM");
        ProcessRules.Row kern = only(
                "root 42 2 0 0 0 0 I [kworker/0:1H]\n", INSTALLED);
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(kern),
                "kernel thread is SYSTEM");
        ProcessRules.Row uninstalled = only(
                "u0_a124 1235 567 100 200 0 0 S com.not.installed\n", INSTALLED);
        eq(null, uninstalled.packageName, "uninstalled lookalike stays unmapped");
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(uninstalled),
                "unmapped app-user row is SYSTEM");
        ProcessRules.Row customName = only(
                "u0_a5 777 1 100 200 0 0 S com.google.android.gms.persistent\n", INSTALLED);
        eq(null, customName.packageName,
                "custom process name stays unmapped (mapping rule unchanged)");
        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(customName),
                "custom-name row is SYSTEM");

        eq(ProcessRules.Group.SYSTEM, ProcessRules.groupFor(null),
                "null row is SYSTEM, never a crash");
    }

    private static void filter() {
        String ps = "USER           PID  PPID  VSZ   RSS WCHAN            ADDR S NAME\n"
                + "root             1     0  1668   1436 0                   0 S init\n"
                + "u0_a123       1234   567 123456  78900 0                   0 S com.example.app\n"
                + "system         789     1  98765  43210 0                   0 S system_server\n"
                + "u0_a123       2345  1234  44444   9999 0                   0 R com.example.app:remote\n"
                + "u0_a5          777     1  77777  22222 0                   0 S com.google.android.gms\n"
                + "u0_a124       3456   567  22222   3333 0                   0 S com.example.game\n"
                + "root            42     2      0      0 0                   0 I [kworker/0:1H]\n";
        List<ProcessRules.Row> rows = ProcessRules.parse(ps, INSTALLED);
        eq(7, rows.size(), "filter fixture shapes to 7 rows");

        List<ProcessRules.Row> controllable =
                ProcessRules.inGroup(rows, ProcessRules.Group.CONTROLLABLE);
        eq(3, controllable.size(), "controllable group holds 3 rows");
        eq(1234, controllable.get(0).pid, "controllable group keeps ps order (first)");
        eq(2345, controllable.get(1).pid, "controllable group keeps ps order (second)");
        eq(3456, controllable.get(2).pid, "controllable group keeps ps order (third)");

        List<ProcessRules.Row> system =
                ProcessRules.inGroup(rows, ProcessRules.Group.SYSTEM);
        eq(4, system.size(), "system group holds 4 rows");
        eq(1, system.get(0).pid, "system group keeps ps order (first)");
        eq(42, system.get(3).pid, "system group keeps ps order (last)");

        // The groups partition the fetch: no overlap, nothing lost.
        eq(7, controllable.size() + system.size(), "groups partition the fetch");
        for (ProcessRules.Row r : controllable) {
            check(!system.contains(r), "no controllable row is also in the system group");
        }
        eq(3, ProcessRules.countGroup(rows, ProcessRules.Group.CONTROLLABLE),
                "controllable count matches");
        eq(4, ProcessRules.countGroup(rows, ProcessRules.Group.SYSTEM),
                "system count matches");
        eq(0, ProcessRules.inGroup(null, ProcessRules.Group.SYSTEM).size(),
                "null list filters to an empty list");
        eq(0, ProcessRules.inGroup(new ArrayList<>(), ProcessRules.Group.CONTROLLABLE).size(),
                "empty list filters to an empty list");

        // Empty group: a fetch with no controllable rows.
        List<ProcessRules.Row> sysOnly = ProcessRules.parse(
                "system 789 1 100 200 0 0 S system_server\n"
                        + "root 42 2 0 0 0 0 I [kworker/0:1H]\n", INSTALLED);
        eq(0, ProcessRules.inGroup(sysOnly, ProcessRules.Group.CONTROLLABLE).size(),
                "all-system fetch has an empty controllable group");
        check(ProcessRules.emptyGroupText(ProcessRules.Group.CONTROLLABLE)
                        .contains("No processes you can control"),
                "empty controllable text says so honestly");
        check(ProcessRules.emptyGroupText(ProcessRules.Group.SYSTEM)
                        .contains("No system processes"),
                "empty system text says so honestly");

        // Status lines: total + both group sizes under every selection.
        String all = ProcessRules.listStatus(rows, null, 7);
        eq("7 processes — 3 can be controlled · 4 system", all,
                "All status names the total and the split");
        String allCapped = ProcessRules.listStatus(rows, null, 3);
        check(allCapped.contains("showing the first 3"),
                "All status names the cap: " + allCapped);
        check(!allCapped.contains("of this group"),
                "All cap note does not claim a group: " + allCapped);

        String ctl = ProcessRules.listStatus(rows, ProcessRules.Group.CONTROLLABLE, 3);
        eq("3 processes you can control · 4 system (7 loaded)", ctl,
                "Can control status names the group, the other group, and the total");
        String ctlCapped = ProcessRules.listStatus(rows, ProcessRules.Group.CONTROLLABLE, 2);
        check(ctlCapped.contains("showing the first 2 of this group"),
                "group cap note bounds the group, not the fetch: " + ctlCapped);

        String sys = ProcessRules.listStatus(rows, ProcessRules.Group.SYSTEM, 4);
        eq("4 system processes · 3 you can control (7 loaded)", sys,
                "System status names the group, the other group, and the total");
        String sysCapped = ProcessRules.listStatus(rows, ProcessRules.Group.SYSTEM, 2);
        check(sysCapped.contains("showing the first 2 of this group"),
                "system cap note bounds the group: " + sysCapped);

        String empty = ProcessRules.listStatus(sysOnly, ProcessRules.Group.CONTROLLABLE, 0);
        eq("0 processes you can control · 2 system (2 loaded)", empty,
                "empty group status still names the fetched total");
    }

    // ---- v1.36: protected-package override ----

    private static void overrideDecision() {
        // Control itself: refused always, override or not.
        eq(ProtectedOverride.Decision.REFUSE_LOCKOUT,
                ProtectedOverride.decideEnable(ProtectedOverride.OWN_PACKAGE, false),
                "Control itself refuses with the override off");
        eq(ProtectedOverride.Decision.REFUSE_LOCKOUT,
                ProtectedOverride.decideEnable(ProtectedOverride.OWN_PACKAGE, true),
                "Control itself refuses with the override on");
        check(ProtectedOverride.isOwnPackage(ProtectedOverride.OWN_PACKAGE),
                "own package recognized");
        check(!ProtectedOverride.isOwnPackage("com.android.systemui"),
                "systemui is not Control itself");

        // Every refuse-listed package: refused off, allowed on.
        for (String pkg : ProcessRules.REFUSE_ALL) {
            if (ProtectedOverride.OWN_PACKAGE.equals(pkg)) continue;
            eq(ProtectedOverride.Decision.REFUSE_PROTECTED,
                    ProtectedOverride.decideEnable(pkg, false),
                    "REFUSE_ALL member refuses with the override off: " + pkg);
            eq(ProtectedOverride.Decision.ALLOW,
                    ProtectedOverride.decideEnable(pkg, true),
                    "REFUSE_ALL member allows with the override on: " + pkg);
            check(ProtectedOverride.isProtected(pkg),
                    "REFUSE_ALL member is protected: " + pkg);
        }
        for (String pkg : ProcessRules.REFUSE_DESTRUCTIVE) {
            eq(ProtectedOverride.Decision.REFUSE_PROTECTED,
                    ProtectedOverride.decideEnable(pkg, false),
                    "REFUSE_DESTRUCTIVE member refuses with the override off: " + pkg);
            eq(ProtectedOverride.Decision.ALLOW,
                    ProtectedOverride.decideEnable(pkg, true),
                    "REFUSE_DESTRUCTIVE member allows with the override on: " + pkg);
            check(ProtectedOverride.isProtected(pkg),
                    "REFUSE_DESTRUCTIVE member is protected: " + pkg);
        }

        // Ordinary packages: allowed either way.
        eq(ProtectedOverride.Decision.ALLOW,
                ProtectedOverride.decideEnable("com.example.app", false),
                "ordinary package allows with the override off");
        eq(ProtectedOverride.Decision.ALLOW,
                ProtectedOverride.decideEnable("com.example.app", true),
                "ordinary package allows with the override on");
        check(!ProtectedOverride.isProtected("com.example.app"),
                "ordinary package is not protected");
        check(!ProtectedOverride.isProtected(null),
                "null package is not protected");

        // Fail closed on malformed input.
        eq(ProtectedOverride.Decision.REFUSE_PROTECTED,
                ProtectedOverride.decideEnable(null, true),
                "null package refuses even with the override on");
        eq(ProtectedOverride.Decision.REFUSE_PROTECTED,
                ProtectedOverride.decideEnable("", true),
                "empty package refuses even with the override on");

        // Refusal wordings: the protected strings are exactly
        // the bridge's long-standing refusals, so the
        // non-override path reads as it always has.
        eq("Refused: com.android.systemui is on the bridge refuse-list.",
                ProtectedOverride.refusalText("com.android.systemui",
                        ProtectedOverride.Decision.REFUSE_PROTECTED),
                "REFUSE_ALL refusal wording unchanged");
        eq("Refused: destructive control of com.google.android.gms is protected by the bridge refuse-list.",
                ProtectedOverride.refusalText("com.google.android.gms",
                        ProtectedOverride.Decision.REFUSE_PROTECTED),
                "REFUSE_DESTRUCTIVE refusal wording unchanged");
        eq("Refused: com.google.android.packageinstaller is on the bridge refuse-list.",
                ProtectedOverride.refusalText("com.google.android.packageinstaller",
                        ProtectedOverride.Decision.REFUSE_PROTECTED),
                "package in both lists takes the REFUSE_ALL wording");
        check(ProtectedOverride.refusalText(ProtectedOverride.OWN_PACKAGE,
                        ProtectedOverride.Decision.REFUSE_LOCKOUT)
                        .contains("only tool that can re-enable it"),
                "lockout reason names the re-enable problem");
        eq(ProtectedOverride.lockoutReason(),
                ProtectedOverride.refusalText(ProtectedOverride.OWN_PACKAGE,
                        ProtectedOverride.Decision.REFUSE_LOCKOUT),
                "lockout refusal is the single-sourced reason");
        eq("", ProtectedOverride.refusalText("com.example.app",
                ProtectedOverride.Decision.ALLOW),
                "ALLOW has no refusal text");
    }

    private static void overrideConsequences() {
        String fallback = ProtectedOverride.consequenceFor("com.example.not.a.real.package");
        check(!fallback.isEmpty(), "fallback consequence is non-empty");
        check(fallback.contains("system-critical"), "fallback names system-critical");

        // Every list member except Control itself has a
        // specific consequence — never the generic fallback.
        for (String pkg : ProcessRules.REFUSE_ALL) {
            if (ProtectedOverride.OWN_PACKAGE.equals(pkg)) continue;
            String c = ProtectedOverride.consequenceFor(pkg);
            check(!c.isEmpty(), "consequence non-empty: " + pkg);
            check(!c.equals(fallback), "consequence is specific, not fallback: " + pkg);
            check(c.contains("re-enabled"), "consequence names re-enabling: " + pkg);
        }
        for (String pkg : ProcessRules.REFUSE_DESTRUCTIVE) {
            String c = ProtectedOverride.consequenceFor(pkg);
            check(!c.isEmpty(), "consequence non-empty: " + pkg);
            check(!c.equals(fallback), "consequence is specific, not fallback: " + pkg);
            check(c.contains("re-enabled"), "consequence names re-enabling: " + pkg);
        }

        // The known packages name what breaks.
        check(ProtectedOverride.consequenceFor("com.android.systemui")
                        .contains("status bar"), "systemui consequence names the status bar");
        check(ProtectedOverride.consequenceFor("com.android.settings")
                        .contains("Settings app"), "settings consequence names Settings");
        check(ProtectedOverride.consequenceFor("com.android.phone")
                        .contains("phone calls"), "phone consequence names calls");
        check(ProtectedOverride.consequenceFor("com.android.server.telecom")
                        .contains("phone calls"), "telecom consequence names calls");
        check(ProtectedOverride.consequenceFor("com.google.android.gms")
                        .contains("Play services"), "gms consequence names Play services");
        check(ProtectedOverride.consequenceFor("com.android.vending")
                        .contains("Play Store"), "vending consequence names the Play Store");
        check(ProtectedOverride.consequenceFor("com.android.bluetooth")
                        .contains("Bluetooth"), "bluetooth consequence names Bluetooth");
        check(ProtectedOverride.consequenceFor("com.android.nfc")
                        .contains("NFC"), "nfc consequence names NFC");
        check(ProtectedOverride.consequenceFor("com.google.android.euicc")
                        .contains("eSIM"), "euicc consequence names eSIM");

        // Recovery: the exact adb re-enable command.
        String recovery = ProtectedOverride.recoveryText("com.example.app");
        check(recovery.contains("adb shell pm enable com.example.app"),
                "recovery text carries the exact adb command");
        check(recovery.contains("re-enable"), "recovery text names re-enabling");

        String disableBody = ProtectedOverride.disableConfirmBody("com.android.systemui");
        check(disableBody.contains("status bar"), "disable body names the consequence");
        check(disableBody.contains("adb shell pm enable com.android.systemui"),
                "disable body names the recovery command");
        String enableBody = ProtectedOverride.enableConfirmBody("com.android.systemui");
        check(enableBody.contains("re-enables"), "enable body says re-enables");
        check(enableBody.contains("adb shell pm enable com.android.systemui"),
                "enable body names the recovery command");
        // v1.38: the bodies are terse — the exact package first,
        // the consequence, the recovery command last with no
        // trailing period (copy-pasteable), no surrounding prose.
        check(disableBody.startsWith("com.android.systemui\n"),
                "disable body names the exact package first");
        check(enableBody.startsWith("com.android.systemui\n"),
                "enable body names the exact package first");
        check(disableBody.endsWith("adb shell pm enable com.android.systemui"),
                "disable body ends with the recovery command, no trailing period");
        check(enableBody.endsWith("adb shell pm enable com.android.systemui"),
                "enable body ends with the recovery command, no trailing period");
        check(disableBody.length() < 280, "disable body stays short");
        check(enableBody.length() < 200, "enable body stays short");
    }

    private static void overrideScope() {
        eq("SET_APP_ENABLED", ProtectedOverride.OVERRIDE_OP,
                "the override op is SET_APP_ENABLED and nothing else");

        // overrideApplies: switch on + protected + not Control.
        check(ProtectedOverride.overrideApplies("com.android.systemui", true),
                "override applies to a protected package with the switch on");
        check(!ProtectedOverride.overrideApplies("com.android.systemui", false),
                "override does not apply with the switch off");
        check(ProtectedOverride.overrideApplies("com.google.android.gms", true),
                "override applies to a destructive-list package with the switch on");
        check(!ProtectedOverride.overrideApplies("com.example.app", true),
                "override never applies to an ordinary package");
        check(!ProtectedOverride.overrideApplies(ProtectedOverride.OWN_PACKAGE, true),
                "override never applies to Control itself");
        check(!ProtectedOverride.overrideApplies(null, true),
                "override never applies to a null package");

        eq("override=protected-disable", ProtectedOverride.AUDIT_MARKER,
                "audit marker names the override");

        // Menu labels: a protected row names the risk.
        eq("Disable app", ProtectedOverride.menuLabel(true, false),
                "ordinary disable label unchanged");
        eq("Enable app", ProtectedOverride.menuLabel(false, false),
                "ordinary enable label unchanged");
        eq("Disable app — protected", ProtectedOverride.menuLabel(true, true),
                "protected disable label names the risk");
        eq("Enable app — protected", ProtectedOverride.menuLabel(false, true),
                "protected enable label names the risk");

        // Switch copy: risks + recovery + the Control exception.
        check(!ProtectedOverride.switchConfirmTitle().isEmpty(),
                "switch confirm title is non-empty");
        String body = ProtectedOverride.switchConfirmBody();
        check(body.contains("status bar"), "switch copy names the status bar risk");
        check(body.contains("Settings"), "switch copy names the Settings risk");
        check(body.contains("phone calls"), "switch copy names the calls risk");
        check(body.contains("Play services"), "switch copy names the Play services risk");
        check(body.contains("re-enabling"), "switch copy names the recovery");
        check(body.contains("adb"), "switch copy names the adb recovery");
        check(body.contains("Control itself can never be disabled"),
                "switch copy names the Control exception");
        check(ProtectedOverride.switchOnText().contains("strong confirmation"),
                "switch-on status names the per-action confirmation");
        check(ProtectedOverride.switchOnText().contains("Control itself can never be disabled"),
                "switch-on status names the Control exception");
        check(ProtectedOverride.switchOffText().contains("stay refused"),
                "switch-off status says protected packages stay refused");
    }

    private static void oldBridgeFailClosed() {
        // A v6 bridge never reads the "override" field, so its
        // SET_APP_ENABLED path computes exactly decideEnable
        // with override=false. Every protected package must
        // refuse under that input — the fail-closed property.
        // (The harness cannot run the old bridge; the property
        // is that the old code path and this input are the
        // same computation, and the v7 bridge reads the field
        // in exactly one place — proven by grep in BUILD_NOTES.)
        for (String pkg : ProcessRules.REFUSE_ALL) {
            check(ProtectedOverride.decideEnable(pkg, false)
                            != ProtectedOverride.Decision.ALLOW,
                    "old bridge refuses REFUSE_ALL member: " + pkg);
        }
        for (String pkg : ProcessRules.REFUSE_DESTRUCTIVE) {
            check(ProtectedOverride.decideEnable(pkg, false)
                            != ProtectedOverride.Decision.ALLOW,
                    "old bridge refuses REFUSE_DESTRUCTIVE member: " + pkg);
        }
        // The version gate forces the restart before the
        // override can work: v9 is STALE, v10 is CURRENT, and
        // both sides of the gate report the same version.
        eq(10, BridgeGate.HOLD_WATCH_VERSION, "gate requires v10");
        eq(10, ShellBridgeMain.VERSION, "bridge reports v10");
        eq(BridgeGate.HOLD_WATCH_VERSION, ShellBridgeMain.VERSION,
                "gate and bridge versions agree");
        eq(BridgeGate.State.STALE, BridgeGate.classify(true, 9),
                "answered v9 is STALE under v1.44");
        eq(BridgeGate.State.CURRENT, BridgeGate.classify(true, 10),
                "answered v10 is CURRENT");
    }

    private static void processMenus() {
        String header = "USER           PID  PPID  VSZ   RSS WCHAN            ADDR S NAME\n";
        // No known package: Details plus the explicit
        // explainer, and nothing else — never a silent
        // omission, never a guessed mapping.
        ProcessRules.Row unmapped = only(header
                + "system         789     1  98765  4321 0                   0 S system_server\n",
                INSTALLED);
        eq(null, unmapped.packageName, "system_server has no mapping");
        List<ProcessRules.MenuItem> items = ProcessRules.menuItems(unmapped, true);
        eq(2, items.size(), "unmapped menu has two items");
        eq("Details", items.get(0).label, "unmapped menu starts with Details");
        check(items.get(0).enabled, "unmapped Details is enabled");
        eq("details", items.get(0).action, "unmapped Details action");
        eq("No package found for this process — nothing to disable",
                items.get(1).label, "unmapped explainer wording");
        check(!items.get(1).enabled, "unmapped explainer is disabled");
        eq("none", items.get(1).action, "unmapped explainer runs nothing");
        // A null row gets the same menu.
        items = ProcessRules.menuItems(null, null);
        eq(2, items.size(), "null row menu has two items");
        eq("No package found for this process — nothing to disable",
                items.get(1).label, "null row explainer wording");
        // Control's own row: Details plus its explainer only.
        ProcessRules.Row own = only(header
                + "u0_a123       1234   567 123456 78900 0                   0 S app.control.launcher\n",
                INSTALLED);
        items = ProcessRules.menuItems(own, true);
        eq(2, items.size(), "own menu has two items");
        eq("Control itself can never be disabled", items.get(1).label,
                "own explainer wording");
        check(!items.get(1).enabled, "own explainer is disabled");
        // An ordinary enabled app: the full menu, Disable.
        ProcessRules.Row app = only(header
                + "u0_a123       1234   567 123456 78900 0                   0 S com.example.app\n",
                INSTALLED);
        items = ProcessRules.menuItems(app, true);
        eq(3, items.size(), "mapped menu has three items");
        eq("Kill", items.get(1).label, "mapped menu offers Kill");
        check(items.get(1).enabled, "Kill is enabled");
        eq("kill", items.get(1).action, "Kill action");
        eq("Disable app", items.get(2).label, "enabled app offers Disable");
        eq("toggle", items.get(2).action, "toggle action");
        // A disabled app offers Enable.
        items = ProcessRules.menuItems(app, false);
        eq("Enable app", items.get(2).label, "disabled app offers Enable");
        // A vanished package (null state) defaults to Disable;
        // the governed path refuses honestly if it is gone.
        items = ProcessRules.menuItems(app, null);
        eq("Disable app", items.get(2).label, "null state defaults to Disable");
        // A protected row offers the toggle too (v1.37 change
        // from v1.36's menu hiding): the governed confirm
        // names the coming refusal and the bridge refuses.
        ProcessRules.Row sysui = only(header
                + "system         790     1  98765  4321 0                   0 S com.android.systemui\n",
                INSTALLED);
        check(ProtectedOverride.isProtected("com.android.systemui"),
                "systemui is a protected package");
        items = ProcessRules.menuItems(sysui, true);
        eq(3, items.size(), "protected menu has three items");
        eq("Disable app — protected", items.get(2).label,
                "protected row offers Disable with the protected label");
        items = ProcessRules.menuItems(sysui, false);
        eq("Enable app — protected", items.get(2).label,
                "protected disabled row offers Enable with the protected label");
        // A secondary process maps to its package and gets
        // the full menu.
        ProcessRules.Row remote = only(header
                + "u0_a123       2345  1234  55555  1111 0                   0 R com.example.app:remote\n",
                INSTALLED);
        eq("com.example.app", remote.packageName, "secondary process maps to its package");
        items = ProcessRules.menuItems(remote, true);
        eq(3, items.size(), "secondary process gets the full menu");
        eq("Disable app", items.get(2).label, "secondary process offers Disable");
    }

    private static void protocolBumped() {
        eq(10, ShellBridgeMain.VERSION, "bridge reports v10 in v1.44");
        eq(10, BridgeGate.HOLD_WATCH_VERSION, "gate requires v10 in v1.44");
        eq(BridgeGate.State.STALE, BridgeGate.classify(true, 9),
                "answered v9 is STALE — restart needed");
        eq(BridgeGate.State.CURRENT, BridgeGate.classify(true, 10),
                "answered v10 is CURRENT");
    }

    private static void meters() {
        eq("0 B", DeviceMeters.formatBytes(0), "bytes: zero");
        eq("512 B", DeviceMeters.formatBytes(512), "bytes: plain bytes");
        eq("1023 B", DeviceMeters.formatBytes(1023), "bytes: just under a KB");
        eq("1 KB", DeviceMeters.formatBytes(1024), "bytes: one KB");
        eq("840 KB", DeviceMeters.formatBytes(860160L), "bytes: KB rounds");
        eq("1.0 MB", DeviceMeters.formatBytes(1024L * 1024L), "bytes: one MB");
        eq("6.1 GB", DeviceMeters.formatBytes(6543114240L), "bytes: GB one decimal");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.formatBytes(-1),
                "bytes: negative is unavailable");
        eq("6.1 / 8.0 GB", DeviceMeters.formatUsedTotal(6543114240L, 8589934592L),
                "used/total: shared GB unit");
        eq("256.0 / 512.0 MB",
                DeviceMeters.formatUsedTotal(256L * 1024L * 1024L, 512L * 1024L * 1024L),
                "used/total: shared MB unit");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.formatUsedTotal(5, 4),
                "used/total: used above total is unavailable");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.formatUsedTotal(1, 0),
                "used/total: zero total is unavailable");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.formatUsedTotal(-1, 4),
                "used/total: negative used is unavailable");
        check(Math.abs(DeviceMeters.usedFraction(3, 4) - 0.75) < 0.0001,
                "fraction: three quarters");
        check(DeviceMeters.usedFraction(0, 4) == 0.0, "fraction: zero used is zero");
        check(DeviceMeters.usedFraction(4, 4) == 1.0, "fraction: full is one");
        check(Double.isNaN(DeviceMeters.usedFraction(5, 4)),
                "fraction: used above total is NaN");
        check(Double.isNaN(DeviceMeters.usedFraction(1, 0)),
                "fraction: zero total is NaN");
        eq("64%", DeviceMeters.formatPercent(0.635), "percent: rounds");
        eq("0%", DeviceMeters.formatPercent(0.0), "percent: zero");
        eq("100%", DeviceMeters.formatPercent(1.0), "percent: full");
        eq("100%", DeviceMeters.formatPercent(1.5), "percent: clamps above one");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.formatPercent(Double.NaN),
                "percent: NaN is unavailable");
        DeviceMeters.CpuSample sample = DeviceMeters.parseCpuLine(
                "cpu  100 0 50 800 10 0 5 0 0 0");
        check(sample != null, "cpu: aggregate line parses");
        eq(965L, sample.total, "cpu: total sums every field");
        eq(810L, sample.idle, "cpu: idle includes iowait");
        check(DeviceMeters.parseCpuLine("cpu0  100 0 50 800 10 0 5 0 0 0") == null,
                "cpu: per-cpu line rejected");
        check(DeviceMeters.parseCpuLine("cpu  1 2") == null,
                "cpu: short line rejected");
        check(DeviceMeters.parseCpuLine("cpu  100 x 50 800 10") == null,
                "cpu: non-number rejected");
        check(DeviceMeters.parseCpuLine("cpu  100 -1 50 800 10") == null,
                "cpu: negative field rejected");
        check(DeviceMeters.parseCpuLine(null) == null, "cpu: null rejected");
        check(DeviceMeters.parseCpuLine("intr 1 2 3 4 5") == null,
                "cpu: non-cpu line rejected");
        DeviceMeters.CpuSample before = new DeviceMeters.CpuSample(1000, 800);
        DeviceMeters.CpuSample after = new DeviceMeters.CpuSample(1100, 820);
        check(Math.abs(DeviceMeters.cpuUsage(before, after) - 0.8) < 0.0001,
                "cpu: usage from deltas");
        check(Double.isNaN(DeviceMeters.cpuUsage(before, before)),
                "cpu: identical samples are NaN");
        check(Double.isNaN(DeviceMeters.cpuUsage(after, before)),
                "cpu: backwards samples are NaN");
        check(Double.isNaN(DeviceMeters.cpuUsage(null, after)),
                "cpu: null sample is NaN");
        check(Double.isNaN(DeviceMeters.cpuUsage(before,
                new DeviceMeters.CpuSample(1100, 950))),
                "cpu: idle delta above total delta is NaN");
        eq("64% · approx", DeviceMeters.cpuLabel(0.635), "cpu: label says approx");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.cpuLabel(Double.NaN),
                "cpu: NaN label is unavailable");
        eq("82%", DeviceMeters.batteryLabel(82, false), "battery: plain percent");
        eq("82% · Charging", DeviceMeters.batteryLabel(82, true),
                "battery: charging named");
        eq("100% · Full", DeviceMeters.batteryLabel(100, true), "battery: full named");
        eq("100%", DeviceMeters.batteryLabel(100, false), "battery: full, unplugged");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.batteryLabel(-1, false),
                "battery: negative is unavailable");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.batteryLabel(101, false),
                "battery: above 100 is unavailable");
        check(DeviceMeters.batteryWarn(19, false), "battery: low + unplugged warns");
        check(!DeviceMeters.batteryWarn(19, true), "battery: low + charging is fine");
        check(!DeviceMeters.batteryWarn(20, false), "battery: 20% does not warn");
        check(!DeviceMeters.batteryWarn(-1, false), "battery: unknown never warns");
        eq("RX 1.0 GB · TX 340.0 MB",
                DeviceMeters.trafficLabel(1024L * 1024L * 1024L, 340L * 1024L * 1024L),
                "traffic: totals labeled");
        eq(DeviceMeters.UNAVAILABLE, DeviceMeters.trafficLabel(-1, 5),
                "traffic: unknown RX is unavailable");
        check(Math.abs(DeviceMeters.mobileShare(100, 100, 400, 400) - 0.25) < 0.0001,
                "traffic: mobile share");
        check(Double.isNaN(DeviceMeters.mobileShare(-1, 0, 400, 400)),
                "traffic: unknown mobile is NaN");
        check(Double.isNaN(DeviceMeters.mobileShare(0, 0, 0, 0)),
                "traffic: zero total is NaN");
        check(Double.isNaN(DeviceMeters.mobileShare(500, 0, 400, 0)),
                "traffic: mobile above total is NaN");
        DeviceMeters.Reading unavailable = DeviceMeters.Reading.unavailable();
        check(!unavailable.available, "reading: unavailable is not available");
        check(Double.isNaN(unavailable.fraction), "reading: unavailable has no fill");
        eq(DeviceMeters.UNAVAILABLE, unavailable.numbers,
                "reading: unavailable text");
        check(!unavailable.warn, "reading: unavailable never warns");
    }

    private static void hubRows() {
        eq(18, DeepHub.GROUP_COUNT, "hub: 18 groups");
        for (int i = 0; i < DeepHub.GROUP_COUNT; i++) {
            int glyph = DeepHub.glyphForGroup(i);
            check(glyph >= 0 && glyph <= PageGlyphs.CAMERA,
                    "hub: group " + i + " maps to a valid glyph");
        }
        eq(PageGlyphs.SHIELD, DeepHub.glyphForGroup(0),
                "hub: Protected packages is the shield");
        eq(PageGlyphs.APPS, DeepHub.glyphForGroup(1), "hub: Target app is apps");
        eq(PageGlyphs.ACTIVITY, DeepHub.glyphForGroup(2),
                "hub: Running processes is activity");
        eq(PageGlyphs.CONNECTIONS, DeepHub.glyphForGroup(7),
                "hub: Private DNS is connections");
        eq(PageGlyphs.CAMERA, DeepHub.glyphForGroup(12), "hub: Capture is camera");
        eq(PageGlyphs.INFO, DeepHub.glyphForGroup(-1),
                "hub: negative index falls back to info");
        eq(PageGlyphs.INFO, DeepHub.glyphForGroup(18),
                "hub: past-the-end index falls back to info");
        eq(PageGlyphs.INFO, DeepHub.glyphForGroup(99),
                "hub: far index falls back to info");
        eq(DeepHub.glyphForGroup(5), DeepHub.glyphForGroup(5),
                "hub: mapping is deterministic");
    }
}
