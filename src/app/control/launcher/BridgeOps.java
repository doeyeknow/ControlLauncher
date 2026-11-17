package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The v1.35 bridge expansion, as pure Java — no Android classes —
 * so the harness exercises the exact shipping code.
 *
 * This class owns everything about the new bridge ops EXCEPT
 * running them: the exact argv for every command (fixed arrays,
 * never a composed shell string), the output verification that
 * decides success (the v1.33 lesson — a command's own output
 * and exit status prove the outcome, never an assumption), the
 * allowlists (settings keys, key codes, dumpsys services), and
 * the parsers that shape tool output into UI facts.
 *
 * ShellBridgeMain builds its commands and verifies its results
 * through here; the app-side UI validates through here too, so
 * both sides enforce the same rules. Nothing in this class
 * invents data: an unparseable readout is null, and the UI says
 * so honestly instead of showing a guessed value.
 *
 * What this class cannot prove: the exact wording of each
 * tool's output on Android 16 (parsers are written against the
 * documented output and fail honestly on anything else), or
 * whether a given build allows a given op at all (pm suspend
 * and the `cmd wifi` network commands vary — refusals come
 * back verbatim, and the on-device checklist is the verdict).
 */
public final class BridgeOps {

    /** Verified outcome of one tool run. */
    public static final class Outcome {
        /** True only when the tool's own output/exit status
         *  confirmed the action. */
        public final boolean ok;
        /** On success: the confirming fact. On failure: the
         *  honest reason, including the tool's own words. */
        public final String message;

        Outcome(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static final int MAX_ECHO_CHARS = 300;

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern CLASS_SEGMENT = Pattern.compile(
            "^[A-Za-z_$][A-Za-z0-9_$]*$");

    private BridgeOps() {}

    /** Error markers: words a tool prints when it refused.
     *  Checked case-insensitively against combined output. */
    public static boolean hasErrorMarker(String output) {
        if (output == null) return false;
        String low = output.toLowerCase(Locale.ROOT);
        return low.contains("error") || low.contains("exception")
                || low.contains("failure") || low.contains("permission denial")
                || low.contains("not allowed") || low.contains("unknown command")
                || low.contains("unknown package") || low.contains("denied")
                || low.contains("invalid");
    }

    static String cap(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    private static Outcome fail(String action, String output) {
        String out = output == null ? "" : output.trim();
        if (out.isEmpty()) {
            return new Outcome(false, "Android did not confirm the " + action
                    + " — the tool printed nothing.");
        }
        return new Outcome(false, "Android did not confirm the " + action + ": "
                + cap(out, MAX_ECHO_CHARS));
    }

    // ---- packages: uninstall / restore / install / suspend ----

    /** {@code pm uninstall --user 0}: removes the app for the
     *  owner user only. The APK stays on the device, so
     *  install-existing restores it. */
    public static String[] uninstallUserCommand(String pkg) {
        return new String[]{"/system/bin/pm", "uninstall", "--user", "0", pkg};
    }

    /** {@code pm install-existing --user 0}: restores an app
     *  that was uninstalled for user 0. */
    public static String[] installExistingCommand(String pkg) {
        return new String[]{"/system/bin/pm", "install-existing", "--user", "0", pkg};
    }

    /** {@code pm install -r}: silent install of a staged APK
     *  file. The path must pass {@link #validateApkStagePath}
     *  first — the bridge never installs from an arbitrary
     *  path. */
    public static String[] installApkCommand(String path) {
        return new String[]{"/system/bin/pm", "install", "-r", path};
    }

    /** {@code pm suspend} / {@code pm unsuspend}. Whether a
     *  build allows suspending is decided on the phone: the
     *  verifier passes the refusal through verbatim. */
    public static String[] suspendCommand(String pkg, boolean suspend) {
        return new String[]{"/system/bin/pm", suspend ? "suspend" : "unsuspend", pkg};
    }

    /** {@code pm trim-caches}: free cached data until at
     *  least this many bytes are free. */
    public static String[] trimCachesCommand(long bytes) {
        return new String[]{"/system/bin/pm", "trim-caches", String.valueOf(bytes)};
    }

    /** The fixed trim target: 1 GiB of free space. */
    public static final long TRIM_CACHES_TARGET_BYTES = 1024L * 1024L * 1024L;

    /** {@code pm list packages}, with {@code -u} to include
     *  packages uninstalled for the user. */
    public static String[] listPackagesCommand(boolean includeUninstalled) {
        return includeUninstalled
                ? new String[]{"/system/bin/pm", "list", "packages", "-u"}
                : new String[]{"/system/bin/pm", "list", "packages"};
    }

    /** Uninstall succeeds only on exit 0 plus pm's "Success"
     *  (a "Failure" line anywhere wins). */
    public static Outcome verifyUninstall(String output, int exitCode) {
        String out = output == null ? "" : output;
        if (exitCode == 0 && out.contains("Success") && !out.contains("Failure")) {
            return new Outcome(true, "Success");
        }
        return fail("uninstall", out);
    }

    /** install-existing prints "Package &lt;pkg&gt; installed
     *  for user: 0" on success. */
    public static Outcome verifyInstallExisting(String output, int exitCode) {
        String out = output == null ? "" : output;
        if (exitCode == 0 && out.contains("installed for user")
                && !out.contains("Failure")) {
            return new Outcome(true, firstLine(out));
        }
        return fail("restore", out);
    }

    /** APK install succeeds only on exit 0 plus "Success". */
    public static Outcome verifyInstallApk(String output, int exitCode) {
        String out = output == null ? "" : output;
        if (exitCode == 0 && out.contains("Success") && !out.contains("Failure")) {
            return new Outcome(true, "Success");
        }
        return fail("install", out);
    }

    /** Suspend/unsuspend: pm's wording varies by build, so the
     *  exit status plus the absence of error markers is the
     *  proof, and pm's own words are always carried through —
     *  including the empty-output case, which says so. */
    public static Outcome verifySuspend(String output, int exitCode, boolean suspend) {
        String action = suspend ? "suspend" : "unsuspend";
        String out = output == null ? "" : output.trim();
        if (exitCode == 0 && !hasErrorMarker(out)) {
            return new Outcome(true, out.isEmpty()
                    ? "pm printed nothing and exited 0 — treated as applied; "
                    + "check the app's state to confirm."
                    : firstLine(out));
        }
        return fail(action, out);
    }

    /** trim-caches: exit 0 plus no error markers. */
    public static Outcome verifyTrimCaches(String output, int exitCode) {
        String out = output == null ? "" : output.trim();
        if (exitCode == 0 && !hasErrorMarker(out)) {
            return new Outcome(true, out.isEmpty()
                    ? "Cache trim ran." : firstLine(out));
        }
        return fail("cache trim", out);
    }

    /** Package names from {@code pm list packages} output:
     *  one "package:&lt;name&gt;" per line. */
    public static Set<String> parsePackageList(String output) {
        Set<String> out = new HashSet<>();
        if (output == null) return out;
        for (String line : output.split("\\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) {
                String pkg = t.substring("package:".length()).trim();
                if (PACKAGE_PATTERN.matcher(pkg).matches()) out.add(pkg);
            }
        }
        return out;
    }

    /** Packages present in the -u list but absent from the
     *  plain list: uninstalled for user 0, restorable. Sorted,
     *  so the picker reads alphabetically. */
    public static List<String> removedPackages(String installedOutput, String allOutput) {
        Set<String> installed = parsePackageList(installedOutput);
        Set<String> all = parsePackageList(allOutput);
        List<String> removed = new ArrayList<>();
        for (String pkg : all) {
            if (!installed.contains(pkg)) removed.add(pkg);
        }
        java.util.Collections.sort(removed);
        return removed;
    }

    private static String firstLine(String out) {
        String t = out.trim();
        int nl = t.indexOf('\n');
        return cap(nl < 0 ? t : t.substring(0, nl), MAX_ECHO_CHARS);
    }

    // ---- display size / density / overscan ----

    /** A `wm` readout: the physical value plus the override
     *  (null when no override is set, or when the output could
     *  not be parsed — the raw text is kept for display). */
    public static final class WmValue {
        public final String physical;
        /** Null when no override line was present. */
        public final String override;
        public final String raw;

        WmValue(String physical, String override, String raw) {
            this.physical = physical;
            this.override = override;
            this.raw = raw;
        }
    }

    public static String[] wmSizeGetCommand() {
        return new String[]{"/system/bin/wm", "size"};
    }

    public static String[] wmSizeSetCommand(int width, int height) {
        return new String[]{"/system/bin/wm", "size", width + "x" + height};
    }

    public static String[] wmSizeResetCommand() {
        return new String[]{"/system/bin/wm", "size", "reset"};
    }

    public static String[] wmDensityGetCommand() {
        return new String[]{"/system/bin/wm", "density"};
    }

    public static String[] wmDensitySetCommand(int dpi) {
        return new String[]{"/system/bin/wm", "density", String.valueOf(dpi)};
    }

    public static String[] wmDensityResetCommand() {
        return new String[]{"/system/bin/wm", "density", "reset"};
    }

    public static String[] wmOverscanGetCommand() {
        return new String[]{"/system/bin/wm", "overscan"};
    }

    public static String[] wmOverscanSetCommand(int left, int top, int right, int bottom) {
        return new String[]{"/system/bin/wm", "overscan",
                left + "," + top + "," + right + "," + bottom};
    }

    public static String[] wmOverscanResetCommand() {
        return new String[]{"/system/bin/wm", "overscan", "reset"};
    }

    /** Density bounds: below 120 everything is unreadably
     *  small, above 640 unreadably large. */
    public static boolean validDensity(int dpi) {
        return dpi >= 120 && dpi <= 640;
    }

    /** Display-size bounds: each side must be a sane pixel
     *  count, and the pair must not be degenerate. */
    public static boolean validSize(int width, int height) {
        return width >= 240 && width <= 4096 && height >= 240 && height <= 4096;
    }

    /** Overscan margins: non-negative, at most 500px a side
     *  (more would eat the screen). */
    public static boolean validOverscan(int left, int top, int right, int bottom) {
        return left >= 0 && left <= 500 && top >= 0 && top <= 500
                && right >= 0 && right <= 500 && bottom >= 0 && bottom <= 500;
    }

    private static final Pattern SIZE_LINE = Pattern.compile(
            "(Physical|Override) size:\\s*(\\d+)x(\\d+)");
    private static final Pattern DENSITY_LINE = Pattern.compile(
            "(Physical|Override) density:\\s*(\\d+)");
    private static final Pattern OVERSCAN_TUPLE = Pattern.compile(
            "(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)");
    private static final Pattern OVERSCAN_NAMED = Pattern.compile(
            "left[=:]\\s*(\\d+).*?top[=:]\\s*(\\d+).*?right[=:]\\s*(\\d+).*?bottom[=:]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Parse `wm size` output. Physical is null when the
     *  output did not parse at all. */
    public static WmValue parseWmSize(String output) {
        String raw = output == null ? "" : output.trim();
        String physical = null;
        String override = null;
        Matcher m = SIZE_LINE.matcher(raw);
        while (m.find()) {
            String value = m.group(2) + "x" + m.group(3);
            if ("Physical".equals(m.group(1))) physical = value;
            else override = value;
        }
        return new WmValue(physical, override, raw);
    }

    /** Parse `wm density` output. */
    public static WmValue parseWmDensity(String output) {
        String raw = output == null ? "" : output.trim();
        String physical = null;
        String override = null;
        Matcher m = DENSITY_LINE.matcher(raw);
        while (m.find()) {
            if ("Physical".equals(m.group(1))) physical = m.group(2);
            else override = m.group(2);
        }
        return new WmValue(physical, override, raw);
    }

    /** Parse `wm overscan` output. The get form's wording is
     *  the least certain of the three (a tuple or named
     *  fields); when neither parses, the value is null and the
     *  UI shows the raw text instead of a guessed rect. The
     *  returned "physical" is the current rect; overscan has
     *  no physical/override split, so override stays null. */
    public static WmValue parseWmOverscan(String output) {
        String raw = output == null ? "" : output.trim();
        Matcher tuple = OVERSCAN_TUPLE.matcher(raw);
        if (tuple.find()) {
            return new WmValue(tuple.group(1) + "," + tuple.group(2) + ","
                    + tuple.group(3) + "," + tuple.group(4), null, raw);
        }
        Matcher named = OVERSCAN_NAMED.matcher(raw);
        if (named.find()) {
            return new WmValue(named.group(1) + "," + named.group(2) + ","
                    + named.group(3) + "," + named.group(4), null, raw);
        }
        return new WmValue(null, null, raw);
    }

    /**
     * Verify a size/density set by re-reading: success when
     * the override equals the requested value — or when there
     * is no override line and the physical value equals it
     * (setting the physical value clears the override, which
     * is the same visible result). Anything else, including
     * an unreadable readout, is "not confirmed".
     */
    public static Outcome verifyWmSet(WmValue readBack, String wanted, String what) {
        if (readBack != null && wanted.equals(readBack.override)) {
            return new Outcome(true, "Now " + wanted + " (override).");
        }
        if (readBack != null && readBack.override == null
                && wanted.equals(readBack.physical)) {
            return new Outcome(true, "Now " + wanted + " (no override — matches the physical value).");
        }
        return new Outcome(false, "Android did not confirm the new " + what
                + " — the re-read did not show " + wanted
                + (readBack == null || readBack.raw.isEmpty()
                        ? "." : ": " + cap(readBack.raw, MAX_ECHO_CHARS)));
    }

    /** Verify an overscan set: the get form's wording is
     *  uncertain, so the exit status plus the absence of error
     *  markers is the proof, and the re-read text is always
     *  carried through for the user to see. */
    public static Outcome verifyOverscanSet(String readBack, int exitCode) {
        if (exitCode == 0 && !hasErrorMarker(readBack)) {
            String raw = readBack == null ? "" : readBack.trim();
            return new Outcome(true, raw.isEmpty()
                    ? "Overscan command ran." : "Overscan set. Re-read: " + firstLine(raw));
        }
        return fail("overscan change", readBack);
    }

    /** One-line display of a WmValue for status lines: the
     *  effective value, naming the override when one exists. */
    public static String wmDisplay(WmValue value, String emptyText) {
        if (value == null || value.physical == null) return emptyText;
        if (value.override != null) return value.override + " (override; physical " + value.physical + ")";
        return value.physical;
    }

    // ---- governed settings: fixed allowlist, no free-form keys ----

    /** One writable setting: namespace, key, value kind, and
     *  the bounds or choices the UI offers. The bridge refuses
     *  any namespace/key pair not in {@link #SETTINGS}. */
    public static final class SettingDef {
        public final String namespace;
        public final String key;
        public final String label;
        /** "float", "int", or "enum". */
        public final String kind;
        public final double min;
        public final double max;
        /** Allowed values for "enum", null otherwise. */
        public final String[] allowed;
        /** Preset choices the UI offers: {label, value} pairs. */
        public final String[][] presets;

        SettingDef(String namespace, String key, String label, String kind,
                   double min, double max, String[] allowed, String[][] presets) {
            this.namespace = namespace;
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.allowed = allowed;
            this.presets = presets;
        }
    }

    /** The complete writable settings surface. Nothing else
     *  is writable through the bridge — there is deliberately
     *  no arbitrary settings endpoint. */
    public static final SettingDef[] SETTINGS = {
            new SettingDef("system", "font_scale", "Font scale", "float",
                    0.85, 1.30, null, new String[][]{
                    {"Smaller", "0.85"}, {"Default", "1.0"},
                    {"Larger", "1.15"}, {"Largest", "1.3"}}),
            new SettingDef("global", "window_animation_scale", "Window animation speed", "float",
                    0, 10, null, new String[][]{
                    {"Off", "0"}, {"Half speed", "0.5"},
                    {"Normal", "1"}, {"Double", "2"}}),
            new SettingDef("global", "transition_animation_scale", "Transition animation speed", "float",
                    0, 10, null, new String[][]{
                    {"Off", "0"}, {"Half speed", "0.5"},
                    {"Normal", "1"}, {"Double", "2"}}),
            new SettingDef("global", "animator_duration_scale", "Animator duration", "float",
                    0, 10, null, new String[][]{
                    {"Off", "0"}, {"Half speed", "0.5"},
                    {"Normal", "1"}, {"Double", "2"}}),
            new SettingDef("system", "screen_off_timeout", "Screen timeout", "int",
                    15000, 1800000, null, new String[][]{
                    {"30 seconds", "30000"}, {"1 minute", "60000"},
                    {"2 minutes", "120000"}, {"5 minutes", "300000"},
                    {"10 minutes", "600000"}, {"30 minutes", "1800000"}}),
            new SettingDef("system", "screen_brightness_mode", "Brightness mode", "enum",
                    0, 0, new String[]{"0", "1"}, new String[][]{
                    {"Manual", "0"}, {"Automatic", "1"}}),
            new SettingDef("system", "screen_brightness", "Screen brightness", "int",
                    0, 255, null, new String[][]{
                    {"Dim", "25"}, {"Low", "80"}, {"Medium", "140"},
                    {"Bright", "200"}, {"Max", "255"}}),
            new SettingDef("system", "accelerometer_rotation", "Auto-rotate", "enum",
                    0, 0, new String[]{"0", "1"}, new String[][]{
                    {"Off", "0"}, {"On", "1"}}),
    };

    /** The allowlisted definition for a namespace/key pair, or
     *  null when the pair is not writable. */
    public static SettingDef findSetting(String namespace, String key) {
        if (namespace == null || key == null) return null;
        for (SettingDef def : SETTINGS) {
            if (def.namespace.equals(namespace) && def.key.equals(key)) return def;
        }
        return null;
    }

    public static String[] settingsGetCommand(SettingDef def) {
        return new String[]{"/system/bin/settings", "get", def.namespace, def.key};
    }

    public static String[] settingsPutCommand(SettingDef def, String value) {
        return new String[]{"/system/bin/settings", "put", def.namespace, def.key, value};
    }

    /** Validate a value against its definition: null means
     *  valid, otherwise the plain-words reason it is refused. */
    public static String validateSettingValue(SettingDef def, String value) {
        if (def == null) return "Setting is not on the bridge allowlist.";
        if (value == null) return "No value was given.";
        String v = value.trim();
        if ("enum".equals(def.kind)) {
            for (String allowed : def.allowed) {
                if (allowed.equals(v)) return null;
            }
            return "Value is not one of the allowed choices.";
        }
        if ("int".equals(def.kind)) {
            long n;
            try {
                n = Long.parseLong(v);
            } catch (Exception e) {
                return "Value must be a whole number.";
            }
            if (n < def.min || n > def.max) {
                return "Value is outside the allowed range.";
            }
            return null;
        }
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (Exception e) {
            return "Value must be a number.";
        }
        if (Double.isNaN(d) || Double.isInfinite(d) || d < def.min || d > def.max) {
            return "Value is outside the allowed range.";
        }
        return null;
    }

    /** Does a `settings get` read-back match the value that
     *  was written? `settings get` prints "null" for an unset
     *  key, which never matches. Floats compare with a small
     *  tolerance (the settings store round-trips floats). */
    public static boolean settingReadbackMatches(SettingDef def, String readBack, String want) {
        if (def == null || readBack == null || want == null) return false;
        String got = readBack.trim();
        if ("null".equals(got) || got.isEmpty()) return false;
        if ("float".equals(def.kind)) {
            try {
                double g = Double.parseDouble(got);
                double w = Double.parseDouble(want.trim());
                return Math.abs(g - w) < 0.001;
            } catch (Exception e) {
                return false;
            }
        }
        return got.equals(want.trim());
    }

    /** Human form of a read-back value for status lines:
     *  "not set" for null/empty, the preset label when the
     *  value matches one, else the raw value. */
    public static String settingDisplay(SettingDef def, String readBack) {
        if (readBack == null) return "not set";
        String got = readBack.trim();
        if (got.isEmpty() || "null".equals(got)) return "not set";
        if (def != null && def.presets != null) {
            for (String[] preset : def.presets) {
                if (settingReadbackMatches(def, got, preset[1])) return preset[0];
            }
        }
        return got;
    }

    // ---- night mode ----

    public static String[] nightModeGetCommand() {
        return new String[]{"/system/bin/cmd", "uimode", "night"};
    }

    /** mode: "yes" (night), "no" (day), or "auto". */
    public static String[] nightModeSetCommand(String mode) {
        return new String[]{"/system/bin/cmd", "uimode", "night", mode};
    }

    public static boolean validNightMode(String mode) {
        return "yes".equals(mode) || "no".equals(mode) || "auto".equals(mode);
    }

    /** Parse `cmd uimode night` output: "yes", "no", "auto",
     *  or null when the wording is not recognized. */
    public static String parseNightMode(String output) {
        if (output == null) return null;
        String low = output.toLowerCase(Locale.ROOT);
        if (low.contains("auto")) return "auto";
        if (low.contains("yes")) return "yes";
        if (low.contains("no")) return "no";
        return null;
    }

    public static String nightModeDisplay(String mode) {
        if ("yes".equals(mode)) return "Night";
        if ("no".equals(mode)) return "Day";
        if ("auto".equals(mode)) return "Automatic";
        return "unknown";
    }

    // ---- input injection: fixed key menu, validated geometry ----

    /** The fixed key-event menu: {label, Android key code}.
     *  No other key code may reach `input keyevent`. */
    public static final String[][] KEY_CODES = {
            {"Back", "4"}, {"Home", "3"}, {"Recents", "187"},
            {"Volume up", "24"}, {"Volume down", "25"}, {"Power", "26"},
            {"Play / pause", "85"}, {"Next track", "87"}, {"Previous track", "88"},
            {"D-pad up", "19"}, {"D-pad down", "20"}, {"D-pad left", "21"},
            {"D-pad right", "22"}, {"D-pad center", "23"}, {"Enter", "66"},
    };

    /** True when the code is on the fixed menu. */
    public static boolean validKeyCode(int code) {
        for (String[] entry : KEY_CODES) {
            if (Integer.parseInt(entry[1]) == code) return true;
        }
        return false;
    }

    /** The menu label for a code, or null when off-menu. */
    public static String keyLabel(int code) {
        for (String[] entry : KEY_CODES) {
            if (Integer.parseInt(entry[1]) == code) return entry[0];
        }
        return null;
    }

    public static String[] keyEventCommand(int code) {
        return new String[]{"/system/bin/input", "keyevent", String.valueOf(code)};
    }

    public static String[] tapCommand(int x, int y) {
        return new String[]{"/system/bin/input", "tap",
                String.valueOf(x), String.valueOf(y)};
    }

    public static String[] swipeCommand(int x1, int y1, int x2, int y2, int durationMs) {
        return new String[]{"/system/bin/input", "swipe",
                String.valueOf(x1), String.valueOf(y1),
                String.valueOf(x2), String.valueOf(y2),
                String.valueOf(durationMs)};
    }

    public static String[] inputTextCommand(String escaped) {
        return new String[]{"/system/bin/input", "text", escaped};
    }

    /** A coordinate is valid inside the display: 0 <= v < max.
     *  The UI validates against the real display size; the
     *  bridge validates against a generous sanity bound. */
    public static boolean validCoord(int value, int max) {
        return value >= 0 && value < max;
    }

    /** Swipe duration bounds, milliseconds. */
    public static boolean validSwipeDuration(int ms) {
        return ms >= 100 && ms <= 5000;
    }

    /**
     * Escape text for `input text`: '%' becomes '%%' and a
     * space becomes '%s' (the tool's own convention), and
     * control characters — which the tool cannot type — are
     * dropped. Escaping is done once, client-side; the bridge
     * validates the escaped shape instead of re-escaping
     * (escaping twice would corrupt '%' sequences).
     */
    public static String escapeInputText(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '%') out.append("%%");
            else if (ch == ' ') out.append("%s");
            else if (ch < 0x20 || ch == 0x7f) {
                // Dropped: `input text` cannot type controls.
            } else out.append(ch);
        }
        return out.toString();
    }

    /** The bridge-side shape check for escaped text: no
     *  control characters, and within the length cap. */
    public static boolean validEscapedText(String escaped) {
        if (escaped == null || escaped.isEmpty() || escaped.length() > 2000) return false;
        for (int i = 0; i < escaped.length(); i++) {
            char ch = escaped.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return false;
        }
        return true;
    }

    /** `input` prints nothing on success: exit 0 with no
     *  error markers is the proof. */
    public static Outcome verifyInput(String output, int exitCode, String what) {
        if (exitCode == 0 && !hasErrorMarker(output)) {
            return new Outcome(true, what + " sent.");
        }
        return fail(what.toLowerCase(Locale.ROOT), output);
    }

    // ---- capture: screenshot / screen record / bugreport ----

    public static String[] screencapCommand() {
        return new String[]{"/system/bin/screencap", "-p"};
    }

    /** Allowed recording lengths, seconds. */
    public static final int[] RECORD_SECONDS = {10, 30, 60};

    public static boolean validRecordSeconds(int seconds) {
        for (int allowed : RECORD_SECONDS) {
            if (allowed == seconds) return true;
        }
        return false;
    }

    public static String[] screenrecordCommand(int seconds, String path) {
        return new String[]{"/system/bin/screenrecord", "--time-limit",
                String.valueOf(seconds), path};
    }

    /** Recordings land in Movies, where the gallery shows
     *  them — that is the retrieval path (no media permission
     *  is added to fetch the file back). */
    public static String recordingPath(long timestampMs) {
        return "/sdcard/Movies/control-record-" + timestampMs + ".mp4";
    }

    /** `bugreport` dumps to stdout; the bridge streams it to
     *  this file in Download, where the user can find it. */
    public static String bugreportPath(long timestampMs) {
        return "/sdcard/Download/control-bugreport-" + timestampMs + ".txt";
    }

    public static String[] bugreportCommand() {
        return new String[]{"/system/bin/bugreport"};
    }

    /** The staged-APK path rule: exactly
     *  /sdcard/Download/control-install-&lt;token&gt;.apk, with a
     *  token of safe characters and no directory traversal.
     *  The bridge installs ONLY from a path that passes this. */
    private static final Pattern STAGE_PATH = Pattern.compile(
            "^/sdcard/Download/control-install-[A-Za-z0-9_-]+\\.apk$");

    public static boolean validateApkStagePath(String path) {
        return path != null && STAGE_PATH.matcher(path).matches();
    }

    /** The staging file name the app writes via MediaStore. */
    public static String stageFileName(String token) {
        return "control-install-" + token + ".apk";
    }

    // ---- power & scheduling: doze, battery, standby, jobs ----

    /** Doze actions: the fixed `dumpsys deviceidle` verbs. */
    public static final String[] DOZE_ACTIONS = {
            "force-idle", "force-inactive", "unforce", "step"};

    public static boolean validDozeAction(String action) {
        for (String allowed : DOZE_ACTIONS) {
            if (allowed.equals(action)) return true;
        }
        return false;
    }

    public static String[] dozeCommand(String action) {
        return new String[]{"/system/bin/dumpsys", "deviceidle", action};
    }

    public static String[] deviceIdleStateCommand() {
        return new String[]{"/system/bin/dumpsys", "deviceidle"};
    }

    private static final Pattern DEVICE_IDLE_STATE =
            Pattern.compile("mState=([A-Z_]+)");

    /** The device-idle state word from a `dumpsys deviceidle`
     *  dump (ACTIVE, IDLE, INACTIVE, ...), or null when the
     *  dump did not carry one. */
    public static String parseDeviceIdleState(String output) {
        if (output == null) return null;
        Matcher m = DEVICE_IDLE_STATE.matcher(output);
        return m.find() ? m.group(1) : null;
    }

    /** Doze verbs print little or nothing: exit 0 plus no
     *  error markers is the proof, and the re-read state is
     *  always reported alongside. */
    public static Outcome verifyDoze(String output, int exitCode, String action,
                                     String stateAfter) {
        if (exitCode == 0 && !hasErrorMarker(output)) {
            return new Outcome(true, "Doze " + action + " ran."
                    + (stateAfter == null ? "" : " State now: " + stateAfter + "."));
        }
        return fail("doze " + action, output);
    }

    public static String[] batterySetLevelCommand(int level) {
        return new String[]{"/system/bin/dumpsys", "battery", "set", "level",
                String.valueOf(level)};
    }

    public static String[] batteryUnplugCommand() {
        return new String[]{"/system/bin/dumpsys", "battery", "unplug"};
    }

    public static String[] batteryResetCommand() {
        return new String[]{"/system/bin/dumpsys", "battery", "reset"};
    }

    public static String[] batteryGetCommand() {
        return new String[]{"/system/bin/dumpsys", "battery"};
    }

    public static boolean validBatteryLevel(int level) {
        return level >= 0 && level <= 100;
    }

    /** Battery facts from a `dumpsys battery` dump. Fields
     *  stay unknown (level -1, powered/status null) when the
     *  dump did not carry them. */
    public static final class BatteryInfo {
        public final int level;
        public final Boolean powered;
        public final String status;

        BatteryInfo(int level, Boolean powered, String status) {
            this.level = level;
            this.powered = powered;
            this.status = status;
        }
    }

    private static final Pattern BATTERY_LEVEL = Pattern.compile(
            "^\\s*level:\\s*(\\d+)", Pattern.MULTILINE);
    private static final Pattern BATTERY_POWERED = Pattern.compile(
            "^\\s*(AC|USB|Wireless) powered:\\s*(true|false)",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    private static final Pattern BATTERY_STATUS = Pattern.compile(
            "^\\s*status:\\s*(\\d+)", Pattern.MULTILINE);

    public static BatteryInfo parseBattery(String output) {
        if (output == null) return new BatteryInfo(-1, null, null);
        int level = -1;
        Matcher lm = BATTERY_LEVEL.matcher(output);
        if (lm.find()) {
            try {
                level = Integer.parseInt(lm.group(1));
            } catch (Exception ignored) {
            }
        }
        Boolean powered = null;
        Matcher pm = BATTERY_POWERED.matcher(output);
        while (pm.find()) {
            boolean on = Boolean.parseBoolean(pm.group(2).toLowerCase(Locale.ROOT));
            powered = (powered == null) ? on : (powered || on);
        }
        String status = null;
        Matcher sm = BATTERY_STATUS.matcher(output);
        if (sm.find()) {
            switch (sm.group(1)) {
                case "1": status = "Unknown"; break;
                case "2": status = "Charging"; break;
                case "3": status = "Discharging"; break;
                case "4": status = "Not charging"; break;
                case "5": status = "Full"; break;
                default: status = "Status " + sm.group(1); break;
            }
        }
        return new BatteryInfo(level, powered, status);
    }

    /** One status line for a battery read-back; unknown
     *  fields are omitted, never guessed. */
    public static String batteryDisplay(BatteryInfo info) {
        if (info == null) return "Battery state could not be read.";
        StringBuilder out = new StringBuilder();
        if (info.level >= 0) out.append("Level ").append(info.level).append("%");
        if (info.status != null) {
            if (out.length() > 0) out.append(" · ");
            out.append(info.status);
        }
        if (info.powered != null) {
            if (out.length() > 0) out.append(" · ");
            out.append(info.powered ? "Plugged in" : "On battery");
        }
        return out.length() > 0 ? out.toString() : "Battery state could not be read.";
    }

    /** Verify a faked level by re-reading: the level must
     *  match exactly. */
    public static Outcome verifyBatteryLevel(BatteryInfo after, int wanted) {
        if (after != null && after.level == wanted) {
            return new Outcome(true, "Battery level now reads " + wanted
                    + "% (simulated — Reset returns to the real battery).");
        }
        return new Outcome(false, "Android did not confirm the simulated level — "
                + "the re-read did not show " + wanted + "%.");
    }

    /** Standby buckets: canonical name -> numeric value. */
    public static final Map<String, Integer> STANDBY_BUCKETS = new LinkedHashMap<>();

    static {
        STANDBY_BUCKETS.put("ACTIVE", 10);
        STANDBY_BUCKETS.put("WORKING_SET", 20);
        STANDBY_BUCKETS.put("FREQUENT", 30);
        STANDBY_BUCKETS.put("RARE", 40);
        STANDBY_BUCKETS.put("RESTRICTED", 50);
    }

    public static String[] standbyGetCommand(String pkg) {
        return new String[]{"/system/bin/am", "get-standby-bucket", pkg};
    }

    public static String[] standbySetCommand(String pkg, String bucket) {
        return new String[]{"/system/bin/am", "set-standby-bucket", pkg,
                bucket.toLowerCase(Locale.ROOT)};
    }

    /** Parse `am get-standby-bucket` output: a number or a
     *  bucket name, normalized to the canonical name, or null
     *  when neither was found. */
    public static String parseStandbyBucket(String output) {
        if (output == null) return null;
        Matcher num = Pattern.compile("\\b(10|20|30|40|50)\\b").matcher(output);
        if (num.find()) {
            int value = Integer.parseInt(num.group(1));
            for (Map.Entry<String, Integer> e : STANDBY_BUCKETS.entrySet()) {
                if (e.getValue() == value) return e.getKey();
            }
        }
        String upper = output.toUpperCase(Locale.ROOT);
        for (String name : STANDBY_BUCKETS.keySet()) {
            if (upper.contains(name)) return name;
        }
        return null;
    }

    /** A job id is a non-negative 32-bit integer. */
    public static boolean validJobId(String raw) {
        if (raw == null) return false;
        try {
            long id = Long.parseLong(raw.trim());
            return id >= 0 && id <= Integer.MAX_VALUE;
        } catch (Exception e) {
            return false;
        }
    }

    public static String[] runJobCommand(String pkg, int jobId) {
        return new String[]{"/system/bin/cmd", "jobscheduler", "run", "-f",
                pkg, String.valueOf(jobId)};
    }

    /** Job runs: exit 0 plus no error markers; the tool's
     *  own words are carried through. */
    public static Outcome verifyRunJob(String output, int exitCode) {
        String out = output == null ? "" : output.trim();
        if (exitCode == 0 && !hasErrorMarker(out)) {
            return new Outcome(true, out.isEmpty()
                    ? "Job run requested." : firstLine(out));
        }
        return fail("job run", out);
    }

    // ---- system config: device_config, wifi networks, users ----

    public static String[] deviceConfigListCommand(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return new String[]{"/system/bin/cmd", "device_config", "list"};
        }
        return new String[]{"/system/bin/cmd", "device_config", "list", namespace};
    }

    public static String[] deviceConfigGetCommand(String namespace, String key) {
        return new String[]{"/system/bin/cmd", "device_config", "get", namespace, key};
    }

    public static String[] deviceConfigPutCommand(String namespace, String key, String value) {
        return new String[]{"/system/bin/cmd", "device_config", "put", namespace, key, value};
    }

    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final Pattern CONFIG_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.\\-]{0,127}$");

    /** Namespace shape: lowercase, starts with a letter. */
    public static boolean validNamespace(String namespace) {
        return namespace != null && NAMESPACE_PATTERN.matcher(namespace).matches();
    }

    /** Config key shape: identifier characters, dots, dashes. */
    public static boolean validConfigKey(String key) {
        return key != null && CONFIG_KEY_PATTERN.matcher(key).matches();
    }

    /** Config values ride as one argv element (no shell), but
     *  must still be single-line and bounded. */
    public static boolean validConfigValue(String value) {
        if (value == null || value.length() > 512) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return false;
        }
        return true;
    }

    /** device_config read-back: exact match after trimming;
     *  "null" (unset) never matches a written value. */
    public static boolean configReadbackMatches(String readBack, String want) {
        if (readBack == null || want == null) return false;
        String got = readBack.trim();
        return !got.isEmpty() && !"null".equals(got) && got.equals(want);
    }

    public static String[] wifiListCommand() {
        return new String[]{"/system/bin/cmd", "wifi", "list-networks"};
    }

    public static String[] wifiForgetCommand(int networkId) {
        return new String[]{"/system/bin/cmd", "wifi", "forget-network",
                String.valueOf(networkId)};
    }

    public static boolean validNetworkId(String raw) {
        if (raw == null) return false;
        try {
            long id = Long.parseLong(raw.trim());
            return id >= 0 && id <= Integer.MAX_VALUE;
        } catch (Exception e) {
            return false;
        }
    }

    /** One saved network, as parsed from the list output. */
    public static final class WifiNetwork {
        public final int id;
        public final String ssid;

        WifiNetwork(int id, String ssid) {
            this.id = id;
            this.ssid = ssid;
        }
    }

    private static final Pattern WIFI_LINE = Pattern.compile(
            "(?i)\\bID\\s*[:=]\\s*(\\d+)\\b.*?\\bSSID\\s*[:=]\\s*\"?([^\",\\n]+)\"?");

    /** Parse saved networks leniently: the exact `cmd wifi`
     *  wording varies, so lines that do not carry both an ID
     *  and an SSID are skipped, and an empty result means the
     *  UI shows the raw output instead of a guessed list. */
    public static List<WifiNetwork> parseWifiNetworks(String output) {
        List<WifiNetwork> out = new ArrayList<>();
        if (output == null) return out;
        for (String line : output.split("\\n")) {
            Matcher m = WIFI_LINE.matcher(line);
            if (!m.find()) continue;
            try {
                int id = Integer.parseInt(m.group(1));
                String ssid = m.group(2).trim();
                if (!ssid.isEmpty()) out.add(new WifiNetwork(id, ssid));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Forget: exit 0 plus no error markers. */
    public static Outcome verifyWifiForget(String output, int exitCode) {
        String out = output == null ? "" : output.trim();
        if (exitCode == 0 && !hasErrorMarker(out)) {
            return new Outcome(true, out.isEmpty()
                    ? "Forget command ran." : firstLine(out));
        }
        return fail("forget network", out);
    }

    public static String[] listUsersCommand() {
        return new String[]{"/system/bin/pm", "list", "users"};
    }

    public static String[] createUserCommand(String name) {
        return new String[]{"/system/bin/pm", "create-user", name};
    }

    public static String[] removeUserCommand(int userId) {
        return new String[]{"/system/bin/pm", "remove-user", String.valueOf(userId)};
    }

    public static String[] currentUserCommand() {
        return new String[]{"/system/bin/am", "get-current-user"};
    }

    /** User names: 1–40 chars, no newlines, no leading dash
     *  (which would read as a flag). */
    public static boolean validUserName(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty() || n.length() > 40 || n.startsWith("-")) return false;
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch < 0x20 || ch == 0x7f) return false;
        }
        return true;
    }

    /** One user from `pm list users`. */
    public static final class UserInfo {
        public final int id;
        public final String name;
        public final boolean running;

        UserInfo(int id, String name, boolean running) {
            this.id = id;
            this.name = name;
            this.running = running;
        }
    }

    private static final Pattern USER_LINE = Pattern.compile(
            "UserInfo\\{(\\d+):([^:}]*):(\\d+)\\}\\s*(running)?");

    /** Parse `pm list users`: "UserInfo{0:Owner:13} running"
     *  lines. Unparseable lines are skipped, never guessed. */
    public static List<UserInfo> parseUsers(String output) {
        List<UserInfo> out = new ArrayList<>();
        if (output == null) return out;
        Matcher m = USER_LINE.matcher(output);
        while (m.find()) {
            try {
                int id = Integer.parseInt(m.group(1));
                String name = m.group(2).trim();
                boolean running = m.group(4) != null;
                out.add(new UserInfo(id, name.isEmpty() ? ("User " + id) : name, running));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static final Pattern CREATE_USER_OK =
            Pattern.compile("created user id (\\d+)", Pattern.CASE_INSENSITIVE);

    /** The new user id from `pm create-user` output
     *  ("Success: created user id 10"), or -1. */
    public static int parseCreateUser(String output) {
        if (output == null) return -1;
        Matcher m = CREATE_USER_OK.matcher(output);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    /** Parse `am get-current-user`: a bare integer, or -1. */
    public static int parseCurrentUser(String output) {
        if (output == null) return -1;
        try {
            return Integer.parseInt(output.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The removal refusal: user 0 (the owner) is never
     * removable, and neither is the currently-running user.
     * Returns the plain-words reason, or null when removal
     * may proceed. A current-user id of -1 (unreadable) only
     * refuses user 0 — Android itself refuses removing the
     * current user, as the backstop.
     */
    public static String refuseRemoveUser(int userId, int currentUserId) {
        if (userId == 0) {
            return "Refused: user 0 is the owner — it cannot be removed.";
        }
        if (currentUserId >= 0 && userId == currentUserId) {
            return "Refused: user " + userId + " is the currently-running user.";
        }
        return null;
    }

    /** Remove succeeds only on exit 0 plus pm's "Success". */
    public static Outcome verifyRemoveUser(String output, int exitCode) {
        String out = output == null ? "" : output;
        if (exitCode == 0 && out.contains("Success") && !out.contains("Failure")) {
            return new Outcome(true, firstLine(out));
        }
        return fail("remove user", out);
    }

    // ---- launch any activity (non-exported included) ----

    /** Resolve a possibly-relative class name against its
     *  package (".Foo" -> "com.pkg.Foo"); fully-qualified
     *  names pass through. */
    public static String resolveClassName(String pkg, String cls) {
        if (cls == null) return "";
        if (cls.startsWith(".")) return (pkg == null ? "" : pkg) + cls;
        return cls;
    }

    /** A fully-qualified class name: dot-separated Java
     *  identifier segments (inner classes keep '$'). */
    public static boolean validClassName(String cls) {
        if (cls == null || cls.isEmpty() || cls.length() > 512) return false;
        if (cls.startsWith(".") || cls.endsWith(".")) return false;
        for (String segment : cls.split("\\.", -1)) {
            if (!CLASS_SEGMENT.matcher(segment).matches()) return false;
        }
        return true;
    }

    /** {@code am start -n <pkg>/<cls>}: the shell user may
     *  start activities Android hides from other apps. */
    public static String[] launchActivityCommand(String pkg, String cls) {
        return new String[]{"/system/bin/am", "start", "-n", pkg + "/" + cls};
    }

    /** am start prints "Starting: Intent {...}" on success;
     *  a failure prints an Error/Exception instead. When the
     *  activity is ALREADY the top-most one, am start prints
     *  no Starting line — it prints a Warning that the intent
     *  "has been delivered to currently running top-most
     *  instance". That means the activity came forward, so it
     *  counts as success too (v1.38: the old verifier reported
     *  it as a refusal, and the reason toast cut the words
     *  off). Error/Exception markers still fail, in either
     *  shape. */
    public static Outcome verifyAmStart(String output, int exitCode) {
        String out = output == null ? "" : output;
        String low = out.toLowerCase(Locale.ROOT);
        if (exitCode == 0 && !low.contains("error") && !low.contains("exception")) {
            if (out.contains("Starting: Intent")) {
                return new Outcome(true, firstLine(out));
            }
            if (low.contains("delivered to currently running top-most instance")) {
                return new Outcome(true, "Already running — brought to the front: "
                        + firstLine(out));
            }
        }
        return fail("launch", out);
    }

    // ---- dumpsys: fixed service menu, never the whole dump ----

    /** The dumpsys services the Inspect section offers. A
     *  bare `dumpsys` (everything) is deliberately not an
     *  option: the output is far too large for the response
     *  channel and for a phone screen. */
    public static final String[] DUMPSYS_SERVICES = {
            "activity", "window", "battery", "wifi", "notification",
            "power", "package", "meminfo", "usagestats", "deviceidle",
            "jobscheduler",
    };

    public static boolean validDumpsysService(String service) {
        if (service == null) return false;
        for (String allowed : DUMPSYS_SERVICES) {
            if (allowed.equals(service)) return true;
        }
        return false;
    }

    public static String[] dumpsysCommand(String service) {
        return new String[]{"/system/bin/dumpsys", service};
    }

    /** Package-name shape, shared with the bridge's own
     *  check: the client validates before sending, the
     *  bridge validates again before running. */
    public static boolean validPackage(String pkg) {
        return pkg != null && PACKAGE_PATTERN.matcher(pkg).matches();
    }

    // ---- shared shapes: coordinates, wm state, long tasks ----

    // ---- appops: read the current mode for one op ----

    /** AppOp modes, the same allowlist the bridge enforces on
     *  writes. */
    public static final String[] APP_OP_MODE_WORDS = {
            "allow", "ignore", "deny", "default", "foreground"};

    /**
     * The current mode for one AppOp from {@code cmd appops get
     * <pkg>} output, or null when the output does not carry
     * it. Lines read {@code OP: mode; ...} (with optional
     * leading whitespace) or {@code Uid mode: OP: mode} for
     * UID-level entries; the first matching line wins, and a
     * mode word outside the allowlist is null, never guessed.
     * This is the before-value read for AppOp journaling — a
     * null here means the change is recorded with an unknown
     * previous value and no restore.
     */
    public static String parseAppOpMode(String output, String op) {
        if (output == null || op == null || op.isEmpty()) return null;
        for (String rawLine : output.split("\\n")) {
            String line = rawLine.trim();
            if (line.startsWith("Uid mode:")) {
                line = line.substring("Uid mode:".length()).trim();
            }
            if (!line.startsWith(op + ":")) continue;
            String rest = line.substring(op.length() + 1).trim();
            int end = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char ch = rest.charAt(i);
                if (ch == ';' || ch == ' ' || ch == '\t') {
                    end = i;
                    break;
                }
            }
            String mode = rest.substring(0, end);
            for (String allowed : APP_OP_MODE_WORDS) {
                if (allowed.equals(mode)) return mode;
            }
            return null;
        }
        return null;
    }

    /** The bridge-side sanity bound for injected coordinates:
     *  far above any real display, far below nonsense. The
     *  app validates against the real display size; this is
     *  the backstop for anything that reaches the bridge. */
    public static final int COORD_SANITY_MAX = 8192;

    /** The display state the WM_GET op returns: physical and
     *  override values for size and density, plus the
     *  overscan tuple. Null fields mean "could not be read"
     *  — never guessed. */
    public static final class WmState {
        public final String sizePhysical;
        public final String sizeOverride;
        public final String densityPhysical;
        public final String densityOverride;
        public final String overscan;

        WmState(String sizePhysical, String sizeOverride,
                String densityPhysical, String densityOverride,
                String overscan) {
            this.sizePhysical = sizePhysical;
            this.sizeOverride = sizeOverride;
            this.densityPhysical = densityPhysical;
            this.densityOverride = densityOverride;
            this.overscan = overscan;
        }

        /** True when at least one override is in force. */
        public boolean hasOverride() {
            return (sizeOverride != null && !sizeOverride.isEmpty())
                    || (densityOverride != null && !densityOverride.isEmpty())
                    || (overscan != null && !"0,0,0,0".equals(overscan));
        }
    }

    /** Compose the WM_GET response text from the three parsed
     *  readouts: one key=value line each, empty value when a
     *  readout could not be parsed. */
    public static String composeWmState(WmValue size, WmValue density, WmValue overscan) {
        StringBuilder out = new StringBuilder();
        out.append("sizePhysical=").append(size == null ? "" : emptyForNull(size.physical));
        out.append("\nsizeOverride=").append(size == null ? "" : emptyForNull(size.override));
        out.append("\ndensityPhysical=").append(density == null ? "" : emptyForNull(density.physical));
        out.append("\ndensityOverride=").append(density == null ? "" : emptyForNull(density.override));
        out.append("\noverscan=").append(overscan == null ? "" : emptyForNull(overscan.physical));
        return out.toString();
    }

    private static String emptyForNull(String value) {
        return value == null ? "" : value;
    }

    /** Parse a WM_GET response back into a WmState. Empty
     *  values become null ("could not be read"). */
    public static WmState parseWmState(String text) {
        Map<String, String> values = parseKeyValues(text);
        return new WmState(
                nullForEmpty(values.get("sizePhysical")),
                nullForEmpty(values.get("sizeOverride")),
                nullForEmpty(values.get("densityPhysical")),
                nullForEmpty(values.get("densityOverride")),
                nullForEmpty(values.get("overscan")));
    }

    private static String nullForEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** One "key=value" line per entry; lines without '=' are
     *  ignored, and the first '=' splits key from value. */
    public static Map<String, String> parseKeyValues(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    /** The status of a start-and-poll task (screen record,
     *  bugreport): running or finished, the file path, and
     *  the file size in bytes (-1 when unknown). */
    public static final class LongTaskStatus {
        /** "RUNNING", "FINISHED", or "NONE". */
        public final String state;
        public final String path;
        public final long size;

        LongTaskStatus(String state, String path, long size) {
            this.state = state;
            this.path = path;
            this.size = size;
        }

        public boolean isRunning() {
            return "RUNNING".equals(state);
        }

        public boolean exists() {
            return !"NONE".equals(state);
        }
    }

    /** Parse a long-task status response: first line the
     *  state word, then key=value lines (path, size). */
    public static LongTaskStatus parseLongTaskStatus(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new LongTaskStatus("NONE", null, -1);
        }
        String[] lines = text.split("\\n", 2);
        String state = lines[0].trim();
        if (!"RUNNING".equals(state) && !"FINISHED".equals(state)) {
            state = "NONE";
        }
        Map<String, String> values = lines.length > 1
                ? parseKeyValues(lines[1]) : new LinkedHashMap<String, String>();
        String path = nullForEmpty(values.get("path"));
        long size = -1;
        String rawSize = values.get("size");
        if (rawSize != null) {
            try {
                size = Long.parseLong(rawSize);
            } catch (Exception ignored) {
            }
        }
        return new LongTaskStatus(state, path, size);
    }

    /** Compose a long-task status response. */
    public static String composeLongTaskStatus(String state, String path, long size) {
        return state + "\npath=" + (path == null ? "" : path)
                + "\nsize=" + size;
    }

    /** Human size for status lines: "1.2 MB", "340 KB", or
     *  "unknown size" when the file could not be measured. */
    public static String humanSize(long bytes) {
        if (bytes < 0) return "unknown size";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
