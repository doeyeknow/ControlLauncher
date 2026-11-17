package app.control.launcher;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * App-side governor for bridge writes.
 *
 * Every public method is one named, audited attempt. The bridge repeats
 * validation authoritatively; this layer gives the user confirmation
 * context, refuses obvious protected targets before IPC, and writes the
 * shared audit record even when the bridge is down or refuses.
 */
public final class BridgeControl {
    public static final String ORIGIN_UI = "UI";
    public static final String ORIGIN_SMS = "SMS";
    public static final String ORIGIN_FILTER = "FILTER";

    public static final String FILTER_DNS_HOSTNAME = "dns.adguard-dns.com";

    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");

    public static final Set<String> REFUSE_ALL = new HashSet<>(Arrays.asList(
            "app.control.launcher"));

    public static final Set<String> REFUSE_DESTRUCTIVE = new HashSet<>(Arrays.asList(
             "com.android.providers.userdictionary"));

    public static final String[] PERMISSION_LABELS = {
            "Camera", "Microphone", "Precise location", "Approximate location",
            "Notifications", "Read SMS", "Send SMS", "Receive SMS", "Read contacts",
            "Write contacts", "Read calendar", "Write calendar", "Body sensors",
            "Read call log", "Phone state", "Phone calls", "Read images", "Read video",
            "Read audio", "Legacy external storage"
    };
    public static final String[] PERMISSIONS = {
            "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS", "android.permission.READ_SMS",
            "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
            "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
            "android.permission.BODY_SENSORS", "android.permission.READ_CALL_LOG",
            "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_EXTERNAL_STORAGE"
    };
    public static final String[] APP_OP_LABELS = {
            "Camera", "Microphone", "Precise location", "Approximate location",
            "Notifications", "Read SMS", "Send SMS", "Receive SMS", "Read contacts",
            "Read calendar", "Body sensors", "Read call log", "Phone state", "Phone calls",
            "Wake lock", "Monitor location", "Usage stats", "Exact alarms"
    };
    public static final String[] APP_OPS = {
            "CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION",
            "POST_NOTIFICATION", "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS",
            "READ_CALENDAR", "BODY_SENSORS", "READ_CALL_LOG", "READ_PHONE_STATE",
            "CALL_PHONE", "WAKE_LOCK", "MONITOR_LOCATION", "GET_USAGE_STATS",
            "SCHEDULE_EXACT_ALARM"
    };
    public static final String[] APP_OP_MODES = {"allow", "ignore", "deny", "default", "foreground"};

    private BridgeControl() {}

    public static ShellBridgeClient.Response grantPermission(
            Context c, String origin, String pkg, String permission) {
        return executePackage(c, origin, "GRANT_PERMISSION", pkg,
                "permission=" + permission, false,
                () -> ShellBridgeClient.grantPermission(c, pkg, permission));
    }

    public static ShellBridgeClient.Response revokePermission(
            Context c, String origin, String pkg, String permission) {
        return executePackage(c, origin, "REVOKE_PERMISSION", pkg,
                "permission=" + permission, false,
                () -> ShellBridgeClient.revokePermission(c, pkg, permission));
    }

    public static ShellBridgeClient.Response setAppOp(
            Context c, String origin, String pkg, String appOp, String mode) {
        return executePackage(c, origin, "SET_APP_OP", pkg,
                "op=" + appOp + ", mode=" + mode, false,
                () -> ShellBridgeClient.setAppOp(c, pkg, appOp, mode));
    }

    public static ShellBridgeClient.Response forceStop(Context c, String origin, String pkg) {
        return executePackage(c, origin, "FORCE_STOP", pkg, "", true,
                () -> ShellBridgeClient.forceStop(c, pkg));
    }

    public static ShellBridgeClient.Response setAppEnabled(
            Context c, String origin, String pkg, boolean enabled) {
        // v1.36: Control itself is refused app-side too (the
        // bridge enforces it as well) — and the refusal is
        // audited like any other attempt, without a bridge
        // call that could never succeed.
        if (ProtectedOverride.isOwnPackage(pkg)) {
            ShellBridgeClient.Response r = new ShellBridgeClient.Response(
                    false, "", ProtectedOverride.lockoutReason(), 0, 0, "", 0);
            audit(c, origin, "SET_APP_ENABLED", pkg, "enabled=" + enabled, r);
            return r;
        }
        boolean override = ProtectedOverride.overrideApplies(pkg,
                Prefs.get(c).getBoolean(Prefs.KEY_PROTECTED_OVERRIDE, false));
        return executePackage(c, origin, "SET_APP_ENABLED", pkg,
                "enabled=" + enabled
                        + (override ? ", " + ProtectedOverride.AUDIT_MARKER : ""),
                true,
                () -> ShellBridgeClient.setAppEnabled(c, pkg, enabled, override));
    }

    /** Audit the protected-package switch flipping, with no
     *  bridge call: the switch is a client-side pref, but
     *  turning the override on is a governed act and the log
     *  must show who did it and when. */
    public static void logOverrideToggle(Context c, String origin, boolean enabled) {
        ShellBridgeClient.Response r = new ShellBridgeClient.Response(true,
                enabled ? "Protected-package override enabled."
                        : "Protected-package override disabled.",
                "", 0, 0, "", 0);
        audit(c, origin, "PROTECTED_OVERRIDE", "Protected packages",
                "override=" + (enabled ? "on" : "off"), r);
    }
    public static ShellBridgeClient.Response clearAppData(Context c, String origin, String pkg) {
        return executePackage(c, origin, "CLEAR_APP_DATA", pkg, "", true,
                () -> ShellBridgeClient.clearAppData(c, pkg));
    }

    public static ShellBridgeClient.Response setBackgroundData(
            Context c, String origin, String pkg, boolean allowed) {
        return executePackage(c, origin, "SET_BACKGROUND_DATA", pkg,
                "allowed=" + allowed, false,
                () -> ShellBridgeClient.setBackgroundData(c, pkg, allowed));
    }

    public static ShellBridgeClient.Response setDeviceIdleWhitelist(
            Context c, String origin, String pkg, boolean whitelisted) {
        return executePackage(c, origin, "SET_DEVICE_IDLE_WHITELIST", pkg,
                "whitelisted=" + whitelisted, false,
                () -> ShellBridgeClient.setDeviceIdleWhitelist(c, pkg, whitelisted));
    }

    public static ShellBridgeClient.Response setPrivateDns(
            Context c, String origin, String mode, String hostname) {
        String params = "mode=" + mode + (hostname == null || hostname.isEmpty()
                ? "" : ", hostname=" + hostname);
        ShellBridgeClient.Response r = ShellBridgeClient.setPrivateDns(c, mode, hostname);
        audit(c, origin, "SET_PRIVATE_DNS", "Private DNS", params, r);
        return r;
    }

    /** Pre-declared FILTER mapping: FILTER ON may set the recommended
     *  filtering resolver through an already-running bridge. If the
     *  bridge is down this is a no-op, not a failed filter command. */
    public static String trySetFilterDns(Context c, String origin) {
        ShellBridgeClient.Response ping = ShellBridgeClient.ping(c);
        if (!ping.ok) return "bridge off; Private DNS unchanged";
        ShellBridgeClient.Response r = setPrivateDns(c, origin, "hostname", FILTER_DNS_HOSTNAME);
        return r.ok ? "Private DNS set to " + FILTER_DNS_HOSTNAME + " by the bridge"
                : "Private DNS bridge write refused: " + r.error;
    }

    // ---- governed radio writes (Connections screen) ----
    //
    // Radios have no package target, so these audit directly like
    // setPrivateDns does: the attempt is logged whether the bridge
    // accepts, refuses, or is down.

    public static ShellBridgeClient.Response setWifi(
            Context c, String origin, boolean enabled) {
        ShellBridgeClient.Response r = ShellBridgeClient.setWifi(c, enabled);
        audit(c, origin, "SET_WIFI", "Wi-Fi radio", "enabled=" + enabled, r);
        return r;
    }

    public static ShellBridgeClient.Response setMobileData(
            Context c, String origin, boolean enabled) {
        ShellBridgeClient.Response r = ShellBridgeClient.setMobileData(c, enabled);
        audit(c, origin, "SET_MOBILE_DATA", "Mobile data", "enabled=" + enabled, r);
        return r;
    }

    public static ShellBridgeClient.Response setBluetooth(
            Context c, String origin, boolean enabled) {
        ShellBridgeClient.Response r = ShellBridgeClient.setBluetooth(c, enabled);
        audit(c, origin, "SET_BLUETOOTH", "Bluetooth radio", "enabled=" + enabled, r);
        return r;
    }

    public static ShellBridgeClient.Response setNfc(
            Context c, String origin, boolean enabled) {
        ShellBridgeClient.Response r = ShellBridgeClient.setNfc(c, enabled);
        audit(c, origin, "SET_NFC", "NFC radio", "enabled=" + enabled, r);
        return r;
    }

    public static ShellBridgeClient.Response setAirplaneMode(
            Context c, String origin, boolean enabled) {
        ShellBridgeClient.Response r = ShellBridgeClient.setAirplaneMode(c, enabled);
        audit(c, origin, "SET_AIRPLANE_MODE", "Airplane mode", "enabled=" + enabled, r);
        return r;
    }

    public static ShellBridgeClient.Response setLauncherSetting(
            Context c, String origin, String setting, String value) {
        ShellBridgeClient.Response r = ShellBridgeClient.setLauncherSetting(c, setting, value);
        audit(c, origin, "SET_LAUNCHER_SETTING", setting, "value=" + value, r);
        return r;
    }

    // ---- v1.35 bridge expansion: package-targeted writes ----

    public static ShellBridgeClient.Response uninstallUser(Context c, String origin, String pkg) {
        return executePackage(c, origin, "UNINSTALL_USER", pkg, "user=0", true,
                () -> ShellBridgeClient.uninstallUser(c, pkg));
    }

    public static ShellBridgeClient.Response installExisting(Context c, String origin, String pkg) {
        return executePackage(c, origin, "INSTALL_EXISTING", pkg, "user=0", false,
                () -> ShellBridgeClient.installExisting(c, pkg));
    }

    public static ShellBridgeClient.Response suspendApp(
            Context c, String origin, String pkg, boolean suspend) {
        return executePackage(c, origin, suspend ? "SUSPEND_APP" : "UNSUSPEND_APP", pkg,
                "suspend=" + suspend, false,
                () -> ShellBridgeClient.suspendApp(c, pkg, suspend));
    }

    public static ShellBridgeClient.Response standbySet(
            Context c, String origin, String pkg, String bucket) {
        return executePackage(c, origin, "STANDBY_SET", pkg,
                "bucket=" + bucket, false,
                () -> ShellBridgeClient.standbySet(c, pkg, bucket));
    }

    public static ShellBridgeClient.Response runJob(
            Context c, String origin, String pkg, String jobId) {
        return executePackage(c, origin, "RUN_JOB", pkg,
                "jobId=" + jobId, false,
                () -> ShellBridgeClient.runJob(c, pkg, jobId));
    }

    public static ShellBridgeClient.Response launchActivity(
            Context c, String origin, String pkg, String cls) {
        ShellBridgeClient.Response r = ShellBridgeClient.launchActivity(c, pkg, cls);
        audit(c, origin, "LAUNCH_ACTIVITY", pkg, "class=" + cls, r);
        return r;
    }

    // ---- v1.35 bridge expansion: device-wide writes ----

    public static ShellBridgeClient.Response installApk(
            Context c, String origin, String path, String pkg) {
        ShellBridgeClient.Response r = ShellBridgeClient.installApk(c, path);
        audit(c, origin, "INSTALL_APK", pkg == null ? "(staged APK)" : pkg,
                "staged install", r);
        return r;
    }

    public static ShellBridgeClient.Response trimCaches(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.trimCaches(c);
        audit(c, origin, "TRIM_CACHES", "all apps", "target=1GB", r);
        return r;
    }

    public static ShellBridgeClient.Response wmSetSize(
            Context c, String origin, int width, int height) {
        ShellBridgeClient.Response r = ShellBridgeClient.wmSetSize(c, width, height);
        audit(c, origin, "WM_SET_SIZE", "display", "size=" + width + "x" + height, r);
        return r;
    }

    public static ShellBridgeClient.Response wmSetDensity(
            Context c, String origin, int density) {
        ShellBridgeClient.Response r = ShellBridgeClient.wmSetDensity(c, density);
        audit(c, origin, "WM_SET_DENSITY", "display", "density=" + density, r);
        return r;
    }

    public static ShellBridgeClient.Response wmSetOverscan(
            Context c, String origin, int left, int top, int right, int bottom) {
        ShellBridgeClient.Response r = ShellBridgeClient.wmSetOverscan(c, left, top, right, bottom);
        audit(c, origin, "WM_SET_OVERSCAN", "display",
                "overscan=" + left + "," + top + "," + right + "," + bottom, r);
        return r;
    }

    public static ShellBridgeClient.Response settingPut(
            Context c, String origin, String namespace, String key, String value) {
        ShellBridgeClient.Response r = ShellBridgeClient.settingPut(c, namespace, key, value);
        audit(c, origin, "SETTING_PUT", namespace + "/" + key, "value=" + value, r);
        return r;
    }

    public static ShellBridgeClient.Response nightSet(
            Context c, String origin, String mode) {
        ShellBridgeClient.Response r = ShellBridgeClient.nightSet(c, mode);
        audit(c, origin, "NIGHT_SET", "night mode", "mode=" + mode, r);
        return r;
    }

    public static ShellBridgeClient.Response inputKey(
            Context c, String origin, int code, String label) {
        ShellBridgeClient.Response r = ShellBridgeClient.inputKey(c, code);
        audit(c, origin, "INPUT_KEY", "key event", "key=" + label, r);
        return r;
    }

    public static ShellBridgeClient.Response inputTap(
            Context c, String origin, int x, int y) {
        ShellBridgeClient.Response r = ShellBridgeClient.inputTap(c, x, y);
        audit(c, origin, "INPUT_TAP", "touch", "at=" + x + "," + y, r);
        return r;
    }

    public static ShellBridgeClient.Response inputSwipe(
            Context c, String origin, int x1, int y1, int x2, int y2, int durationMs) {
        ShellBridgeClient.Response r = ShellBridgeClient.inputSwipe(c, x1, y1, x2, y2, durationMs);
        audit(c, origin, "INPUT_SWIPE", "touch",
                "from=" + x1 + "," + y1 + " to=" + x2 + "," + y2 + " ms=" + durationMs, r);
        return r;
    }

    /** The typed text itself is NEVER audited — only that text
     *  was typed and its length (it may be a password). The
     *  bridge's response carries the same length-only wording. */
    public static ShellBridgeClient.Response inputText(
            Context c, String origin, String escaped) {
        ShellBridgeClient.Response r = ShellBridgeClient.inputText(c, escaped);
        audit(c, origin, "INPUT_TEXT", "text entry",
                "length=" + (escaped == null ? 0 : escaped.length()), r);
        return r;
    }

    public static ShellBridgeClient.Response screenshot(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.screenshot(c);
        audit(c, origin, "SCREENSHOT", "screen", "", r);
        return r;
    }

    public static ShellBridgeClient.Response screenrecordStart(
            Context c, String origin, int seconds) {
        ShellBridgeClient.Response r = ShellBridgeClient.screenrecordStart(c, seconds);
        audit(c, origin, "SCREENRECORD_START", "screen recording", "seconds=" + seconds, r);
        return r;
    }

    public static ShellBridgeClient.Response screenrecordStop(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.screenrecordStop(c);
        audit(c, origin, "SCREENRECORD_STOP", "screen recording", "", r);
        return r;
    }

    public static ShellBridgeClient.Response bugreportStart(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.bugreportStart(c);
        audit(c, origin, "BUGREPORT_START", "bugreport", "", r);
        return r;
    }

    public static ShellBridgeClient.Response bugreportStop(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.bugreportStop(c);
        audit(c, origin, "BUGREPORT_STOP", "bugreport", "", r);
        return r;
    }

    public static ShellBridgeClient.Response doze(
            Context c, String origin, String action) {
        ShellBridgeClient.Response r = ShellBridgeClient.doze(c, action);
        audit(c, origin, "DOZE", "device idle", "action=" + action, r);
        return r;
    }

    public static ShellBridgeClient.Response batterySetLevel(
            Context c, String origin, int level) {
        ShellBridgeClient.Response r = ShellBridgeClient.batterySetLevel(c, level);
        audit(c, origin, "BATTERY_SET_LEVEL", "battery", "level=" + level, r);
        return r;
    }

    public static ShellBridgeClient.Response batteryUnplug(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.batteryUnplug(c);
        audit(c, origin, "BATTERY_UNPLUG", "battery", "", r);
        return r;
    }

    public static ShellBridgeClient.Response batteryReset(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.batteryReset(c);
        audit(c, origin, "BATTERY_RESET", "battery", "", r);
        return r;
    }

    public static ShellBridgeClient.Response deviceConfigPut(
            Context c, String origin, String namespace, String key, String value) {
        ShellBridgeClient.Response r = ShellBridgeClient.deviceConfigPut(c, namespace, key, value);
        audit(c, origin, "DEVICE_CONFIG_PUT", namespace + "/" + key, "value=" + value, r);
        return r;
    }

    public static ShellBridgeClient.Response wifiForget(
            Context c, String origin, String networkId, String ssid) {
        ShellBridgeClient.Response r = ShellBridgeClient.wifiForget(c, networkId);
        audit(c, origin, "WIFI_FORGET", ssid == null ? "network " + networkId : ssid,
                "networkId=" + networkId, r);
        return r;
    }

    public static ShellBridgeClient.Response userCreate(
            Context c, String origin, String name) {
        ShellBridgeClient.Response r = ShellBridgeClient.userCreate(c, name);
        audit(c, origin, "USER_CREATE", "user", "name=" + name, r);
        return r;
    }

    public static ShellBridgeClient.Response userRemove(
            Context c, String origin, int userId, String name) {
        ShellBridgeClient.Response r = ShellBridgeClient.userRemove(c, userId);
        audit(c, origin, "USER_REMOVE", "user " + userId,
                "name=" + (name == null ? "" : name), r);
        return r;
    }

    public static ShellBridgeClient.Response stopBridge(Context c, String origin) {
        ShellBridgeClient.Response r = ShellBridgeClient.stopBridge(c);
        audit(c, origin, "STOP_BRIDGE", "Control shell bridge", "", r);
        if (r.ok) {
            Prefs.get(c).edit().putString(Prefs.KEY_BRIDGE_STATE,
                    "off — turned off from Control settings").apply();
        }
        return r;
    }

    private interface Call {
        ShellBridgeClient.Response run();
    }

    private static ShellBridgeClient.Response executePackage(
            Context c, String origin, String action, String pkg, String params,
            boolean destructive, Call call) {
        String refusal = validatePackage(c, pkg, destructive);
        ShellBridgeClient.Response r;
        
            r = call.run();
        
        audit(c, origin, action, pkg, params, r);
        return r;
    }

    private static String validatePackage(Context c, String pkg, boolean destructive) {
        if (pkg == null || !PACKAGE_PATTERN.matcher(pkg).matches()) {
            return "Refused by Control: invalid package name.";
        }
      
        
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "Refused by Control: " + pkg + " is not installed.";
        } catch (Exception e) {
            return "Refused by Control: could not verify " + pkg + ".";
        }
        return null;
    }

    private static void audit(Context c, String origin, String action, String target,
                              String parameters, ShellBridgeClient.Response r) {
        String result = r.ok ? (r.text.isEmpty() ? "Success" : r.text)
                : "Refused/failed: " + r.error;
        SmsControl.logDetailed(c, origin, "", action, target, parameters, result);
        OwnLog.log(c, "Bridge control", action + " → " + target + ": "
                + (r.ok ? "success" : result));
    }
}
