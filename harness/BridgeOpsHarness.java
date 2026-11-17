package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Off-device verification for v1.35: the bridge expansion's
 * pure-Java shaping (BridgeOps), exercising the exact shipping
 * code — every new command's argv, every verifier, every
 * allowlist, and every parser.
 *
 * Legs:
 *   1. PACKAGE COMMANDS: uninstall / install-existing /
 *      install / suspend / trim-caches / list argv are exact
 *      fixed arrays; no bare "disable" element anywhere.
 *   2. PACKAGE VERIFY: pm's Success/state lines confirm;
 *      Failure lines, error text, empty output, and non-zero
 *      exits all fail carrying pm's own words; suspend passes
 *      refusals through verbatim and says when pm printed
 *      nothing.
 *   3. PACKAGE LISTS: "package:" lines parse; the removed set
 *      is the -u list minus the plain list, sorted.
 *   4. WM COMMANDS + PARSE: size/density/overscan get/set/
 *      reset argv; physical + override lines parse; an
 *      unparseable readout is null, never guessed.
 *   5. WM VALIDATE + VERIFY: density/size/overscan bounds;
 *      a set verifies when the re-read override matches, or
 *      when no override exists and the physical value
 *      matches (setting the physical value clears the
 *      override); anything else is "not confirmed".
 *   6. SETTINGS ALLOWLIST: the eight writable keys resolve;
 *      any other namespace/key pair resolves to nothing;
 *      value validation enforces kinds and ranges; every
 *      preset validates; read-backs match exactly (floats
 *      with tolerance) and "null" never matches.
 *   7. NIGHT MODE: get/set argv, mode membership, and the
 *      yes/no/auto parse.
 *   8. INPUT: the key-code menu is fixed and complete; tap /
 *      swipe / text argv; coordinate and duration bounds;
 *      text escaping (%, spaces, dropped controls) and the
 *      bridge-side shape check.
 *   9. CAPTURE: screencap / screenrecord / bugreport argv;
 *      recording lengths; staged-APK path rule (exact
 *      prefix, safe token, no traversal).
 *  10. POWER: doze verbs, device-idle state parse, battery
 *      parse + display + level verification, standby bucket
 *      names/numbers, job-id validation, job-run verify.
 *  11. SYSTEM CONFIG: device_config argv + namespace/key/
 *      value shapes + read-back matching; wifi list/forget
 *      argv + lenient network parse + forget verify; user
 *      list/create/remove argv + user parse + create-id
 *      parse + removal refusals (0 and current user).
 *  12. LAUNCH + DUMPSYS: relative class resolution, class
 *      name shape, am start argv + verify; the dumpsys
 *      service menu (a bare dumpsys is not an option).
 *
 * Run (from ~/workspace/control-launcher):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar -d harness/classes \
 *     harness/BridgeOpsHarness.java
 *   java -cp harness/classes app.control.launcher.BridgeOpsHarness
 *
 * What this harness cannot prove: the tools' exact output
 * wording on Android 16 (parsers fail honestly on anything
 * else), whether a given build allows suspend or the `cmd
 * wifi` network commands, or any screen in hand.
 */
public class BridgeOpsHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        packageCommands();
        packageVerify();
        packageLists();
        wmCommandsAndParse();
        wmValidateAndVerify();
        settingsAllowlist();
        nightMode();
        input();
        capture();
        power();
        systemConfig();
        launchAndDumpsys();
        sharedShapes();
        System.out.println("BridgeOpsHarness: " + checks + " checks, "
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

    private static void argvEq(String[] got, String[] want, String name) {
        check(Arrays.equals(got, want),
                name + " (got " + Arrays.toString(got)
                        + ", want " + Arrays.toString(want) + ")");
    }

    // ---- legs ----

    private static void packageCommands() {
        argvEq(BridgeOps.uninstallUserCommand("com.example.app"),
                new String[]{"/system/bin/pm", "uninstall", "--user", "0", "com.example.app"},
                "uninstall argv");
        argvEq(BridgeOps.installExistingCommand("com.example.app"),
                new String[]{"/system/bin/pm", "install-existing", "--user", "0", "com.example.app"},
                "install-existing argv");
        argvEq(BridgeOps.installApkCommand("/sdcard/Download/control-install-abc.apk"),
                new String[]{"/system/bin/pm", "install", "-r",
                        "/sdcard/Download/control-install-abc.apk"},
                "install argv");
        argvEq(BridgeOps.suspendCommand("com.example.app", true),
                new String[]{"/system/bin/pm", "suspend", "com.example.app"},
                "suspend argv");
        argvEq(BridgeOps.suspendCommand("com.example.app", false),
                new String[]{"/system/bin/pm", "unsuspend", "com.example.app"},
                "unsuspend argv");
        argvEq(BridgeOps.trimCachesCommand(1073741824L),
                new String[]{"/system/bin/pm", "trim-caches", "1073741824"},
                "trim-caches argv");
        eq(1073741824L, BridgeOps.TRIM_CACHES_TARGET_BYTES, "trim target is 1 GiB");
        argvEq(BridgeOps.listPackagesCommand(false),
                new String[]{"/system/bin/pm", "list", "packages"},
                "list packages argv");
        argvEq(BridgeOps.listPackagesCommand(true),
                new String[]{"/system/bin/pm", "list", "packages", "-u"},
                "list packages -u argv");
        // No bare "disable" element in any new pm argv (the
        // v1.33 fix stands: disable-user is the only disable).
        String[][] all = {
                BridgeOps.uninstallUserCommand("com.example.app"),
                BridgeOps.installExistingCommand("com.example.app"),
                BridgeOps.installApkCommand("/sdcard/Download/control-install-abc.apk"),
                BridgeOps.suspendCommand("com.example.app", true),
                BridgeOps.trimCachesCommand(1L),
        };
        for (String[] argv : all) {
            for (String element : argv) {
                check(!"disable".equals(element),
                        "no bare disable element in new pm argv");
            }
        }
        check(BridgeOps.validPackage("com.example.app"), "valid package shape");
        check(!BridgeOps.validPackage("nodots"), "single segment is not a package");
        check(!BridgeOps.validPackage("com.example.app; rm -rf /"), "shell metachars rejected");
        check(!BridgeOps.validPackage(null), "null package rejected");
    }

    private static void packageVerify() {
        BridgeOps.Outcome uOk = BridgeOps.verifyUninstall("Success\n", 0);
        check(uOk.ok, "uninstall Success + exit 0 is success");
        check(!BridgeOps.verifyUninstall("Failure [DELETE_FAILED_INTERNAL_ERROR]\n", 1).ok,
                "uninstall Failure is a failure");
        BridgeOps.Outcome uFail = BridgeOps.verifyUninstall(
                "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", 1);
        check(uFail.message.contains("DELETE_FAILED_INTERNAL_ERROR"),
                "uninstall failure carries pm's words");
        check(!BridgeOps.verifyUninstall("Success\n", 1).ok,
                "uninstall Success with non-zero exit is a failure");
        check(!BridgeOps.verifyUninstall("", 0).ok, "uninstall empty output is a failure");

        BridgeOps.Outcome rOk = BridgeOps.verifyInstallExisting(
                "Package com.example.app installed for user: 0\n", 0);
        check(rOk.ok, "install-existing state line is success");
        check(rOk.message.contains("installed for user"), "restore success carries pm's line");
        check(!BridgeOps.verifyInstallExisting("Failure [INSTALL_FAILED_INVALID_APK]\n", 1).ok,
                "install-existing Failure is a failure");

        check(BridgeOps.verifyInstallApk("Success\n", 0).ok, "install Success is success");
        check(!BridgeOps.verifyInstallApk("Failure [INSTALL_FAILED_ALREADY_EXISTS]\n", 1).ok,
                "install Failure is a failure");
        check(!BridgeOps.verifyInstallApk("Success\n", 1).ok,
                "install Success with non-zero exit is a failure");

        // Suspend: wording varies, so exit + no error markers.
        BridgeOps.Outcome sOk = BridgeOps.verifySuspend("Package com.example.app suspended\n", 0, true);
        check(sOk.ok, "suspend with clean output is success");
        BridgeOps.Outcome sEmpty = BridgeOps.verifySuspend("", 0, true);
        check(sEmpty.ok, "suspend with empty output and exit 0 is success");
        check(sEmpty.message.contains("printed nothing"),
                "empty suspend output says so honestly");
        BridgeOps.Outcome sRefused = BridgeOps.verifySuspend(
                "Error: java.lang.SecurityException: Permission denial\n", 1, true);
        check(!sRefused.ok, "suspend SecurityException is a failure");
        check(sRefused.message.contains("SecurityException"),
                "suspend refusal passes pm's words through verbatim");
        check(!BridgeOps.verifySuspend("Package suspended\n", 1, true).ok,
                "suspend with non-zero exit is a failure even with clean words");

        check(BridgeOps.verifyTrimCaches("", 0).ok, "trim-caches empty + exit 0 is success");
        check(!BridgeOps.verifyTrimCaches("Error: timeout\n", 1).ok,
                "trim-caches error is a failure");
        check(BridgeOps.hasErrorMarker("Error: something"), "error marker: Error");
        check(BridgeOps.hasErrorMarker("java.lang.Exception"), "error marker: Exception");
        check(BridgeOps.hasErrorMarker("Failure [X]"), "error marker: Failure");
        check(BridgeOps.hasErrorMarker("Permission denial: foo"), "error marker: denial");
        check(!BridgeOps.hasErrorMarker("Success"), "Success has no error marker");
        check(!BridgeOps.hasErrorMarker(""), "empty output has no error marker");
        check(!BridgeOps.hasErrorMarker(null), "null output has no error marker");
    }

    private static void packageLists() {
        String installed = "package:com.example.app\npackage:com.example.game\n";
        String all = "package:com.example.app\npackage:com.example.game\n"
                + "package:com.example.old\npackage:com.example.zoo\n";
        eq(2, BridgeOps.parsePackageList(installed).size(), "installed list parses");
        check(BridgeOps.parsePackageList(installed).contains("com.example.app"),
                "installed list holds the app");
        eq(0, BridgeOps.parsePackageList("garbage\npackage:not a package\n").size(),
                "malformed list lines are skipped");
        eq(0, BridgeOps.parsePackageList(null).size(), "null list parses to empty");
        List<String> removed = BridgeOps.removedPackages(installed, all);
        eq(2, removed.size(), "removed set holds the two uninstalled apps");
        eq("com.example.old", removed.get(0), "removed set is sorted (first)");
        eq("com.example.zoo", removed.get(1), "removed set is sorted (second)");
        eq(0, BridgeOps.removedPackages(all, all).size(), "identical lists remove nothing");
        eq(0, BridgeOps.removedPackages(installed, installed).size(),
                "no -u extras means nothing removed");
    }

    private static void wmCommandsAndParse() {
        argvEq(BridgeOps.wmSizeGetCommand(), new String[]{"/system/bin/wm", "size"}, "wm size get");
        argvEq(BridgeOps.wmSizeSetCommand(720, 1280),
                new String[]{"/system/bin/wm", "size", "720x1280"}, "wm size set");
        argvEq(BridgeOps.wmSizeResetCommand(),
                new String[]{"/system/bin/wm", "size", "reset"}, "wm size reset");
        argvEq(BridgeOps.wmDensityGetCommand(),
                new String[]{"/system/bin/wm", "density"}, "wm density get");
        argvEq(BridgeOps.wmDensitySetCommand(360),
                new String[]{"/system/bin/wm", "density", "360"}, "wm density set");
        argvEq(BridgeOps.wmDensityResetCommand(),
                new String[]{"/system/bin/wm", "density", "reset"}, "wm density reset");
        argvEq(BridgeOps.wmOverscanGetCommand(),
                new String[]{"/system/bin/wm", "overscan"}, "wm overscan get");
        argvEq(BridgeOps.wmOverscanSetCommand(0, 10, 0, 10),
                new String[]{"/system/bin/wm", "overscan", "0,10,0,10"}, "wm overscan set");
        argvEq(BridgeOps.wmOverscanResetCommand(),
                new String[]{"/system/bin/wm", "overscan", "reset"}, "wm overscan reset");

        BridgeOps.WmValue size = BridgeOps.parseWmSize(
                "Physical size: 1080x2400\nOverride size: 720x1280\n");
        eq("1080x2400", size.physical, "size physical parses");
        eq("720x1280", size.override, "size override parses");
        BridgeOps.WmValue sizeNoOverride = BridgeOps.parseWmSize("Physical size: 1080x2400\n");
        eq("1080x2400", sizeNoOverride.physical, "size without override parses");
        eq(null, sizeNoOverride.override, "size without override has null override");
        eq(null, BridgeOps.parseWmSize("hello\n").physical, "garbage size output is null");
        eq(null, BridgeOps.parseWmSize(null).physical, "null size output is null");

        BridgeOps.WmValue density = BridgeOps.parseWmDensity(
                "Physical density: 420\nOverride density: 360\n");
        eq("420", density.physical, "density physical parses");
        eq("360", density.override, "density override parses");
        eq(null, BridgeOps.parseWmDensity("").physical, "empty density output is null");

        BridgeOps.WmValue over = BridgeOps.parseWmOverscan("Overscan: 0,10,0,10\n");
        eq("0,10,0,10", over.physical, "overscan tuple parses");
        BridgeOps.WmValue named = BridgeOps.parseWmOverscan(
                "Overscan left=0 top=10 right=0 bottom=10\n");
        eq("0,10,0,10", named.physical, "overscan named fields parse");
        BridgeOps.WmValue spaced = BridgeOps.parseWmOverscan("Overscan: 0, 10, 0, 10\n");
        eq("0,10,0,10", spaced.physical, "overscan spaced tuple parses");
        BridgeOps.WmValue commaNamed = BridgeOps.parseWmOverscan(
                "Overscan left=0, top=10, right=0, bottom=10\n");
        eq("0,10,0,10", commaNamed.physical, "overscan comma-separated named fields parse");
        eq(null, BridgeOps.parseWmOverscan("nothing useful\n").physical,
                "unparseable overscan is null, never guessed");
        eq("1080x2400", BridgeOps.wmDisplay(sizeNoOverride, "?"), "wm display without override");
        eq("720x1280 (override; physical 1080x2400)", BridgeOps.wmDisplay(size, "?"),
                "wm display names the override");
        eq("?", BridgeOps.wmDisplay(BridgeOps.parseWmSize("x"), "?"),
                "wm display falls back on unparseable output");
    }

    private static void wmValidateAndVerify() {
        check(BridgeOps.validDensity(120), "density lower bound");
        check(BridgeOps.validDensity(640), "density upper bound");
        check(BridgeOps.validDensity(420), "density mid-range");
        check(!BridgeOps.validDensity(119), "density below bound refused");
        check(!BridgeOps.validDensity(641), "density above bound refused");
        check(BridgeOps.validSize(720, 1280), "sane size");
        check(!BridgeOps.validSize(100, 1280), "too-narrow size refused");
        check(!BridgeOps.validSize(720, 5000), "too-tall size refused");
        check(BridgeOps.validOverscan(0, 0, 0, 0), "zero overscan");
        check(BridgeOps.validOverscan(10, 20, 30, 40), "sane overscan");
        check(!BridgeOps.validOverscan(-1, 0, 0, 0), "negative overscan refused");
        check(!BridgeOps.validOverscan(0, 0, 0, 501), "overscan above bound refused");

        BridgeOps.WmValue match = BridgeOps.parseWmSize(
                "Physical size: 1080x2400\nOverride size: 720x1280\n");
        check(BridgeOps.verifyWmSet(match, "720x1280", "size").ok,
                "override matching the request verifies");
        check(!BridgeOps.verifyWmSet(match, "1080x2400", "size").ok,
                "a different override does not verify");
        BridgeOps.WmValue cleared = BridgeOps.parseWmSize("Physical size: 720x1280\n");
        check(BridgeOps.verifyWmSet(cleared, "720x1280", "size").ok,
                "physical matching with no override verifies (override cleared)");
        check(!BridgeOps.verifyWmSet(BridgeOps.parseWmSize("garbage"), "720x1280", "size").ok,
                "unreadable re-read does not verify");
        check(!BridgeOps.verifyWmSet(null, "720x1280", "size").ok,
                "null re-read does not verify");
        BridgeOps.Outcome over = BridgeOps.verifyOverscanSet("Overscan: 0,10,0,10\n", 0);
        check(over.ok, "overscan clean run verifies");
        check(over.message.contains("0,10,0,10"), "overscan verify carries the re-read");
        check(!BridgeOps.verifyOverscanSet("Error: bad value\n", 1).ok,
                "overscan error does not verify");
    }

    private static void settingsAllowlist() {
        eq(8, BridgeOps.SETTINGS.length, "eight writable settings");
        check(BridgeOps.findSetting("system", "font_scale") != null,
                "font_scale resolves");
        check(BridgeOps.findSetting("global", "window_animation_scale") != null,
                "window_animation_scale resolves");
        check(BridgeOps.findSetting("system", "screen_off_timeout") != null,
                "screen_off_timeout resolves");
        check(BridgeOps.findSetting("system", "screen_brightness") != null,
                "screen_brightness resolves");
        check(BridgeOps.findSetting("global", "animator_duration_scale") != null,
                "animator_duration_scale resolves");
        check(BridgeOps.findSetting("system", "accelerometer_rotation") != null,
                "accelerometer_rotation resolves");
        // Anything else must NOT resolve — no arbitrary keys.
        check(BridgeOps.findSetting("secure", "android_id") == null,
                "android_id is not writable");
        check(BridgeOps.findSetting("global", "airplane_mode_on") == null,
                "airplane_mode_on is not writable through this op");
        check(BridgeOps.findSetting("system", "font_scale ") == null,
                "trailing space does not resolve");
        check(BridgeOps.findSetting("SYSTEM", "font_scale") == null,
                "namespace match is case-sensitive");
        check(BridgeOps.findSetting(null, null) == null, "null pair does not resolve");
        check(BridgeOps.findSetting("system", "private_dns_mode") == null,
                "private_dns_mode stays on its own op");

        BridgeOps.SettingDef font = BridgeOps.findSetting("system", "font_scale");
        argvEq(BridgeOps.settingsGetCommand(font),
                new String[]{"/system/bin/settings", "get", "system", "font_scale"},
                "settings get argv");
        argvEq(BridgeOps.settingsPutCommand(font, "1.15"),
                new String[]{"/system/bin/settings", "put", "system", "font_scale", "1.15"},
                "settings put argv");
        eq(null, BridgeOps.validateSettingValue(font, "1.0"), "font 1.0 valid");
        eq(null, BridgeOps.validateSettingValue(font, "0.85"), "font lower bound valid");
        eq(null, BridgeOps.validateSettingValue(font, "1.3"), "font upper bound valid");
        check(BridgeOps.validateSettingValue(font, "0.5") != null, "font below range refused");
        check(BridgeOps.validateSettingValue(font, "2.0") != null, "font above range refused");
        check(BridgeOps.validateSettingValue(font, "abc") != null, "font non-number refused");
        check(BridgeOps.validateSettingValue(font, "1.0; rm") != null, "font junk refused");
        check(BridgeOps.validateSettingValue(null, "1.0") != null, "null def refused");

        BridgeOps.SettingDef timeout = BridgeOps.findSetting("system", "screen_off_timeout");
        eq(null, BridgeOps.validateSettingValue(timeout, "60000"), "timeout valid");
        check(BridgeOps.validateSettingValue(timeout, "1000") != null, "timeout below range refused");
        check(BridgeOps.validateSettingValue(timeout, "60000.5") != null, "timeout fraction refused");

        BridgeOps.SettingDef mode = BridgeOps.findSetting("system", "screen_brightness_mode");
        eq(null, BridgeOps.validateSettingValue(mode, "0"), "brightness mode 0 valid");
        eq(null, BridgeOps.validateSettingValue(mode, "1"), "brightness mode 1 valid");
        check(BridgeOps.validateSettingValue(mode, "2") != null, "brightness mode 2 refused");

        BridgeOps.SettingDef bright = BridgeOps.findSetting("system", "screen_brightness");
        check(BridgeOps.validateSettingValue(bright, "256") != null, "brightness 256 refused");
        eq(null, BridgeOps.validateSettingValue(bright, "255"), "brightness 255 valid");

        // Every preset of every key validates — the UI can
        // only offer values the bridge accepts.
        for (BridgeOps.SettingDef def : BridgeOps.SETTINGS) {
            check(def.presets != null && def.presets.length > 0,
                    "setting " + def.key + " has presets");
            for (String[] preset : def.presets) {
                eq(null, BridgeOps.validateSettingValue(def, preset[1]),
                        "preset " + preset[0] + " (" + preset[1] + ") validates for " + def.key);
            }
        }

        check(BridgeOps.settingReadbackMatches(font, "1.0\n", "1.0"), "float read-back matches");
        check(BridgeOps.settingReadbackMatches(font, "1.0001\n", "1.0"),
                "float read-back matches within tolerance");
        check(!BridgeOps.settingReadbackMatches(font, "1.15\n", "1.0"),
                "float read-back mismatch fails");
        check(!BridgeOps.settingReadbackMatches(font, "null\n", "1.0"),
                "null read-back never matches");
        check(!BridgeOps.settingReadbackMatches(font, "\n", "1.0"), "empty read-back never matches");
        check(BridgeOps.settingReadbackMatches(timeout, "60000\n", "60000"),
                "int read-back matches");
        check(!BridgeOps.settingReadbackMatches(timeout, "30000\n", "60000"),
                "int read-back mismatch fails");
        eq("Default", BridgeOps.settingDisplay(font, "1.0\n"), "display names the preset");
        eq("not set", BridgeOps.settingDisplay(font, "null\n"), "display says not set for null");
        eq("1.2", BridgeOps.settingDisplay(font, "1.2\n"), "display shows a non-preset value raw");
    }

    private static void nightMode() {
        argvEq(BridgeOps.nightModeGetCommand(),
                new String[]{"/system/bin/cmd", "uimode", "night"}, "night mode get argv");
        argvEq(BridgeOps.nightModeSetCommand("auto"),
                new String[]{"/system/bin/cmd", "uimode", "night", "auto"}, "night mode set argv");
        check(BridgeOps.validNightMode("yes"), "yes is a mode");
        check(BridgeOps.validNightMode("no"), "no is a mode");
        check(BridgeOps.validNightMode("auto"), "auto is a mode");
        check(!BridgeOps.validNightMode("maybe"), "maybe is not a mode");
        check(!BridgeOps.validNightMode(""), "empty is not a mode");
        eq("yes", BridgeOps.parseNightMode("Night mode: yes\n"), "night parse yes");
        eq("no", BridgeOps.parseNightMode("Night mode: no\n"), "night parse no");
        eq("auto", BridgeOps.parseNightMode("Night mode: auto\n"), "night parse auto");
        eq(null, BridgeOps.parseNightMode("something else\n"), "night parse unknown is null");
        eq("Night", BridgeOps.nightModeDisplay("yes"), "night display yes");
        eq("Day", BridgeOps.nightModeDisplay("no"), "night display no");
        eq("Automatic", BridgeOps.nightModeDisplay("auto"), "night display auto");
        eq("unknown", BridgeOps.nightModeDisplay("bogus"), "night display unknown");
    }

    private static void input() {
        eq(15, BridgeOps.KEY_CODES.length, "key menu holds 15 entries");
        check(BridgeOps.validKeyCode(4), "Back is on the menu");
        check(BridgeOps.validKeyCode(187), "Recents is on the menu");
        check(BridgeOps.validKeyCode(85), "Play/pause is on the menu");
        check(BridgeOps.validKeyCode(66), "Enter is on the menu");
        check(!BridgeOps.validKeyCode(999), "off-menu code refused");
        check(!BridgeOps.validKeyCode(0), "code 0 refused");
        eq("Back", BridgeOps.keyLabel(4), "Back label");
        eq(null, BridgeOps.keyLabel(999), "off-menu code has no label");
        argvEq(BridgeOps.keyEventCommand(4),
                new String[]{"/system/bin/input", "keyevent", "4"}, "keyevent argv");
        argvEq(BridgeOps.tapCommand(540, 1200),
                new String[]{"/system/bin/input", "tap", "540", "1200"}, "tap argv");
        argvEq(BridgeOps.swipeCommand(100, 200, 300, 400, 300),
                new String[]{"/system/bin/input", "swipe", "100", "200", "300", "400", "300"},
                "swipe argv");
        argvEq(BridgeOps.inputTextCommand("hello%sWorld"),
                new String[]{"/system/bin/input", "text", "hello%sWorld"}, "input text argv");
        check(BridgeOps.validCoord(0, 1080), "coordinate 0 valid");
        check(BridgeOps.validCoord(1079, 1080), "coordinate max-1 valid");
        check(!BridgeOps.validCoord(1080, 1080), "coordinate max refused");
        check(!BridgeOps.validCoord(-1, 1080), "negative coordinate refused");
        check(BridgeOps.validSwipeDuration(100), "swipe duration lower bound");
        check(BridgeOps.validSwipeDuration(5000), "swipe duration upper bound");
        check(!BridgeOps.validSwipeDuration(99), "swipe duration below bound refused");
        check(!BridgeOps.validSwipeDuration(5001), "swipe duration above bound refused");

        eq("hello%sWorld", BridgeOps.escapeInputText("hello World"), "space escapes to %s");
        eq("100%%", BridgeOps.escapeInputText("100%"), "% escapes to %%");
        eq("a%sb", BridgeOps.escapeInputText("a b"), "single space");
        eq("abc", BridgeOps.escapeInputText("ab\nc"), "newline dropped");
        eq("abc", BridgeOps.escapeInputText("ab\tc"), "tab dropped");
        eq("", BridgeOps.escapeInputText(null), "null escapes to empty");
        eq("", BridgeOps.escapeInputText(""), "empty escapes to empty");
        eq("caf\u00e9", BridgeOps.escapeInputText("caf\u00e9"), "non-ASCII passes through");
        check(BridgeOps.validEscapedText("hello%sWorld"), "escaped text valid");
        check(!BridgeOps.validEscapedText(""), "empty escaped text refused");
        check(!BridgeOps.validEscapedText("has\nnewline"), "newline in escaped text refused");
        check(!BridgeOps.validEscapedText(null), "null escaped text refused");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2001; i++) big.append('a');
        check(!BridgeOps.validEscapedText(big.toString()), "over-long text refused");

        check(BridgeOps.verifyInput("", 0, "Tap").ok, "input exit 0 verifies");
        check(!BridgeOps.verifyInput("Error: bad coordinates", 1, "Tap").ok,
                "input error does not verify");
    }

    private static void capture() {
        argvEq(BridgeOps.screencapCommand(),
                new String[]{"/system/bin/screencap", "-p"}, "screencap argv");
        argvEq(BridgeOps.screenrecordCommand(30, "/sdcard/Movies/x.mp4"),
                new String[]{"/system/bin/screenrecord", "--time-limit", "30",
                        "/sdcard/Movies/x.mp4"}, "screenrecord argv");
        argvEq(BridgeOps.bugreportCommand(),
                new String[]{"/system/bin/bugreport"}, "bugreport argv");
        check(BridgeOps.validRecordSeconds(10), "10s recording allowed");
        check(BridgeOps.validRecordSeconds(30), "30s recording allowed");
        check(BridgeOps.validRecordSeconds(60), "60s recording allowed");
        check(!BridgeOps.validRecordSeconds(61), "61s recording refused");
        check(!BridgeOps.validRecordSeconds(0), "0s recording refused");
        eq("/sdcard/Movies/control-record-123.mp4", BridgeOps.recordingPath(123L),
                "recording path lands in Movies");
        eq("/sdcard/Download/control-bugreport-123.txt", BridgeOps.bugreportPath(123L),
                "bugreport path lands in Download");
        check(BridgeOps.validateApkStagePath("/sdcard/Download/control-install-abc123.apk"),
                "staged path valid");
        check(BridgeOps.validateApkStagePath("/sdcard/Download/control-install-a_b-c9.apk"),
                "staged path with safe token chars valid");
        check(!BridgeOps.validateApkStagePath("/sdcard/Download/other.apk"),
                "non-staged Download path refused");
        check(!BridgeOps.validateApkStagePath("/sdcard/Download/control-install-../evil.apk"),
                "traversal in staged path refused");
        check(!BridgeOps.validateApkStagePath("/sdcard/Download/control-install-x.apk/extra"),
                "trailing path in staged path refused");
        check(!BridgeOps.validateApkStagePath("/data/local/tmp/control-install-x.apk"),
                "wrong directory refused");
        check(!BridgeOps.validateApkStagePath("/sdcard/Download/control-install-x.zip"),
                "non-APK extension refused");
        check(!BridgeOps.validateApkStagePath(null), "null staged path refused");
        eq("control-install-tok99.apk", BridgeOps.stageFileName("tok99"), "stage file name");
    }

    private static void power() {
        check(BridgeOps.validDozeAction("force-idle"), "force-idle is a doze verb");
        check(BridgeOps.validDozeAction("step"), "step is a doze verb");
        check(!BridgeOps.validDozeAction("idle"), "idle is not a doze verb");
        check(!BridgeOps.validDozeAction(""), "empty is not a doze verb");
        argvEq(BridgeOps.dozeCommand("force-idle"),
                new String[]{"/system/bin/dumpsys", "deviceidle", "force-idle"},
                "doze argv");
        argvEq(BridgeOps.deviceIdleStateCommand(),
                new String[]{"/system/bin/dumpsys", "deviceidle"}, "deviceidle state argv");
        eq("IDLE", BridgeOps.parseDeviceIdleState("mState=IDLE mLightState=ACTIVE\n"),
                "device idle state parses");
        eq("ACTIVE", BridgeOps.parseDeviceIdleState("  mState=ACTIVE\n"), "state ACTIVE parses");
        eq(null, BridgeOps.parseDeviceIdleState("no state here\n"), "missing state is null");
        eq(null, BridgeOps.parseDeviceIdleState(null), "null dump state is null");
        BridgeOps.Outcome dOk = BridgeOps.verifyDoze("", 0, "force-idle", "IDLE");
        check(dOk.ok, "doze exit 0 verifies");
        check(dOk.message.contains("IDLE"), "doze verify carries the re-read state");
        check(!BridgeOps.verifyDoze("Error: deviceidle", 1, "force-idle", null).ok,
                "doze error does not verify");

        argvEq(BridgeOps.batterySetLevelCommand(42),
                new String[]{"/system/bin/dumpsys", "battery", "set", "level", "42"},
                "battery set level argv");
        argvEq(BridgeOps.batteryUnplugCommand(),
                new String[]{"/system/bin/dumpsys", "battery", "unplug"}, "battery unplug argv");
        argvEq(BridgeOps.batteryResetCommand(),
                new String[]{"/system/bin/dumpsys", "battery", "reset"}, "battery reset argv");
        argvEq(BridgeOps.batteryGetCommand(),
                new String[]{"/system/bin/dumpsys", "battery"}, "battery get argv");
        check(BridgeOps.validBatteryLevel(0), "battery level 0 valid");
        check(BridgeOps.validBatteryLevel(100), "battery level 100 valid");
        check(!BridgeOps.validBatteryLevel(101), "battery level 101 refused");
        check(!BridgeOps.validBatteryLevel(-1), "battery level -1 refused");

        String dump = "Current Battery Service state:\n  AC powered: false\n"
                + "  USB powered: true\n  status: 2\n  level: 42\n";
        BridgeOps.BatteryInfo info = BridgeOps.parseBattery(dump);
        eq(42, info.level, "battery level parses");
        eq(Boolean.TRUE, info.powered, "battery powered parses from USB");
        eq("Charging", info.status, "battery status 2 is Charging");
        eq("Level 42% · Charging · Plugged in", BridgeOps.batteryDisplay(info),
                "battery display line");
        BridgeOps.BatteryInfo unknown = BridgeOps.parseBattery("nothing\n");
        eq(-1, unknown.level, "missing battery level is -1");
        eq(null, unknown.powered, "missing powered is null");
        eq(null, unknown.status, "missing status is null");
        check(BridgeOps.batteryDisplay(unknown).contains("could not be read"),
                "unknown battery says so");
        check(BridgeOps.verifyBatteryLevel(info, 42).ok, "level re-read match verifies");
        check(!BridgeOps.verifyBatteryLevel(info, 43).ok, "level re-read mismatch fails");
        check(!BridgeOps.verifyBatteryLevel(unknown, 42).ok, "unreadable level fails");

        eq(5, BridgeOps.STANDBY_BUCKETS.size(), "five standby buckets");
        argvEq(BridgeOps.standbyGetCommand("com.example.app"),
                new String[]{"/system/bin/am", "get-standby-bucket", "com.example.app"},
                "standby get argv");
        argvEq(BridgeOps.standbySetCommand("com.example.app", "RARE"),
                new String[]{"/system/bin/am", "set-standby-bucket", "com.example.app", "rare"},
                "standby set argv lowercases the bucket");
        eq("RARE", BridgeOps.parseStandbyBucket("40\n"), "standby number parses to RARE");
        eq("ACTIVE", BridgeOps.parseStandbyBucket("Bucket: ACTIVE\n"), "standby name parses");
        eq("WORKING_SET", BridgeOps.parseStandbyBucket("20"), "standby 20 is WORKING_SET");
        eq(null, BridgeOps.parseStandbyBucket("no bucket\n"), "missing bucket is null");
        eq(null, BridgeOps.parseStandbyBucket(null), "null bucket output is null");

        check(BridgeOps.validJobId("0"), "job id 0 valid");
        check(BridgeOps.validJobId("12345"), "job id 12345 valid");
        check(!BridgeOps.validJobId("-1"), "negative job id refused");
        check(!BridgeOps.validJobId("abc"), "non-numeric job id refused");
        check(!BridgeOps.validJobId("99999999999"), "oversized job id refused");
        check(!BridgeOps.validJobId(null), "null job id refused");
        argvEq(BridgeOps.runJobCommand("com.example.app", 7),
                new String[]{"/system/bin/cmd", "jobscheduler", "run", "-f",
                        "com.example.app", "7"}, "run job argv");
        check(BridgeOps.verifyRunJob("Job 7 for com.example.app scheduled\n", 0).ok,
                "job run clean output verifies");
        check(!BridgeOps.verifyRunJob("Error: job not found\n", 1).ok,
                "job run error does not verify");
    }

    private static void systemConfig() {
        argvEq(BridgeOps.deviceConfigListCommand(null),
                new String[]{"/system/bin/cmd", "device_config", "list"}, "device_config list argv");
        argvEq(BridgeOps.deviceConfigListCommand("activity_manager"),
                new String[]{"/system/bin/cmd", "device_config", "list", "activity_manager"},
                "device_config list namespace argv");
        argvEq(BridgeOps.deviceConfigGetCommand("ns", "key"),
                new String[]{"/system/bin/cmd", "device_config", "get", "ns", "key"},
                "device_config get argv");
        argvEq(BridgeOps.deviceConfigPutCommand("ns", "key", "value"),
                new String[]{"/system/bin/cmd", "device_config", "put", "ns", "key", "value"},
                "device_config put argv");
        check(BridgeOps.validNamespace("activity_manager"), "namespace valid");
        check(!BridgeOps.validNamespace("Activity_Manager"), "uppercase namespace refused");
        check(!BridgeOps.validNamespace("1bad"), "digit-led namespace refused");
        check(!BridgeOps.validNamespace("has space"), "spaced namespace refused");
        check(!BridgeOps.validNamespace(null), "null namespace refused");
        check(BridgeOps.validConfigKey("max_cached_processes"), "config key valid");
        check(BridgeOps.validConfigKey("some.key-name"), "dotted/dashed key valid");
        check(!BridgeOps.validConfigKey(""), "empty key refused");
        check(!BridgeOps.validConfigKey("has space"), "spaced key refused");
        check(!BridgeOps.validConfigKey("semi;colon"), "semicolon key refused");
        check(BridgeOps.validConfigValue("true"), "config value valid");
        check(BridgeOps.validConfigValue(""), "empty config value valid (clears)");
        check(!BridgeOps.validConfigValue("two\nlines"), "multiline value refused");
        check(BridgeOps.configReadbackMatches("true\n", "true"), "config read-back matches");
        check(!BridgeOps.configReadbackMatches("null\n", "true"), "null read-back never matches");
        check(!BridgeOps.configReadbackMatches("false\n", "true"), "config mismatch fails");

        argvEq(BridgeOps.wifiListCommand(),
                new String[]{"/system/bin/cmd", "wifi", "list-networks"}, "wifi list argv");
        argvEq(BridgeOps.wifiForgetCommand(3),
                new String[]{"/system/bin/cmd", "wifi", "forget-network", "3"}, "wifi forget argv");
        check(BridgeOps.validNetworkId("0"), "network id 0 valid");
        check(!BridgeOps.validNetworkId("-1"), "negative network id refused");
        check(!BridgeOps.validNetworkId("abc"), "non-numeric network id refused");
        List<BridgeOps.WifiNetwork> nets = BridgeOps.parseWifiNetworks(
                "ID: 0 SSID: \"HomeWifi\"\nID: 1 SSID: Cafe\nnot a network line\n");
        eq(2, nets.size(), "wifi list parses two networks");
        eq(0, nets.get(0).id, "first network id");
        eq("HomeWifi", nets.get(0).ssid, "first network ssid unquoted");
        eq("Cafe", nets.get(1).ssid, "second network ssid");
        eq(0, BridgeOps.parseWifiNetworks("no networks here\n").size(),
                "unparseable wifi output yields an empty list, never a guess");
        check(BridgeOps.verifyWifiForget("", 0).ok, "wifi forget exit 0 verifies");
        check(!BridgeOps.verifyWifiForget("Error: no such network\n", 1).ok,
                "wifi forget error does not verify");

        argvEq(BridgeOps.listUsersCommand(),
                new String[]{"/system/bin/pm", "list", "users"}, "list users argv");
        argvEq(BridgeOps.createUserCommand("Work"),
                new String[]{"/system/bin/pm", "create-user", "Work"}, "create user argv");
        argvEq(BridgeOps.removeUserCommand(10),
                new String[]{"/system/bin/pm", "remove-user", "10"}, "remove user argv");
        argvEq(BridgeOps.currentUserCommand(),
                new String[]{"/system/bin/am", "get-current-user"}, "current user argv");
        check(BridgeOps.validUserName("Work"), "user name valid");
        check(BridgeOps.validUserName("  Kids  "), "user name trims");
        check(!BridgeOps.validUserName(""), "empty user name refused");
        check(!BridgeOps.validUserName("   "), "blank user name refused");
        check(!BridgeOps.validUserName("-rf"), "dash-led user name refused");
        check(!BridgeOps.validUserName("has\nnewline"), "newline user name refused");
        List<BridgeOps.UserInfo> users = BridgeOps.parseUsers(
                "Users:\n\tUserInfo{0:Owner:13} running\n\tUserInfo{10:Work:11}\n");
        eq(2, users.size(), "user list parses two users");
        eq(0, users.get(0).id, "owner id 0");
        eq("Owner", users.get(0).name, "owner name");
        check(users.get(0).running, "owner marked running");
        eq(10, users.get(1).id, "second user id");
        check(!users.get(1).running, "second user not running");
        eq(0, BridgeOps.parseUsers("garbage\n").size(), "unparseable user output is empty");
        eq(10, BridgeOps.parseCreateUser("Success: created user id 10\n"),
                "create-user id parses");
        eq(-1, BridgeOps.parseCreateUser("Error: could not create user\n"),
                "failed create-user has no id");
        eq(0, BridgeOps.parseCurrentUser("0\n"), "current user parses");
        eq(-1, BridgeOps.parseCurrentUser("n/a\n"), "unparseable current user is -1");
        check(BridgeOps.refuseRemoveUser(0, 0) != null, "user 0 removal refused");
        check(BridgeOps.refuseRemoveUser(0, 10) != null, "user 0 refused even when not current");
        check(BridgeOps.refuseRemoveUser(10, 10) != null, "current user removal refused");
        eq(null, BridgeOps.refuseRemoveUser(10, 0), "non-current non-zero user removable");
        eq(null, BridgeOps.refuseRemoveUser(10, -1), "removal allowed when current user unreadable");
        check(BridgeOps.refuseRemoveUser(0, 0).contains("user 0"),
                "user 0 refusal names the reason");
        check(BridgeOps.verifyRemoveUser("Success: removed user\n", 0).ok,
                "remove-user Success verifies");
        check(!BridgeOps.verifyRemoveUser("Failure: user not found\n", 1).ok,
                "remove-user Failure does not verify");
    }

    private static void launchAndDumpsys() {
        eq("com.example.app.Foo", BridgeOps.resolveClassName("com.example.app", ".Foo"),
                "relative class resolves");
        eq("com.other.Foo", BridgeOps.resolveClassName("com.example.app", "com.other.Foo"),
                "qualified class passes through");
        check(BridgeOps.validClassName("com.example.app.MainActivity"), "class name valid");
        check(BridgeOps.validClassName("com.example.app.Outer$Inner"),
                "inner class name valid");
        check(!BridgeOps.validClassName(".Foo"), "leading-dot class invalid");
        check(!BridgeOps.validClassName("com..Foo"), "empty segment invalid");
        check(!BridgeOps.validClassName("com.example.app/evil"), "slash class invalid");
        check(!BridgeOps.validClassName("com.example.app; rm"), "shell class invalid");
        check(!BridgeOps.validClassName(null), "null class invalid");
        argvEq(BridgeOps.launchActivityCommand("com.example.app", "com.example.app.Main"),
                new String[]{"/system/bin/am", "start", "-n",
                        "com.example.app/com.example.app.Main"}, "am start argv");
        check(BridgeOps.verifyAmStart("Starting: Intent { cmp=com.example.app/.Main }\n", 0).ok,
                "am start Starting line verifies");
        check(!BridgeOps.verifyAmStart("Error: Activity not started, unable to resolve Intent\n", 1).ok,
                "am start error does not verify");
        check(!BridgeOps.verifyAmStart("", 0).ok, "am start empty output does not verify");
        // v1.38: the already-on-top warning is success — the
        // activity came forward — with or without a Starting
        // line, and case-insensitively. Error/exception markers
        // and a nonzero exit still fail, warning or not.
        BridgeOps.Outcome top = BridgeOps.verifyAmStart(
                "Warning: Activity not started, intent has been delivered to "
                        + "currently running top-most instance.\n", 0);
        check(top.ok, "am start already-on-top warning verifies");
        check(top.message.contains("Already running"),
                "already-on-top message says the activity was already running");
        check(BridgeOps.verifyAmStart(
                "Starting: Intent { cmp=com.example.app/.Main }\n"
                        + "Warning: Activity not started, intent has been delivered to "
                        + "currently running top-most instance.\n", 0).ok,
                "am start Starting line plus warning verifies");
        check(BridgeOps.verifyAmStart(
                "WARNING: INTENT HAS BEEN DELIVERED TO CURRENTLY RUNNING "
                        + "TOP-MOST INSTANCE.\n", 0).ok,
                "am start warning match is case-insensitive");
        check(!BridgeOps.verifyAmStart(
                "Warning: Activity not started, intent has been delivered to "
                        + "currently running top-most instance.\n"
                        + "Error: something else failed\n", 0).ok,
                "am start warning plus error does not verify");
        check(!BridgeOps.verifyAmStart(
                "Warning: Activity not started, intent has been delivered to "
                        + "currently running top-most instance.\n"
                        + "java.lang.SecurityException: denial\n", 0).ok,
                "am start warning plus exception does not verify");
        check(!BridgeOps.verifyAmStart(
                "Warning: Activity not started, intent has been delivered to "
                        + "currently running top-most instance.\n", 1).ok,
                "am start warning with nonzero exit does not verify");

        eq(11, BridgeOps.DUMPSYS_SERVICES.length, "eleven dumpsys services on the menu");
        check(BridgeOps.validDumpsysService("battery"), "battery is on the dumpsys menu");
        check(BridgeOps.validDumpsysService("jobscheduler"), "jobscheduler is on the menu");
        check(!BridgeOps.validDumpsysService(""), "empty service is not on the menu");
        check(!BridgeOps.validDumpsysService("all"), "all is not on the menu");
        check(!BridgeOps.validDumpsysService("BATTERY"), "service match is case-sensitive");
        check(!BridgeOps.validDumpsysService(null), "null service is not on the menu");
        argvEq(BridgeOps.dumpsysCommand("battery"),
                new String[]{"/system/bin/dumpsys", "battery"}, "dumpsys argv");
    }

    private static void sharedShapes() {
        // Coordinate sanity bound: real displays pass, nonsense fails.
        check(BridgeOps.validCoord(1079, BridgeOps.COORD_SANITY_MAX),
                "real display coordinate passes the sanity bound");
        check(BridgeOps.validCoord(8191, BridgeOps.COORD_SANITY_MAX),
                "sanity bound upper edge passes");
        check(!BridgeOps.validCoord(8192, BridgeOps.COORD_SANITY_MAX),
                "sanity bound rejects 8192");
        check(!BridgeOps.validCoord(-1, BridgeOps.COORD_SANITY_MAX),
                "sanity bound rejects negatives");

        // WM state: compose + parse round-trips, empty means unreadable.
        BridgeOps.WmValue size = BridgeOps.parseWmSize(
                "Physical size: 1080x2400\nOverride size: 720x1280\n");
        BridgeOps.WmValue density = BridgeOps.parseWmDensity("Physical density: 420\n");
        BridgeOps.WmValue over = BridgeOps.parseWmOverscan("Overscan: 0,0,0,0\n");
        String composed = BridgeOps.composeWmState(size, density, over);
        BridgeOps.WmState state = BridgeOps.parseWmState(composed);
        eq("1080x2400", state.sizePhysical, "wm state size physical round-trips");
        eq("720x1280", state.sizeOverride, "wm state size override round-trips");
        eq("420", state.densityPhysical, "wm state density physical round-trips");
        eq(null, state.densityOverride, "wm state density override empty means null");
        eq("0,0,0,0", state.overscan, "wm state overscan round-trips");
        check(state.hasOverride(), "wm state with a size override has an override");
        BridgeOps.WmState plain = BridgeOps.parseWmState(BridgeOps.composeWmState(
                BridgeOps.parseWmSize("Physical size: 1080x2400\n"),
                BridgeOps.parseWmDensity("Physical density: 420\n"),
                BridgeOps.parseWmOverscan("Overscan: 0,0,0,0\n")));
        check(!plain.hasOverride(), "wm state with no overrides has none");
        BridgeOps.WmState unreadable = BridgeOps.parseWmState(
                BridgeOps.composeWmState(
                        BridgeOps.parseWmSize("garbage"),
                        BridgeOps.parseWmDensity("garbage"),
                        BridgeOps.parseWmOverscan("garbage")));
        eq(null, unreadable.sizePhysical, "unparseable size readout is null, never guessed");
        eq(null, unreadable.overscan, "unparseable overscan readout is null");
        eq(null, BridgeOps.parseWmState("").sizePhysical, "empty wm state text parses to nulls");
        eq(null, BridgeOps.parseWmState(null).densityPhysical, "null wm state text parses to nulls");

        // Key=value parsing: first '=' splits, bad lines ignored.
        java.util.Map<String, String> kv = BridgeOps.parseKeyValues(
                "a=1\nb=two=three\nnoequals\n=novalue\n c = spaced \n");
        eq("1", kv.get("a"), "kv simple pair");
        eq("two=three", kv.get("b"), "kv value keeps later equals signs");
        check(!kv.containsKey("noequals"), "kv ignores lines without equals");
        check(!kv.containsKey(""), "kv ignores empty keys");
        eq("spaced", kv.get("c"), "kv trims key and value");
        eq(0, BridgeOps.parseKeyValues(null).size(), "kv null is empty");

        // Long-task status: state word + path + size, robust to garbage.
        BridgeOps.LongTaskStatus running = BridgeOps.parseLongTaskStatus(
                BridgeOps.composeLongTaskStatus("RUNNING", "/sdcard/Movies/x.mp4", 12345L));
        check(running.isRunning(), "long-task RUNNING parses");
        check(running.exists(), "long-task RUNNING exists");
        eq("/sdcard/Movies/x.mp4", running.path, "long-task path parses");
        eq(12345L, running.size, "long-task size parses");
        BridgeOps.LongTaskStatus done = BridgeOps.parseLongTaskStatus(
                "FINISHED\npath=/sdcard/Download/b.txt\nsize=987\n");
        check(!done.isRunning(), "long-task FINISHED is not running");
        check(done.exists(), "long-task FINISHED exists");
        eq(987L, done.size, "long-task finished size parses");
        BridgeOps.LongTaskStatus none = BridgeOps.parseLongTaskStatus("NONE\npath=\nsize=-1\n");
        check(!none.exists(), "long-task NONE does not exist");
        eq(null, none.path, "long-task NONE has no path");
        BridgeOps.LongTaskStatus garbage = BridgeOps.parseLongTaskStatus("bogus\n");
        check(!garbage.exists(), "long-task garbage state becomes NONE");
        BridgeOps.LongTaskStatus badSize = BridgeOps.parseLongTaskStatus(
                "RUNNING\npath=/x\nsize=notanumber\n");
        eq(-1L, badSize.size, "long-task bad size becomes -1");
        check(BridgeOps.parseLongTaskStatus(null).state.equals("NONE"),
                "long-task null text becomes NONE");

        // Human sizes.
        eq("0 B", BridgeOps.humanSize(0), "human size 0");
        eq("512 B", BridgeOps.humanSize(512), "human size bytes");
        eq("2 KB", BridgeOps.humanSize(2048), "human size KB");
        eq("1.5 MB", BridgeOps.humanSize(1572864), "human size MB");
        eq("unknown size", BridgeOps.humanSize(-1), "human size unknown");
    }
}
