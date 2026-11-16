package app.control.launcher;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Control's own shell-identity bridge.
 *
 * This class is NOT an Activity or Service. After the owner starts it
 * through adb/app_process, it runs as Android's shell user (normally
 * UID 2000). It exposes a deliberately narrow, read-only IPC surface:
 *
 *   PING       bridge identity/status
 *   PROCESSES  fixed `ps -A`
 *   SERVICES   fixed `dumpsys activity services`
 *   PROCESSES_DETAIL fixed `dumpsys activity processes`
 *   LOGS       fixed `logcat -d -v threadtime -t 2000`, filtered only
 *              in memory by a caller-supplied substring
 *   APPOPS     fixed `cmd appops get <validated.package>`
 *
 * Governed writes (v2) are also named operations only:
 *   GRANT_PERMISSION / REVOKE_PERMISSION (fixed permission set)
 *   SET_APP_OP (fixed op and mode sets)
 *   FORCE_STOP, SET_APP_ENABLED, CLEAR_APP_DATA
 *   SET_BACKGROUND_DATA, SET_DEVICE_IDLE_WHITELIST
 *   SET_PRIVATE_DNS (fixed modes; validated hostname)
 *   SET_LAUNCHER_SETTING (two named settings only)
 *   SET_WIFI / SET_MOBILE_DATA / SET_BLUETOOTH / SET_NFC (v3 — the
 *              fixed `svc` radio toggles behind the Connections screen)
 *   SET_AIRPLANE_MODE (v3 — `cmd connectivity airplane-mode`, with the
 *              settings + broadcast fallback)
 *   STOP_BRIDGE (the bridge ends itself)
 *   HOLD_WATCH (v4 — STREAMING, not request/response: the bridge
 *              finds the touchscreen via `getevent -p`, runs
 *              `getevent -t` on it, and streams one JSON line per
 *              stationary hold detected by HoldEventParser. Only
 *              hold points cross the socket — the raw touch stream
 *              is parsed in the bridge process and never leaves
 *              it, is never logged, and is never stored. The
 *              stream ends when the client disconnects.)
 *
 * There is no operation that accepts an executable, a shell string, or a
 * caller-composed command argument vector. Package targets are checked
 * against installed packages and against refuse-lists below. Local-socket
 * callers must have Control's app UID; loopback TCP callers must present
 * the app-private bridge token. The token is also required on the local
 * socket as a second factor. HOLD_WATCH runs only the two fixed
 * `getevent` commands above, against a device node validated to
 * match `/dev/input/eventN`.
 */
public final class ShellBridgeMain {
    public static final String SOCKET_NAME = "app.control.launcher.bridge.v1";
    public static final int TCP_PORT = 17391;
    /** Protocol version, sent in every response. Bump it when an
     *  op is added or changed — and keep BridgeGate's
     *  HOLD_WATCH_VERSION in step: the client gates the overlay
     *  watch on the version, because the running bridge survives
     *  APK upgrades and keeps answering with the OLD version
     *  until it is restarted. */
    public static final int VERSION = 4;

    private static final int MAX_REQUEST_CHARS = 16384;
    private static final int MAX_OUTPUT_CHARS = 350_000;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+"
                    + "[A-Za-z]{2,63}$");

    /** Packages no bridge write may touch at all. */
    private static final java.util.Set<String> REFUSE_ALL = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "app.control.launcher", "android", "com.android.systemui",
                    "com.android.settings", "com.android.shell", "com.android.phone",
                    "com.android.server.telecom", "com.android.packageinstaller",
                    "com.google.android.packageinstaller",
                    "com.android.permissioncontroller",
                    "com.google.android.permissioncontroller"));

    /** Packages additionally protected from force-stop / disable / clear. */
    private static final java.util.Set<String> REFUSE_DESTRUCTIVE = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "com.google.android.gms", "com.google.android.gsf",
                    "com.android.vending", "com.google.android.packageinstaller",
                    "com.android.providers.settings", "com.android.providers.telephony",
                    "com.android.providers.media", "com.android.providers.downloads",
                    "com.android.bluetooth", "com.android.nfc",
                    "com.android.networkstack", "com.android.networkstack.tethering",
                    "com.google.android.networkstack", "com.android.wifi.resources",
                    "com.google.android.wifi.resources", "com.android.ons",
                    "com.android.stk", "com.google.android.euicc",
                    "com.android.providers.blockednumber",
                    "com.android.providers.userdictionary"));

    private static final java.util.Set<String> PERMISSIONS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION",
                    "android.permission.POST_NOTIFICATIONS", "android.permission.READ_SMS",
                    "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
                    "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
                    "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
                    "android.permission.BODY_SENSORS", "android.permission.READ_CALL_LOG",
                    "android.permission.READ_PHONE_STATE", "android.permission.CALL_PHONE",
                    "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_EXTERNAL_STORAGE"));

    private static final java.util.Set<String> APP_OPS = new java.util.HashSet<>(
            java.util.Arrays.asList("CAMERA", "RECORD_AUDIO", "FINE_LOCATION",
                    "COARSE_LOCATION", "POST_NOTIFICATION", "READ_SMS", "SEND_SMS",
                    "RECEIVE_SMS", "READ_CONTACTS", "READ_CALENDAR", "BODY_SENSORS",
                    "READ_CALL_LOG", "READ_PHONE_STATE", "CALL_PHONE", "WAKE_LOCK",
                    "MONITOR_LOCATION", "GET_USAGE_STATS", "SCHEDULE_EXACT_ALARM"));

    private static final java.util.Set<String> APP_OP_MODES = new java.util.HashSet<>(
            java.util.Arrays.asList("allow", "ignore", "deny", "default", "foreground"));

    private static int expectedAppUid = -1;
    private static String bridgeToken = "";

    private ShellBridgeMain() {}

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ShellBridgeMain <control-app-uid> <bridge-token>");
            return;
        }
        try {
            expectedAppUid = Integer.parseInt(args[0]);
        } catch (Exception e) {
            System.err.println("Invalid app UID");
            return;
        }
        bridgeToken = args[1];
        if (bridgeToken.length() < 24) {
            System.err.println("Bridge token is too short");
            return;
        }

        Thread tcp = new Thread(ShellBridgeMain::serveTcp, "control-bridge-tcp");
        tcp.setDaemon(true);
        tcp.start();
        serveLocal();
    }

    private static void serveLocal() {
        try (LocalServerSocket server = new LocalServerSocket(SOCKET_NAME)) {
            System.err.println("Control shell bridge listening as uid=" + Process.myUid());
            while (true) {
                LocalSocket socket = server.accept();
                Thread t = new Thread(() -> handleLocal(socket), "control-bridge-local-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception e) {
            System.err.println("Local bridge stopped: " + e);
        }
    }

    private static void serveTcp() {
        try (ServerSocket server = new ServerSocket(TCP_PORT, 8,
                InetAddress.getByName("127.0.0.1"))) {
            while (true) {
                Socket socket = server.accept();
                Thread t = new Thread(() -> handleTcp(socket), "control-bridge-tcp-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception e) {
            System.err.println("TCP bridge unavailable: " + e);
        }
    }

    private static void handleLocal(LocalSocket socket) {
        try (LocalSocket s = socket) {
            Credentials peer = s.getPeerCredentials();
            boolean uidOk = peer != null && peer.getUid() == expectedAppUid;
            handleConnection(s.getInputStream(), s.getOutputStream(), uidOk, true);
        } catch (Exception e) {
            System.err.println("Local bridge request failed: " + e);
        }
    }

    private static void handleTcp(Socket socket) {
        try (Socket s = socket) {
            boolean loopback = s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress();
            handleConnection(s.getInputStream(), s.getOutputStream(), loopback, false);
        } catch (Exception e) {
            System.err.println("TCP bridge request failed: " + e);
        }
    }

    /**
     * One connection: read the request line, then either run the
     * streaming HOLD_WATCH op on this thread (it returns when the
     * client disconnects) or answer a normal request/response op.
     * HOLD_WATCH authenticates exactly like every other op
     * (transport check + token); only the response shape differs
     * (an ack line, then one JSON line per hold).
     */
    private static void handleConnection(InputStream in, OutputStream out,
                                         boolean transportOk, boolean localSocket)
            throws Exception {
        String line = readLine(in);
        JSONObject probe = null;
        if (line != null && line.length() <= MAX_REQUEST_CHARS) {
            try {
                probe = new JSONObject(line);
            } catch (Exception ignored) {
            }
        }
        if (probe != null && "HOLD_WATCH".equals(probe.optString("op", ""))) {
            if (!transportOk || !bridgeToken.equals(probe.optString("token", ""))) {
                JSONObject err = new JSONObject();
                put(err, "version", VERSION);
                put(err, "ok", false);
                put(err, "error", "Hold watch refused: transport or token.");
                writeLine(out, err.toString());
                return;
            }
            streamHoldWatch(in, out, probe);
            return;
        }
        JSONObject response = handleRequest(line, transportOk, localSocket);
        writeLine(out, response.toString());
    }

    /**
     * The hold watch (v4): find the touchscreen, stream its
     * {@code getevent} output through HoldEventParser, and write
     * one JSON line per detected hold. Runs on the connection
     * thread until the client disconnects (a drain thread on the
     * request stream notices the close and kills getevent) or
     * getevent itself ends.
     *
     * Privacy: the raw touch stream is parsed here, in the bridge
     * process, and only hold points (normalized coordinates) are
     * written to the socket. Nothing in this method logs or stores
     * coordinates, positions, or event lines — the bridge log gets
     * start/end lines only.
     */
    private static void streamHoldWatch(InputStream in, OutputStream out,
                                        JSONObject request) {
        System.err.println("Hold watch starting");
        double radiusNorm = request.optDouble("radiusNorm", 0.03);
        if (!(radiusNorm > 0.0) || radiusNorm > 0.2) radiusNorm = 0.03;
        long holdMs = request.optLong("holdMs", 1000L);
        if (holdMs < 500L || holdMs > 5000L) holdMs = 1000L;

        TouchDeviceProbe.Device device = null;
        try {
            String probeOut = runFixed(new String[]{"/system/bin/getevent", "-p"});
            device = TouchDeviceProbe.pick(TouchDeviceProbe.parse(probeOut));
        } catch (Exception ignored) {
            // Falls through to the no-device answer below.
        }
        if (device == null) {
            writeLineQuiet(out,
                    "{\"ok\":false,\"error\":\"No touchscreen input device found.\"}");
            System.err.println("Hold watch ended: no touchscreen device");
            return;
        }
        if (!Pattern.matches("^/dev/input/event\\d+$", device.path)) {
            writeLineQuiet(out,
                    "{\"ok\":false,\"error\":\"Touchscreen device path refused.\"}");
            System.err.println("Hold watch ended: device path refused");
            return;
        }

        final boolean[] clientGone = {false};
        final java.lang.Process[] holder = {null};
        // The client disconnecting is only visible as EOF on the
        // request stream, which nothing else reads — so a drain
        // thread watches for it and ends the watch.
        Thread drain = new Thread(() -> {
            try {
                byte[] buf = new byte[64];
                while (in.read(buf) >= 0) {
                    // The client sends nothing after its request;
                    // any bytes are ignored, EOF ends the watch.
                }
            } catch (Exception ignored) {
            }
            clientGone[0] = true;
            java.lang.Process p = holder[0];
            if (p != null) p.destroyForcibly();
        }, "control-bridge-holdwatch-drain");
        drain.setDaemon(true);
        drain.start();

        final OutputStream sink = out;
        HoldEventParser parser = new HoldEventParser(device.minX, device.maxX,
                device.minY, device.maxY, radiusNorm, holdMs, hold -> {
                    if (clientGone[0]) return;
                    String line = "{\"hold\":true,\"nx\":" + hold.nx
                            + ",\"ny\":" + hold.ny + "}";
                    synchronized (sink) {
                        if (!writeLineQuiet(sink, line)) clientGone[0] = true;
                    }
                });
        try {
            if (!writeLineQuiet(sink, "{\"ok\":true,\"op\":\"HOLD_WATCH\"}")) return;
            java.lang.Process getevent = new ProcessBuilder(
                    "/system/bin/getevent", "-t", device.path)
                    .redirectErrorStream(true)
                    .start();
            holder[0] = getevent;
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getevent.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (!clientGone[0] && (line = reader.readLine()) != null) {
                parser.feedLine(line);
            }
        } catch (Exception e) {
            // getevent failed to start or its stream broke: the
            // client sees the connection close and reports the
            // watch as ended.
        } finally {
            java.lang.Process p = holder[0];
            if (p != null) p.destroyForcibly();
            System.err.println("Hold watch ended");
        }
    }

    private static JSONObject handleRequest(String line, boolean transportOk,
                                            boolean localSocket) {
        JSONObject response = new JSONObject();
        try {
            put(response, "version", VERSION);
            put(response, "bridgeUid", Process.myUid());
            put(response, "bridgePid", Process.myPid());
            if (!transportOk) {
                put(response, "ok", false);
                put(response, "error", localSocket
                        ? "Caller UID is not Control's app UID."
                        : "Only loopback callers are accepted.");
                return response;
            }
            if (line == null || line.length() > MAX_REQUEST_CHARS) {
                put(response, "ok", false);
                put(response, "error", "Request missing or too large.");
                return response;
            }
            JSONObject request = new JSONObject(line);
            if (!bridgeToken.equals(request.optString("token", ""))) {
                put(response, "ok", false);
                put(response, "error", "Bridge token rejected.");
                return response;
            }
            String op = request.optString("op", "");
            String text;
            switch (op) {
                case "PING":
                    text = "Control shell bridge is running as UID " + Process.myUid()
                            + " (PID " + Process.myPid() + "). Governed read/control allowlist v" + VERSION + ".";
                    break;
                case "PROCESSES":
                    text = runFixed(new String[]{"/system/bin/ps", "-A"});
                    break;
                case "PROCESSES_DETAIL":
                    text = runFixed(new String[]{"/system/bin/dumpsys", "activity", "processes"});
                    break;
                case "SERVICES":
                    text = runFixed(new String[]{"/system/bin/dumpsys", "activity", "services"});
                    break;
                case "SERVICE_LIST":
                    text = runFixed(new String[]{"/system/bin/service", "list"});
                    break;
                case "LOGS": {
                    String raw = runFixed(new String[]{"/system/bin/logcat", "-d",
                            "-v", "threadtime", "-t", "2000"});
                    text = filterLog(raw, request.optString("query", ""),
                            request.optInt("maxLines", 400));
                    break;
                }
                case "APPOPS": {
                    String pkg = request.optString("package", "");
                    if (!PACKAGE_PATTERN.matcher(pkg).matches()) {
                        throw new Exception("Invalid package name.");
                    }
                    text = runFixed(new String[]{"/system/bin/cmd", "appops", "get", pkg});
                    break;
                }
                case "GRANT_PERMISSION":
                case "REVOKE_PERMISSION": {
                    String pkg = checkedPackage(request, false);
                    String permission = request.optString("permission", "");
                    if (!PERMISSIONS.contains(permission)) {
                        throw new Exception("Permission is not on the bridge allowlist.");
                    }
                    boolean grant = "GRANT_PERMISSION".equals(op);
                    String out = runFixed(new String[]{"/system/bin/pm",
                            grant ? "grant" : "revoke", pkg, permission});
                    text = (grant ? "Granted " : "Revoked ") + permission + " for "
                            + pkg + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_APP_OP": {
                    String pkg = checkedPackage(request, false);
                    String appOp = request.optString("appOp", "");
                    String mode = request.optString("mode", "");
                    if (!APP_OPS.contains(appOp)) {
                        throw new Exception("AppOp is not on the bridge allowlist.");
                    }
                    if (!APP_OP_MODES.contains(mode)) {
                        throw new Exception("AppOp mode is not on the bridge allowlist.");
                    }
                    String out = runFixed(new String[]{"/system/bin/cmd", "appops", "set",
                            pkg, appOp, mode});
                    text = "Set AppOp " + appOp + " to " + mode + " for " + pkg
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "FORCE_STOP": {
                    String pkg = checkedPackage(request, true);
                    String out = runFixed(new String[]{"/system/bin/cmd", "activity",
                            "force-stop", pkg});
                    text = "Force-stopped " + pkg
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_APP_ENABLED": {
                    String pkg = checkedPackage(request, true);
                    boolean enabled = request.optBoolean("enabled", true);
                    String out = runFixed(new String[]{"/system/bin/pm",
                            enabled ? "enable" : "disable", pkg});
                    text = (enabled ? "Enabled " : "Disabled ") + pkg
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "CLEAR_APP_DATA": {
                    String pkg = checkedPackage(request, true);
                    String out = runFixed(new String[]{"/system/bin/cmd", "package",
                            "clear", pkg});
                    if (!out.toLowerCase().contains("success")) {
                        throw new Exception("Android did not confirm the clear: " + out.trim());
                    }
                    text = "Cleared app data for " + pkg + ". " + out.trim();
                    break;
                }
                case "SET_BACKGROUND_DATA": {
                    String pkg = checkedPackage(request, false);
                    boolean allowed = request.optBoolean("allowed", true);
                    int uid = packageUid(pkg);
                    String out = runFixed(new String[]{"/system/bin/cmd", "netpolicy",
                            allowed ? "remove" : "add", "restrict-background-blacklist",
                            String.valueOf(uid)});
                    text = (allowed ? "Allowed background data for " : "Restricted background data for ")
                            + pkg + " (UID " + uid + ")"
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_DEVICE_IDLE_WHITELIST": {
                    String pkg = checkedPackage(request, false);
                    boolean whitelisted = request.optBoolean("whitelisted", true);
                    String out = runFixed(new String[]{"/system/bin/cmd", "deviceidle",
                            "whitelist", (whitelisted ? "+" : "-") + pkg});
                    text = (whitelisted ? "Added " : "Removed ") + pkg
                            + (whitelisted ? " to" : " from") + " the device-idle whitelist"
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_PRIVATE_DNS": {
                    String mode = request.optString("mode", "");
                    if (!mode.equals("off") && !mode.equals("opportunistic")
                            && !mode.equals("hostname")) {
                        throw new Exception("Private DNS mode is not on the bridge allowlist.");
                    }
                    String hostname = request.optString("hostname", "").trim();
                    if (mode.equals("hostname") && !HOSTNAME_PATTERN.matcher(hostname).matches()) {
                        throw new Exception("Invalid Private DNS hostname.");
                    }
                    if (mode.equals("hostname")) {
                        runFixed(new String[]{"/system/bin/settings", "put", "global",
                                "private_dns_specifier", hostname});
                    }
                    String out = runFixed(new String[]{"/system/bin/settings", "put", "global",
                            "private_dns_mode", mode});
                    text = "Set Private DNS mode to " + mode
                            + (mode.equals("hostname") ? " (" + hostname + ")" : "")
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_LAUNCHER_SETTING": {
                    String setting = request.optString("setting", "");
                    String value = request.optString("value", "");
                    String key;
                    if (setting.equals("screen_off_timeout")) {
                        key = "screen_off_timeout";
                        int ms = parseInt(value, -1);
                        if (ms < 15000 || ms > 1800000) {
                            throw new Exception("Screen timeout is outside the allowed range.");
                        }
                    } else if (setting.equals("font_scale")) {
                        key = "font_scale";
                        float scale;
                        try {
                            scale = Float.parseFloat(value);
                        } catch (Exception e) {
                            throw new Exception("Invalid font scale.");
                        }
                        if (scale < 0.85f || scale > 1.30f) {
                            throw new Exception("Font scale is outside the allowed range.");
                        }
                    } else {
                        throw new Exception("Launcher setting is not on the bridge allowlist.");
                    }
                    String out = runFixed(new String[]{"/system/bin/settings", "put", "system",
                            key, value});
                    text = "Set " + key + " to " + value
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_WIFI": {
                    boolean enabled = request.optBoolean("enabled", false);
                    String out = runFixed(new String[]{"/system/bin/svc", "wifi",
                            enabled ? "enable" : "disable"});
                    text = "Wi-Fi " + (enabled ? "enabled" : "disabled")
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_MOBILE_DATA": {
                    boolean enabled = request.optBoolean("enabled", false);
                    String out = runFixed(new String[]{"/system/bin/svc", "data",
                            enabled ? "enable" : "disable"});
                    text = "Mobile data " + (enabled ? "enabled" : "disabled")
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_BLUETOOTH": {
                    boolean enabled = request.optBoolean("enabled", false);
                    String out = runFixed(new String[]{"/system/bin/svc", "bluetooth",
                            enabled ? "enable" : "disable"});
                    text = "Bluetooth " + (enabled ? "enabled" : "disabled")
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_NFC": {
                    boolean enabled = request.optBoolean("enabled", false);
                    String out = runFixed(new String[]{"/system/bin/svc", "nfc",
                            enabled ? "enable" : "disable"});
                    text = "NFC " + (enabled ? "enabled" : "disabled")
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "SET_AIRPLANE_MODE": {
                    boolean enabled = request.optBoolean("enabled", false);
                    String out = runFixed(new String[]{"/system/bin/cmd",
                            "connectivity", "airplane-mode",
                            enabled ? "enable" : "disable"});
                    String low = out.toLowerCase();
                    if (low.contains("exception") || low.contains("unknown")
                            || low.contains("error")) {
                        // Fallback for shells without `cmd connectivity`:
                        // the global setting + the airplane-mode broadcast.
                        runFixed(new String[]{"/system/bin/settings", "put", "global",
                                "airplane_mode_on", enabled ? "1" : "0"});
                        String b = runFixed(new String[]{"/system/bin/am", "broadcast",
                                "-a", "android.intent.action.AIRPLANE_MODE",
                                "--ez", "state", enabled ? "true" : "false"});
                        out = "Set via the settings + broadcast fallback."
                                + (b.trim().isEmpty() ? "" : "\n" + b.trim());
                    }
                    text = "Airplane mode " + (enabled ? "ON" : "OFF")
                            + (out.trim().isEmpty() ? "." : ".\n" + out.trim());
                    break;
                }
                case "STOP_BRIDGE": {
                    text = "Control shell bridge stopping. Deep view is off until it is started again.";
                    new Thread(() -> {
                        try {
                            Thread.sleep(350);
                        } catch (Exception ignored) {
                        }
                        System.exit(0);
                    }, "control-bridge-stop").start();
                    break;
                }
                default:
                    throw new Exception("Operation is not on the bridge allowlist.");
            }
            put(response, "ok", true);
            put(response, "op", op);
            put(response, "text", cap(text));
            return response;
        } catch (Exception e) {
            try {
                response.put("ok", false);
                response.put("error", e.getMessage() == null ? "Bridge error" : e.getMessage());
            } catch (Exception ignored) {
            }
            return response;
        }
    }

    private static String checkedPackage(JSONObject request, boolean destructive)
            throws Exception {
        String pkg = request.optString("package", "");
        if (!PACKAGE_PATTERN.matcher(pkg).matches()) {
            throw new Exception("Invalid package name.");
        }
        if (REFUSE_ALL.contains(pkg)) {
            throw new Exception("Refused: " + pkg + " is on the bridge refuse-list.");
        }
        if (destructive && REFUSE_DESTRUCTIVE.contains(pkg)) {
            throw new Exception("Refused: destructive control of " + pkg
                    + " is protected by the bridge refuse-list.");
        }
        String path = runFixed(new String[]{"/system/bin/pm", "path", pkg});
        if (!path.trim().startsWith("package:")) {
            throw new Exception("Refused: " + pkg + " is not installed.");
        }
        return pkg;
    }

    private static int packageUid(String pkg) throws Exception {
        String out = runFixed(new String[]{"/system/bin/cmd", "package", "list",
                "packages", "-U", pkg});
        java.util.regex.Matcher m = Pattern.compile("uid:(\\d+)").matcher(out);
        if (!m.find()) throw new Exception("Could not resolve a UID for " + pkg + ".");
        return Integer.parseInt(m.group(1));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String runFixed(String[] command) throws Exception {
        java.lang.Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (captured) {
                        if (captured.size() < 1_200_000) {
                            captured.write(buffer, 0, Math.min(n, 1_200_000 - captured.size()));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "control-bridge-command-output");
        reader.start();
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new Exception("The fixed allowlisted command timed out.");
        }
        reader.join(1000);
        synchronized (captured) {
            return new String(captured.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String filterLog(String raw, String query, int maxLines) {
        int capLines = Math.max(25, Math.min(500, maxLines));
        String needle = query == null ? "" : query.trim().toLowerCase();
        String[] lines = raw.split("\\n");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (needle.isEmpty() || line.toLowerCase().contains(needle)) kept.add(line);
        }
        int start = Math.max(0, kept.size() - capLines);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < kept.size(); i++) {
            if (out.length() > 0) out.append('\n');
            out.append(kept.get(i));
        }
        return out.toString();
    }

    private static String cap(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(text.length() - MAX_OUTPUT_CHARS);
    }

    private static String readLine(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return reader.readLine();
    }

    private static void writeLine(OutputStream out, String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** writeLine that reports failure instead of throwing (the
     *  streaming hold watch treats a failed write as "client
     *  gone" and ends cleanly). */
    private static boolean writeLineQuiet(OutputStream out, String line) {
        try {
            writeLine(out, line);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
    }
}
