package app.control.launcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Off-device verification for v1.37: the change journal
 * (ChangeJournal), the config-flag parsing/grouping
 * (ConfigFlags), and the AppOp before-value read
 * (BridgeOps.parseAppOpMode), exercising the exact shipping
 * pure-Java code, including the journal file codec against
 * temp files.
 *
 * Legs:
 *   1. PENDING LIFECYCLE: a recorded entry is pending and
 *      invisible until markApplied; drop removes it; a
 *      refused write leaves no entry.
 *   2. CODEC ROUND TRIP: save/load preserves every field; a
 *      pending entry left by a dead process is dropped on
 *      load; a missing or corrupt file starts empty.
 *   3. ORDERING: newestFirst is reverse chronological;
 *      restorePlan is applied + restorable + unrestored,
 *      newest first; restored entries never reappear.
 *   4. RECORDED DEFAULTS: the earliest unrestored entry's
 *      previous value; null with no entry, an unknown
 *      earliest previous, or an unrestorable earliest
 *      entry; null again after markRestoredFor; a new
 *      change after a reset re-establishes the default.
 *   5. FLAG PLAN: flagRestorePlan is the restore plan
 *      restricted to flag/setting ops; isFlagOp admits
 *      exactly the three flag ops.
 *   6. PRUNING: the 500-entry cap drops the oldest restored
 *      entries first, then the oldest entries overall.
 *   7. DISPLAY: op labels, value words (enabled/disabled,
 *      night mode, setting presets, DNS mode|host, cleared
 *      flags, unknown), and the history row text.
 *   8. FLAG PARSING: both list shapes (namespace/key=value
 *      and scoped key=value), bare-namespace output,
 *      unparseable lines skipped, never guessed.
 *   9. FLAG GROUPING: categories alphabetical, flags
 *      alphabetical within, totals, empty-value display.
 *  10. APPOP MODES: OP lines and Uid-mode lines parse; an
 *      unknown op, an unknown mode word, and empty output
 *      are all null, never guessed.
 *  11. FLAG TOGGLES + PICKER (v1.38): only exactly
 *      "true"/"false" values are boolean and flippable;
 *      sortedNamespaces unions flag namespaces and bare
 *      names, sorted and de-duplicated.
 */
public class ChangeJournalHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        pendingLifecycle();
        codecRoundTrip();
        ordering();
        recordedDefaults();
        flagPlan();
        pruning();
        display();
        flagParsing();
        flagGrouping();
        flagToggles();
        appOpModes();
        System.out.println("ChangeJournalHarness: " + checks + " checks, "
                + failures + " failures");
        for (String f : failed) System.out.println("  FAIL: " + f);
        if (failures > 0) System.exit(1);
    }

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

    private static File tempJournal() throws Exception {
        File f = Files.createTempFile("journal-harness-", ".json").toFile();
        f.delete();
        return f;
    }

    private static ChangeJournal.Entry applied(ChangeJournal journal,
            String op, String target, String previous, String current,
            String pkg, String namespace, String key, String detail) {
        ChangeJournal.Entry e = journal.record(op, target, previous, current,
                previous != null, previous == null ? "unknown" : null,
                pkg, namespace, key, detail);
        journal.markApplied(e.id);
        return journal.byId(e.id);
    }

    // ---- legs ----

    private static void pendingLifecycle() throws Exception {
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        eq(0, journal.newestFirst().size(), "fresh journal is empty");
        ChangeJournal.Entry e = journal.record("SET_APP_ENABLED", "Example (com.example.app)",
                "true", "false", true, null, "com.example.app", null, null, null);
        eq(0, journal.newestFirst().size(), "pending entry is invisible");
        check(!journal.byId(e.id).applied, "pending entry is not applied");
        journal.markApplied(e.id);
        eq(1, journal.newestFirst().size(), "applied entry is visible");
        check(journal.byId(e.id).applied, "markApplied sticks");
        ChangeJournal.Entry refused = journal.record("SET_APP_ENABLED", "Other",
                "true", "false", true, null, "com.other", null, null, null);
        journal.drop(refused.id);
        eq(null, journal.byId(refused.id), "dropped entry is gone");
        eq(1, journal.newestFirst().size(), "refused write leaves no entry");
        eq(1, journal.appliedCount(), "applied count counts applied only");
        f.delete();
    }

    private static void codecRoundTrip() throws Exception {
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        ChangeJournal.Entry e = journal.record("DEVICE_CONFIG_PUT", "ns / key",
                "old", "new", true, null, null, "ns", "key", null);
        journal.markApplied(e.id);
        ChangeJournal.Entry trim = journal.record("TRIM_CACHES", "Device caches",
                null, "trimmed", false, "cannot be undone", null, null, null, null);
        journal.markApplied(trim.id);
        // A pending entry left behind by a dead process.
        journal.record("NIGHT_SET", "Night mode", "no", "yes",
                true, null, null, null, null, null);
        ChangeJournal reloaded = new ChangeJournal(f);
        eq(2, reloaded.newestFirst().size(), "reload drops the pending entry");
        ChangeJournal.Entry back = reloaded.byId(e.id);
        eq("DEVICE_CONFIG_PUT", back.op, "codec preserves op");
        eq("ns / key", back.target, "codec preserves target");
        eq("old", back.previous, "codec preserves previous");
        eq("new", back.current, "codec preserves current");
        eq("ns", back.namespace, "codec preserves namespace");
        eq("key", back.key, "codec preserves key");
        check(back.applied && !back.restored, "codec preserves applied/restored");
        eq(e.timeMs, back.timeMs, "codec preserves timestamp");
        // A fresh record after reload keeps ids unique.
        ChangeJournal.Entry next = reloaded.record("DOZE", "Doze state",
                "ACTIVE", "force-idle", true, null, null, null, null, null);
        check(next.id != e.id, "ids stay unique across reload");
        // Corrupt file starts empty, missing file starts empty.
        Files.write(f.toPath(), "not json{".getBytes(StandardCharsets.UTF_8));
        eq(0, new ChangeJournal(f).newestFirst().size(), "corrupt file starts empty");
        f.delete();
        eq(0, new ChangeJournal(f).newestFirst().size(), "missing file starts empty");
        f.delete();
    }

    private static void ordering() throws Exception {
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        ChangeJournal.Entry first = applied(journal, "SETTING_PUT", "Font scale",
                "1.0", "1.15", null, "system", "font_scale", null);
        ChangeJournal.Entry second = applied(journal, "NIGHT_SET", "Night mode",
                "no", "yes", null, null, null, null);
        ChangeJournal.Entry third = applied(journal, "CLEAR_APP_DATA", "Example",
                null, "cleared", "com.example.app", null, null, null);
        List<ChangeJournal.Entry> newest = journal.newestFirst();
        eq(3, newest.size(), "newestFirst holds every applied entry");
        eq(third.id, newest.get(0).id, "newestFirst is newest first");
        eq(second.id, newest.get(1).id, "newestFirst middle entry");
        eq(first.id, newest.get(2).id, "newestFirst ends oldest");
        List<ChangeJournal.Entry> plan = journal.restorePlan();
        eq(2, plan.size(), "restorePlan excludes unrestorable entries");
        eq(second.id, plan.get(0).id, "restorePlan is newest first");
        journal.markRestored(second.id);
        plan = journal.restorePlan();
        eq(1, plan.size(), "restored entries leave the plan");
        eq(first.id, plan.get(0).id, "plan keeps the unrestored entry");
        check(journal.byId(second.id).restored, "markRestored sticks");
        f.delete();
    }

    private static void recordedDefaults() throws Exception {
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        eq(null, journal.recordedDefault("ns", "key"), "no entry means no default");
        applied(journal, "DEVICE_CONFIG_PUT", "ns / key", "A", "B",
                null, "ns", "key", null);
        applied(journal, "DEVICE_CONFIG_PUT", "ns / key", "B", "C",
                null, "ns", "key", null);
        eq("A", journal.recordedDefault("ns", "key"),
                "default is the earliest previous value");
        eq(null, journal.recordedDefault("ns", "other"), "other keys are separate");
        eq(null, journal.recordedDefault("other", "key"), "other namespaces are separate");
        journal.markRestoredFor("ns", "key");
        eq(null, journal.recordedDefault("ns", "key"),
                "no default once every change is restored");
        eq(0, journal.restorePlan().size(), "restored-for entries leave the plan");
        // A new change after the reset re-establishes the default
        // from its own fresh before-value.
        applied(journal, "DEVICE_CONFIG_PUT", "ns / key", "A", "D",
                null, "ns", "key", null);
        eq("A", journal.recordedDefault("ns", "key"),
                "a later change re-establishes the default");
        // Earliest unrestored entry unrestorable: no default.
        File g = tempJournal();
        ChangeJournal unset = new ChangeJournal(g);
        ChangeJournal.Entry u = unset.record("DEVICE_CONFIG_PUT", "ns / flag",
                null, "x", false, "was unset", null, "ns", "flag", null);
        unset.markApplied(u.id);
        applied(unset, "DEVICE_CONFIG_PUT", "ns / flag", "x", "y",
                null, "ns", "flag", null);
        eq(null, unset.recordedDefault("ns", "flag"),
                "an unset-before earliest entry means no default");
        // A non-flag op never counts as a flag default.
        applied(unset, "NIGHT_SET", "Night mode", "no", "yes",
                null, "ns", "flag", null);
        eq(null, unset.recordedDefault("ns", "flag"),
                "non-flag ops do not supply flag defaults");
        f.delete();
        g.delete();
    }

    private static void flagPlan() throws Exception {
        check(ChangeJournal.isFlagOp("DEVICE_CONFIG_PUT"), "device_config is a flag op");
        check(ChangeJournal.isFlagOp("SETTING_PUT"), "setting put is a flag op");
        check(ChangeJournal.isFlagOp("SET_LAUNCHER_SETTING"), "launcher setting is a flag op");
        check(!ChangeJournal.isFlagOp("NIGHT_SET"), "night mode is not a flag op");
        check(!ChangeJournal.isFlagOp("SET_APP_ENABLED"), "enable is not a flag op");
        check(!ChangeJournal.isFlagOp(null), "null is not a flag op");
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        applied(journal, "NIGHT_SET", "Night mode", "no", "yes", null, null, null, null);
        ChangeJournal.Entry flag = applied(journal, "DEVICE_CONFIG_PUT", "ns / key",
                "A", "B", null, "ns", "key", null);
        ChangeJournal.Entry setting = applied(journal, "SETTING_PUT", "Font scale",
                "1.0", "1.15", null, "system", "font_scale", null);
        List<ChangeJournal.Entry> plan = journal.flagRestorePlan();
        eq(2, plan.size(), "flag plan holds flag ops only");
        eq(setting.id, plan.get(0).id, "flag plan is newest first");
        eq(flag.id, plan.get(1).id, "flag plan keeps the older flag");
        f.delete();
    }

    private static void pruning() throws Exception {
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ChangeJournal.MAX_ENTRIES; i++) {
            ChangeJournal.Entry e = applied(journal, "NIGHT_SET", "Night mode",
                    "no", "yes", null, null, null, null);
            ids.add(e.id);
        }
        eq(ChangeJournal.MAX_ENTRIES, journal.newestFirst().size(), "cap holds at max");
        // Mark the oldest ten restored, then add ten more: the
        // restored entries go first, the unrestored oldest stay.
        for (int i = 0; i < 10; i++) journal.markRestored(ids.get(i));
        for (int i = 0; i < 10; i++) {
            applied(journal, "NIGHT_SET", "Night mode", "no", "yes", null, null, null, null);
        }
        eq(ChangeJournal.MAX_ENTRIES, journal.newestFirst().size(), "cap holds after overflow");
        for (int i = 0; i < 10; i++) {
            eq(null, journal.byId(ids.get(i)), "oldest restored entry pruned: " + i);
        }
        check(journal.byId(ids.get(10)) != null, "oldest unrestored entry survives");
        // With nothing restored, the oldest entries go.
        File g = tempJournal();
        ChangeJournal plain = new ChangeJournal(g);
        List<Long> plainIds = new ArrayList<>();
        for (int i = 0; i < ChangeJournal.MAX_ENTRIES + 5; i++) {
            ChangeJournal.Entry e = applied(plain, "DOZE", "Doze state",
                    "ACTIVE", "force-idle", null, null, null, null);
            plainIds.add(e.id);
        }
        eq(ChangeJournal.MAX_ENTRIES, plain.newestFirst().size(), "cap holds, unrestored only");
        for (int i = 0; i < 5; i++) {
            eq(null, plain.byId(plainIds.get(i)), "oldest entry pruned: " + i);
        }
        check(plain.byId(plainIds.get(5)) != null, "sixth entry survives");
        f.delete();
        g.delete();
    }

    private static void display() throws Exception {
        eq("App enabled / disabled", ChangeJournal.opLabel("SET_APP_ENABLED"),
                "enable label");
        eq("Flag changed", ChangeJournal.opLabel("DEVICE_CONFIG_PUT"), "flag label");
        eq("Private DNS", ChangeJournal.opLabel("SET_PRIVATE_DNS"), "dns label");
        eq("APK installed", ChangeJournal.opLabel("INSTALL_APK"), "install label");
        eq("SOMETHING_ELSE", ChangeJournal.opLabel("SOMETHING_ELSE"),
                "unknown ops read as-is");
        eq("Change", ChangeJournal.opLabel(null), "null op reads Change");
        File f = tempJournal();
        ChangeJournal journal = new ChangeJournal(f);
        ChangeJournal.Entry enabled = applied(journal, "SET_APP_ENABLED", "Example",
                "true", "false", "com.example.app", null, null, null);
        eq("enabled", ChangeJournal.formatValue(enabled, "true"), "enabled reads enabled");
        eq("disabled", ChangeJournal.formatValue(enabled, "false"), "disabled reads disabled");
        eq("unknown", ChangeJournal.formatValue(enabled, null), "null reads unknown");
        ChangeJournal.Entry night = applied(journal, "NIGHT_SET", "Night mode",
                "no", "yes", null, null, null, null);
        eq("Day", ChangeJournal.formatValue(night, "no"), "night no reads Day");
        eq("Night", ChangeJournal.formatValue(night, "yes"), "night yes reads Night");
        ChangeJournal.Entry setting = applied(journal, "SETTING_PUT", "Font scale",
                "1.0", "1.15", null, "system", "font_scale", null);
        eq("Default", ChangeJournal.formatValue(setting, "1.0"),
                "setting values read as preset labels");
        ChangeJournal.Entry dns = applied(journal, "SET_PRIVATE_DNS", "Private DNS",
                "opportunistic|", "hostname|dns.adguard-dns.com", null, null, null, null);
        eq("Automatic", ChangeJournal.formatValue(dns, "opportunistic|"),
                "dns opportunistic reads Automatic");
        eq("hostname (dns.adguard-dns.com)",
                ChangeJournal.formatValue(dns, "hostname|dns.adguard-dns.com"),
                "dns hostname reads with the host");
        eq("Off", ChangeJournal.formatValue(dns, "off|"), "dns off reads Off");
        ChangeJournal.Entry flag = applied(journal, "DEVICE_CONFIG_PUT", "ns / key",
                "v", "", null, "ns", "key", null);
        eq("(cleared)", ChangeJournal.formatValue(flag, ""), "empty flag reads cleared");
        String described = ChangeJournal.describe(enabled);
        check(described.contains("App enabled / disabled"), "describe names the op");
        check(described.contains("Was: enabled → Now: disabled"), "describe shows was → now");
        check(!described.contains("Restored"), "unrestored describe has no Restored mark");
        journal.markRestored(enabled.id);
        check(ChangeJournal.describe(journal.byId(enabled.id)).contains("Restored"),
                "describe marks restored entries");
        ChangeJournal.Entry unrestorable = applied(journal, "CLEAR_APP_DATA", "Example",
                null, "cleared", "com.example.app", null, null, null);
        check(ChangeJournal.describe(unrestorable).contains("Cannot be undone"),
                "describe marks unrestorable entries");
        check(!ChangeJournal.formatTime(System.currentTimeMillis()).isEmpty(),
                "formatTime produces text");
        f.delete();
    }

    private static void flagParsing() {
        ConfigFlags.ParseResult both = ConfigFlags.parse(
                "activity_manager/max_phantom_processes=32\n"
                + "activity_manager/empty_flag=\n"
                + "not a flag line\n"
                + "window_manager/window_animation_scale=1.0\n", null);
        eq(3, both.flags.size(), "flag lines parse, junk skipped");
        eq("activity_manager", both.flags.get(0).namespace, "flag namespace parsed");
        eq("max_phantom_processes", both.flags.get(0).key, "flag key parsed");
        eq("32", both.flags.get(0).value, "flag value parsed");
        eq("", both.flags.get(1).value, "empty flag value kept");
        check(both.sawFlags(), "sawFlags true with flags");
        check(!both.sawNamespacesOnly(), "namespaces-only false with flags");
        ConfigFlags.ParseResult scoped = ConfigFlags.parse(
                "max_phantom_processes=32\nanother_flag=hello\n", "activity_manager");
        eq(2, scoped.flags.size(), "scoped key=value lines take the namespace");
        eq("activity_manager", scoped.flags.get(1).namespace, "scoped namespace applied");
        ConfigFlags.ParseResult bare = ConfigFlags.parse(
                "key_without_value\n", null);
        eq(0, bare.flags.size(), "bare key=value lines without a namespace are skipped");
        ConfigFlags.ParseResult names = ConfigFlags.parse(
                "activity_manager\nwindow_manager\nactivity_manager\n", null);
        eq(0, names.flags.size(), "namespace-only output has no flags");
        eq(2, names.namespaces.size(), "namespace names de-duplicated");
        eq("activity_manager", names.namespaces.get(0), "first namespace kept");
        check(names.sawNamespacesOnly(), "sawNamespacesOnly true for bare names");
        ConfigFlags.ParseResult empty = ConfigFlags.parse("", null);
        check(!empty.sawFlags() && !empty.sawNamespacesOnly(), "empty output is neither");
        ConfigFlags.ParseResult nul = ConfigFlags.parse(null, null);
        check(!nul.sawFlags() && !nul.sawNamespacesOnly(), "null output is neither");
        ConfigFlags.ParseResult tricky = ConfigFlags.parse(
                "ns/key=with=equals\nns/key2 = spaced \n=novalue\n/key=nons\n", null);
        eq(2, tricky.flags.size(), "well-formed lines parse, malformed skipped");
        eq("with=equals", tricky.flags.get(0).value, "values may contain equals");
        eq(" spaced", tricky.flags.get(1).value,
                "values keep their leading space (the line trim takes the trailing one)");
    }

    private static void flagGrouping() {
        List<ConfigFlags.Flag> flags = ConfigFlags.parse(
                "zeta/b=2\n"
                + "alpha/z=1\n"
                + "alpha/a=3\n"
                + "zeta/a=4\n", null).flags;
        List<ConfigFlags.Group> groups = ConfigFlags.group(flags);
        eq(2, groups.size(), "two categories group");
        eq("alpha", groups.get(0).namespace, "categories alphabetical");
        eq("zeta", groups.get(1).namespace, "second category");
        eq("a", groups.get(0).flags.get(0).key, "flags alphabetical within");
        eq("z", groups.get(0).flags.get(1).key, "second flag");
        eq(4, ConfigFlags.totalFlags(groups), "total counts all flags");
        eq("(empty)", ConfigFlags.displayValue(""), "empty value displays (empty)");
        eq("(not set)", ConfigFlags.displayValue(null), "null value displays (not set)");
        eq("hello", ConfigFlags.displayValue("hello"), "plain values display raw");
        eq(0, ConfigFlags.group(new ArrayList<ConfigFlags.Flag>()).size(),
                "no flags means no groups");
    }

    private static void flagToggles() {
        check(ConfigFlags.isBooleanValue("true"), "true is boolean");
        check(ConfigFlags.isBooleanValue("false"), "false is boolean");
        check(!ConfigFlags.isBooleanValue("True"), "True is not boolean (exact match)");
        check(!ConfigFlags.isBooleanValue("1"), "1 is not boolean");
        check(!ConfigFlags.isBooleanValue(""), "empty is not boolean");
        check(!ConfigFlags.isBooleanValue(null), "null is not boolean");
        check(!ConfigFlags.isBooleanValue(" true"), "padded true is not boolean");
        eq("false", ConfigFlags.toggleValue("true"), "toggle true flips to false");
        eq("true", ConfigFlags.toggleValue("false"), "toggle false flips to true");
        eq(null, ConfigFlags.toggleValue("1"), "toggle of a non-boolean is null");
        eq(null, ConfigFlags.toggleValue(null), "toggle of null is null");
        eq(null, ConfigFlags.toggleValue(""), "toggle of empty is null");
        ConfigFlags.ParseResult mixed = ConfigFlags.parse(
                "zeta/b=2\n"
                + "alpha/z=1\n"
                + "bare_namespace\n"
                + "alpha/a=3\n", null);
        List<String> names = ConfigFlags.sortedNamespaces(mixed);
        eq(3, names.size(), "picker unions flag namespaces and bare names");
        eq("alpha", names.get(0), "picker namespaces alphabetical (first)");
        eq("bare_namespace", names.get(1), "picker namespaces alphabetical (second)");
        eq("zeta", names.get(2), "picker namespaces alphabetical (third)");
        ConfigFlags.ParseResult dupes = ConfigFlags.parse(
                "beta/x=1\nbeta\nbeta/y=2\nbeta\n", null);
        eq(1, ConfigFlags.sortedNamespaces(dupes).size(),
                "picker de-duplicates a namespace seen both ways");
        eq(0, ConfigFlags.sortedNamespaces(ConfigFlags.parse("", null)).size(),
                "empty output yields no picker namespaces");
        eq(0, ConfigFlags.sortedNamespaces(null).size(),
                "null result yields no picker namespaces");
    }

    private static void appOpModes() {
        String out = "CAMERA: allow; time=+1h2m3s ago; duration=+5s\n"
                + "RECORD_AUDIO: ignore; time=+2h ago\n"
                + "Uid mode: FINE_LOCATION: deny\n";
        eq("allow", BridgeOps.parseAppOpMode(out, "CAMERA"), "plain op line parses");
        eq("ignore", BridgeOps.parseAppOpMode(out, "RECORD_AUDIO"), "second op parses");
        eq("deny", BridgeOps.parseAppOpMode(out, "FINE_LOCATION"), "uid-mode line parses");
        eq(null, BridgeOps.parseAppOpMode(out, "WAKE_LOCK"), "missing op is null");
        eq(null, BridgeOps.parseAppOpMode(out, "CAM"), "prefix matches do not count");
        eq(null, BridgeOps.parseAppOpMode("CAMERA: frobnicate\n", "CAMERA"),
                "unknown mode word is null");
        eq(null, BridgeOps.parseAppOpMode("", "CAMERA"), "empty output is null");
        eq(null, BridgeOps.parseAppOpMode(null, "CAMERA"), "null output is null");
        eq(null, BridgeOps.parseAppOpMode(out, null), "null op is null");
        eq("foreground", BridgeOps.parseAppOpMode("  BODY_SENSORS: foreground\n",
                "BODY_SENSORS"), "leading whitespace tolerated");
        eq("default", BridgeOps.parseAppOpMode("READ_SMS: default; time=+1s\n"
                + "READ_SMS: allow\n", "READ_SMS"), "first matching line wins");
    }
}
