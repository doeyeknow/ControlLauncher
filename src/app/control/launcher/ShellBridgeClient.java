package app.control.launcher;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** App-side client for Control's own shell bridge. */
public final class ShellBridgeClient {
    public static final class Response {
        public final boolean ok;
        public final String text;
        public final String error;
        public final int bridgeUid;
        public final int bridgePid;
        public final String transport;
        /** Bridge protocol version from the response (v1.15.1).
         *  0 when the bridge did not report one (too old, or no
         *  bridge at all) — 0 is stale by definition, see
         *  BridgeGate. */
        public final int bridgeVersion;
        /** Binary payload (v1.35): the SCREENSHOT op returns
         *  its PNG as base64 here, outside the capped text
         *  field. Empty for every other op. */
        public final String data;

        Response(boolean ok, String text, String error, int bridgeUid,
                 int bridgePid, String transport, int bridgeVersion) {
            this(ok, text, error, bridgeUid, bridgePid, transport, bridgeVersion, "");
        }

        Response(boolean ok, String text, String error, int bridgeUid,
                 int bridgePid, String transport, int bridgeVersion, String data) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.error = error == null ? "" : error;
            this.bridgeUid = bridgeUid;
            this.bridgePid = bridgePid;
            this.transport = transport == null ? "" : transport;
            this.bridgeVersion = bridgeVersion;
            this.data = data == null ? "" : data;
        }
    }

    private ShellBridgeClient() {}

    public static String token(Context c) {
        String token = Prefs.get(c).getString(Prefs.KEY_BRIDGE_TOKEN, "");
        if (token != null && token.length() >= 32) return token;
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        token = out.toString();
        Prefs.get(c).edit().putString(Prefs.KEY_BRIDGE_TOKEN, token).apply();
        return token;
    }

    /** The shell line itself (no adb wrapper): what runs ON the phone,
     *  as the shell user, to start the bridge. The in-app ADB client
     *  (AdbStarter, v1.1) runs exactly this through the shell service.
     *
     *  v1.3 hardening (field report: pairing worked on Android 16 but
     *  the bridge never answered — see BUILD_NOTES):
     *  - the classpath is built from EVERY line of `pm path` output
     *    joined with ':' — the old `cut -d: -f2` left newline-joined
     *    paths in one variable on split installs, which app_process
     *    cannot load (class not found, process dies instantly);
     *  - the detach is completed: nohup + setsid + stdin from
     *    /dev/null + stdout/stderr to the log, backgrounded — so the
     *    bridge survives the ADB shell session that launched it
     *    closing, which is how the in-app client always launches it. */
    public static String shellStarterCommand(Context c) {
        return "CP=$(pm path app.control.launcher | sed \"s/^package://\" "
                + "| tr \"\\n\" \":\"); CP=${CP%:}; "
                + "APP_UID=$(cmd package list packages -U app.control.launcher | sed \"s/.*uid://\"); "
                + "CLASSPATH=\"$CP\" nohup setsid app_process /system/bin "
                + "app.control.launcher.ShellBridgeMain \"$APP_UID\" \"" + token(c)
                + "\" >/data/local/tmp/control-bridge.log 2>&1 </dev/null &";
    }

    /** One line the owner can run from a computer connected by adb. */
    public static String starterCommand(Context c) {
        return "adb shell '" + shellStarterCommand(c) + "'";
    }

    public static Response ping(Context c) {
        return request(c, "PING", null);
    }

    /** Hold-watch diagnostics (v10): the picked device plus
     *  frame/hold counts, as JSON in the response text. A v9
     *  bridge refuses with the allowlist error; the caller
     *  treats that as "no bridge-side data". */
    public static Response holdWatchStatus(Context c) {
        return request(c, "HOLD_WATCH_STATUS", null);
    }

    public static Response processes(Context c) {
        return request(c, "PROCESSES", null);
    }

    public static Response processesDetail(Context c) {
        return request(c, "PROCESSES_DETAIL", null);
    }

    public static Response services(Context c) {
        return request(c, "SERVICES", null);
    }

    public static Response serviceList(Context c) {
        return request(c, "SERVICE_LIST", null);
    }

    public static Response logs(Context c, String query, int maxLines) {
        JSONObject params = new JSONObject();
        try {
            params.put("query", query == null ? "" : query);
            params.put("maxLines", maxLines);
        } catch (Exception ignored) {
        }
        return request(c, "LOGS", params);
    }

    public static Response grantPermission(Context c, String pkg, String permission) {
        return packagePermission(c, "GRANT_PERMISSION", pkg, permission);
    }

    public static Response revokePermission(Context c, String pkg, String permission) {
        return packagePermission(c, "REVOKE_PERMISSION", pkg, permission);
    }

    private static Response packagePermission(Context c, String op, String pkg,
                                              String permission) {
        JSONObject params = new JSONObject();
        try {
            params.put("package", pkg);
            params.put("permission", permission);
        } catch (Exception ignored) {
        }
        return request(c, op, params);
    }

    public static Response setAppOp(Context c, String pkg, String appOp, String mode) {
        JSONObject params = new JSONObject();
        try {
            params.put("package", pkg);
            params.put("appOp", appOp);
            params.put("mode", mode);
        } catch (Exception ignored) {
        }
        return request(c, "SET_APP_OP", params);
    }

    public static Response forceStop(Context c, String pkg) {
        return packageOnly(c, "FORCE_STOP", pkg);
    }

    public static Response clearAppData(Context c, String pkg) {
        return packageOnly(c, "CLEAR_APP_DATA", pkg);
    }

    public static Response setAppEnabled(Context c, String pkg, boolean enabled) {
        return setAppEnabled(c, pkg, enabled, false);
    }

    /** SET_APP_ENABLED with the v7 protected-package override
     *  flag. The flag rides as an ordinary boolean field: an
     *  old bridge ignores unknown fields and refuses protected
     *  packages, so sending it to a v6 bridge fails closed. */
    public static Response setAppEnabled(Context c, String pkg, boolean enabled,
                                         boolean override) {
        JSONObject params = packageParams(pkg);
        try {
            params.put("enabled", enabled);
            params.put("override", override);
        } catch (Exception ignored) {
        }
        return request(c, "SET_APP_ENABLED", params);
    }

    public static Response setBackgroundData(Context c, String pkg, boolean allowed) {
        JSONObject params = packageParams(pkg);
        try {
            params.put("allowed", allowed);
        } catch (Exception ignored) {
        }
        return request(c, "SET_BACKGROUND_DATA", params);
    }

    public static Response setDeviceIdleWhitelist(Context c, String pkg, boolean whitelisted) {
        JSONObject params = packageParams(pkg);
        try {
            params.put("whitelisted", whitelisted);
        } catch (Exception ignored) {
        }
        return request(c, "SET_DEVICE_IDLE_WHITELIST", params);
    }

    public static Response setPrivateDns(Context c, String mode, String hostname) {
        JSONObject params = new JSONObject();
        try {
            params.put("mode", mode);
            params.put("hostname", hostname == null ? "" : hostname);
        } catch (Exception ignored) {
        }
        return request(c, "SET_PRIVATE_DNS", params);
    }

    public static Response setLauncherSetting(Context c, String setting, String value) {
        JSONObject params = new JSONObject();
        try {
            params.put("setting", setting);
            params.put("value", value);
        } catch (Exception ignored) {
        }
        return request(c, "SET_LAUNCHER_SETTING", params);
    }

    public static Response stopBridge(Context c) {
        return request(c, "STOP_BRIDGE", null);
    }

    // ---- governed radio writes (Connections screen, bridge tier) ----

    public static Response setWifi(Context c, boolean enabled) {
        return radio(c, "SET_WIFI", enabled);
    }

    public static Response setMobileData(Context c, boolean enabled) {
        return radio(c, "SET_MOBILE_DATA", enabled);
    }

    public static Response setBluetooth(Context c, boolean enabled) {
        return radio(c, "SET_BLUETOOTH", enabled);
    }

    public static Response setNfc(Context c, boolean enabled) {
        return radio(c, "SET_NFC", enabled);
    }

    public static Response setAirplaneMode(Context c, boolean enabled) {
        return radio(c, "SET_AIRPLANE_MODE", enabled);
    }

    private static Response radio(Context c, String op, boolean enabled) {
        JSONObject params = new JSONObject();
        try {
            params.put("enabled", enabled);
        } catch (Exception ignored) {
        }
        return request(c, op, params);
    }

    private static Response packageOnly(Context c, String op, String pkg) {
        return request(c, op, packageParams(pkg));
    }

    private static JSONObject packageParams(String pkg) {
        JSONObject params = new JSONObject();
        try {
            params.put("package", pkg == null ? "" : pkg.trim());
        } catch (Exception ignored) {
        }
        return params;
    }

    public static Response appOps(Context c, String packageName) {
        JSONObject params = new JSONObject();
        try {
            params.put("package", packageName == null ? "" : packageName.trim());
        } catch (Exception ignored) {
        }
        return request(c, "APPOPS", params);
    }

    // ---- v1.35 bridge expansion: apps ----

    public static Response uninstallUser(Context c, String pkg) {
        return packageOnly(c, "UNINSTALL_USER", pkg);
    }

    public static Response installExisting(Context c, String pkg) {
        return packageOnly(c, "INSTALL_EXISTING", pkg);
    }

    public static Response listRemoved(Context c) {
        return request(c, "LIST_REMOVED", null);
    }

    public static Response installApk(Context c, String path) {
        JSONObject params = new JSONObject();
        try {
            params.put("path", path == null ? "" : path);
        } catch (Exception ignored) {
        }
        return request(c, "INSTALL_APK", params);
    }

    public static Response suspendApp(Context c, String pkg, boolean suspend) {
        return packageOnly(c, suspend ? "SUSPEND_APP" : "UNSUSPEND_APP", pkg);
    }

    public static Response trimCaches(Context c) {
        return request(c, "TRIM_CACHES", null);
    }

    // ---- v1.35 bridge expansion: display ----

    public static Response wmGet(Context c) {
        return request(c, "WM_GET", null);
    }

    public static Response wmSetSize(Context c, int width, int height) {
        JSONObject params = new JSONObject();
        try {
            params.put("width", width);
            params.put("height", height);
        } catch (Exception ignored) {
        }
        return request(c, "WM_SET_SIZE", params);
    }

    public static Response wmSetDensity(Context c, int density) {
        JSONObject params = new JSONObject();
        try {
            params.put("density", density);
        } catch (Exception ignored) {
        }
        return request(c, "WM_SET_DENSITY", params);
    }

    public static Response wmSetOverscan(Context c, int left, int top, int right, int bottom) {
        JSONObject params = new JSONObject();
        try {
            params.put("left", left);
            params.put("top", top);
            params.put("right", right);
            params.put("bottom", bottom);
        } catch (Exception ignored) {
        }
        return request(c, "WM_SET_OVERSCAN", params);
    }

    public static Response settingGet(Context c, String namespace, String key) {
        JSONObject params = new JSONObject();
        try {
            params.put("namespace", namespace);
            params.put("key", key);
        } catch (Exception ignored) {
        }
        return request(c, "SETTING_GET", params);
    }

    public static Response settingPut(Context c, String namespace, String key, String value) {
        JSONObject params = new JSONObject();
        try {
            params.put("namespace", namespace);
            params.put("key", key);
            params.put("value", value);
        } catch (Exception ignored) {
        }
        return request(c, "SETTING_PUT", params);
    }

    public static Response nightGet(Context c) {
        return request(c, "NIGHT_GET", null);
    }

    public static Response nightSet(Context c, String mode) {
        JSONObject params = new JSONObject();
        try {
            params.put("mode", mode);
        } catch (Exception ignored) {
        }
        return request(c, "NIGHT_SET", params);
    }

    // ---- v1.35 bridge expansion: input ----

    public static Response inputKey(Context c, int code) {
        JSONObject params = new JSONObject();
        try {
            params.put("code", code);
        } catch (Exception ignored) {
        }
        return request(c, "INPUT_KEY", params);
    }

    public static Response inputTap(Context c, int x, int y) {
        JSONObject params = new JSONObject();
        try {
            params.put("x", x);
            params.put("y", y);
        } catch (Exception ignored) {
        }
        return request(c, "INPUT_TAP", params);
    }

    public static Response inputSwipe(Context c, int x1, int y1, int x2, int y2, int durationMs) {
        JSONObject params = new JSONObject();
        try {
            params.put("x1", x1);
            params.put("y1", y1);
            params.put("x2", x2);
            params.put("y2", y2);
            params.put("durationMs", durationMs);
        } catch (Exception ignored) {
        }
        return request(c, "INPUT_SWIPE", params);
    }

    /** The text must already be escaped (BridgeOps.escapeInputText);
     *  it travels as one JSON string and is never logged. */
    public static Response inputText(Context c, String escaped) {
        JSONObject params = new JSONObject();
        try {
            params.put("escaped", escaped == null ? "" : escaped);
        } catch (Exception ignored) {
        }
        return request(c, "INPUT_TEXT", params);
    }

    // ---- v1.35 bridge expansion: capture ----

    /** The PNG arrives base64 in Response.data; text is the
     *  short summary only. */
    public static Response screenshot(Context c) {
        return request(c, "SCREENSHOT", null);
    }

    public static Response screenrecordStart(Context c, int seconds) {
        JSONObject params = new JSONObject();
        try {
            params.put("seconds", seconds);
        } catch (Exception ignored) {
        }
        return request(c, "SCREENRECORD_START", params);
    }

    public static Response screenrecordStatus(Context c) {
        return request(c, "SCREENRECORD_STATUS", null);
    }

    public static Response screenrecordStop(Context c) {
        return request(c, "SCREENRECORD_STOP", null);
    }

    public static Response bugreportStart(Context c) {
        return request(c, "BUGREPORT_START", null);
    }

    public static Response bugreportStatus(Context c) {
        return request(c, "BUGREPORT_STATUS", null);
    }

    public static Response bugreportStop(Context c) {
        return request(c, "BUGREPORT_STOP", null);
    }

    // ---- v1.35 bridge expansion: power & scheduling ----

    public static Response doze(Context c, String action) {
        JSONObject params = new JSONObject();
        try {
            params.put("action", action);
        } catch (Exception ignored) {
        }
        return request(c, "DOZE", params);
    }

    public static Response deviceIdleState(Context c) {
        return request(c, "DEVICEIDLE_STATE", null);
    }

    public static Response batteryGet(Context c) {
        return request(c, "BATTERY_GET", null);
    }

    public static Response batterySetLevel(Context c, int level) {
        JSONObject params = new JSONObject();
        try {
            params.put("level", level);
        } catch (Exception ignored) {
        }
        return request(c, "BATTERY_SET_LEVEL", params);
    }

    public static Response batteryUnplug(Context c) {
        return request(c, "BATTERY_UNPLUG", null);
    }

    public static Response batteryReset(Context c) {
        return request(c, "BATTERY_RESET", null);
    }

    public static Response standbyGet(Context c, String pkg) {
        return packageOnly(c, "STANDBY_GET", pkg);
    }

    public static Response standbySet(Context c, String pkg, String bucket) {
        JSONObject params = packageParams(pkg);
        try {
            params.put("bucket", bucket);
        } catch (Exception ignored) {
        }
        return request(c, "STANDBY_SET", params);
    }

    public static Response runJob(Context c, String pkg, String jobId) {
        JSONObject params = packageParams(pkg);
        try {
            params.put("jobId", jobId == null ? "" : jobId.trim());
        } catch (Exception ignored) {
        }
        return request(c, "RUN_JOB", params);
    }

    // ---- v1.35 bridge expansion: system config ----

    public static Response deviceConfigList(Context c, String namespace) {
        JSONObject params = new JSONObject();
        try {
            params.put("namespace", namespace == null ? "" : namespace);
        } catch (Exception ignored) {
        }
        return request(c, "DEVICE_CONFIG_LIST", params);
    }

    public static Response deviceConfigGet(Context c, String namespace, String key) {
        JSONObject params = new JSONObject();
        try {
            params.put("namespace", namespace);
            params.put("key", key);
        } catch (Exception ignored) {
        }
        return request(c, "DEVICE_CONFIG_GET", params);
    }

    public static Response deviceConfigPut(Context c, String namespace, String key, String value) {
        JSONObject params = new JSONObject();
        try {
            params.put("namespace", namespace);
            params.put("key", key);
            params.put("value", value);
        } catch (Exception ignored) {
        }
        return request(c, "DEVICE_CONFIG_PUT", params);
    }

    public static Response wifiList(Context c) {
        return request(c, "WIFI_LIST", null);
    }

    public static Response wifiForget(Context c, String networkId) {
        JSONObject params = new JSONObject();
        try {
            params.put("networkId", networkId == null ? "" : networkId);
        } catch (Exception ignored) {
        }
        return request(c, "WIFI_FORGET", params);
    }

    public static Response usersList(Context c) {
        return request(c, "USERS_LIST", null);
    }

    public static Response currentUser(Context c) {
        return request(c, "CURRENT_USER", null);
    }

    public static Response userCreate(Context c, String name) {
        JSONObject params = new JSONObject();
        try {
            params.put("name", name == null ? "" : name);
        } catch (Exception ignored) {
        }
        return request(c, "USER_CREATE", params);
    }

    public static Response userRemove(Context c, int userId) {
        JSONObject params = new JSONObject();
        try {
            params.put("userId", userId);
        } catch (Exception ignored) {
        }
        return request(c, "USER_REMOVE", params);
    }

    public static Response launchActivity(Context c, String pkg, String cls) {
        JSONObject params = packageParams(pkg);
        try {
            params.put("class", cls == null ? "" : cls);
        } catch (Exception ignored) {
        }
        return request(c, "LAUNCH_ACTIVITY", params);
    }

    // ---- v1.35 bridge expansion: introspection ----

    public static Response dumpsys(Context c, String service) {
        JSONObject params = new JSONObject();
        try {
            params.put("service", service == null ? "" : service);
        } catch (Exception ignored) {
        }
        return request(c, "DUMPSYS", params);
    }

    public static Response request(Context c, String op, JSONObject extra) {
        JSONObject request = extra == null ? new JSONObject() : extra;
        try {
            request.put("op", op);
            request.put("token", token(c));
        } catch (Exception ignored) {
        }
        Response local = requestLocal(request.toString());
        if (local != null) {
            saveState(c, local);
            return local;
        }
        Response tcp = requestTcp(request.toString());
        saveState(c, tcp);
        return tcp;
    }

    private static Response requestLocal(String requestJson) {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(ShellBridgeMain.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT), 2500);
            socket.setSoTimeout(15000);
            return exchange(requestJson, socket.getOutputStream(),
                    socket.getInputStream(), "local socket");
        } catch (Exception e) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static Response requestTcp(String requestJson) {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"),
                ShellBridgeMain.TCP_PORT)) {
            socket.setSoTimeout(15000);
            return exchange(requestJson, socket.getOutputStream(),
                    socket.getInputStream(), "loopback TCP");
        } catch (Exception e) {
            return new Response(false, "", "Bridge is not running: " + e.getMessage(),
                    -1, -1, "none", 0);
        }
    }

    private static Response exchange(String requestJson, OutputStream out,
                                     java.io.InputStream in, String transport) throws Exception {
        out.write((requestJson + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) throw new Exception("Bridge closed the connection.");
        JSONObject json = new JSONObject(line);
        return new Response(json.optBoolean("ok"), json.optString("text", ""),
                json.optString("error", ""), json.optInt("bridgeUid", -1),
                json.optInt("bridgePid", -1), transport,
                BridgeGate.parseVersion(json), json.optString("data", ""));
    }

    private static void saveState(Context c, Response response) {
        String state = response.ok
                ? "running as shell UID " + response.bridgeUid + " (PID " + response.bridgePid + ")"
                : "off — restart needed (also after every reboot)";
        Prefs.get(c).edit().putString(Prefs.KEY_BRIDGE_STATE, state).apply();
    }

    // ---- v1.35: live logcat tail (streaming, like HOLD_WATCH) ----

    /** One live tail: the bridge streams JSON lines until the
     *  line cap, a disconnect, or stop(). Callbacks run on the
     *  pump thread — the UI posts them to the main thread. */
    public static final class LogcatTail {
        public interface Listener {
            void onAck();
            void onLine(String line);
            void onDone(String reason);
            void onError(String error);
        }

        private volatile boolean stopped;
        private volatile boolean finished;
        private LocalSocket localSocket;
        private Socket tcpSocket;
        private Thread pumpThread;

        private LogcatTail() {}

        /** End the tail: the socket close ends the bridge-side
         *  stream, and the pump thread exits. Safe to call
         *  twice; silent when the tail already ended. */
        public void stop() {
            stopped = true;
            closeQuietly(localSocket);
            closeQuietly(tcpSocket);
        }

        public boolean isStopped() {
            return stopped || finished;
        }

        private void closeQuietly(LocalSocket socket) {
            if (socket == null) return;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }

        private void closeQuietly(Socket socket) {
            if (socket == null) return;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Start a live tail. The local socket is tried first,
     *  loopback TCP second — the same preference as a normal
     *  request. A failed connect reports onError; the caller
     *  owns the returned tail and must stop() it. */
    public static LogcatTail startLogcatTail(Context c, String query, int maxLines,
                                              LogcatTail.Listener listener) {
        LogcatTail tail = new LogcatTail();
        JSONObject request = new JSONObject();
        try {
            request.put("op", "LOGCAT_TAIL");
            request.put("token", token(c));
            request.put("query", query == null ? "" : query);
            request.put("maxLines", maxLines);
        } catch (Exception ignored) {
        }
        String requestJson = request.toString();
        tail.pumpThread = new Thread(() -> pumpTail(tail, requestJson, listener),
                "control-logcat-tail");
        tail.pumpThread.setDaemon(true);
        tail.pumpThread.start();
        return tail;
    }

    private static void pumpTail(LogcatTail tail, String requestJson,
                                 LogcatTail.Listener listener) {
        java.io.InputStream in = null;
        OutputStream out = null;
        try {
            LocalSocket local = new LocalSocket();
            try {
                local.connect(new LocalSocketAddress(ShellBridgeMain.SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT), 2500);
                tail.localSocket = local;
                in = local.getInputStream();
                out = local.getOutputStream();
            } catch (Exception e) {
                try {
                    local.close();
                } catch (Exception ignored) {
                }
                Socket tcp = new Socket(InetAddress.getByName("127.0.0.1"),
                        ShellBridgeMain.TCP_PORT);
                tail.tcpSocket = tcp;
                in = tcp.getInputStream();
                out = tcp.getOutputStream();
            }
            out.write((requestJson + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean acked = false;
            while (!tail.stopped && (line = reader.readLine()) != null) {
                JSONObject json;
                try {
                    json = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                if (!acked) {
                    if (!json.optBoolean("ok", false)) {
                        listener.onError(json.optString("error",
                                "The live tail was refused."));
                        return;
                    }
                    acked = true;
                    listener.onAck();
                    continue;
                }
                if (json.has("line")) {
                    listener.onLine(json.optString("line", ""));
                } else if (json.optBoolean("done", false)) {
                    listener.onDone(json.optString("reason", "The tail ended."));
                    return;
                }
            }
            if (!tail.stopped) {
                listener.onDone("The tail ended — the bridge closed the stream.");
            }
        } catch (Exception e) {
            if (!tail.stopped) {
                listener.onError("Live tail failed: " + e.getMessage());
            }
        } finally {
            tail.finished = true;
            tail.closeQuietly(tail.localSocket);
            tail.closeQuietly(tail.tcpSocket);
        }
    }
}
