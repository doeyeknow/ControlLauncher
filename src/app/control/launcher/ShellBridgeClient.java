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

        Response(boolean ok, String text, String error, int bridgeUid,
                 int bridgePid, String transport, int bridgeVersion) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.error = error == null ? "" : error;
            this.bridgeUid = bridgeUid;
            this.bridgePid = bridgePid;
            this.transport = transport == null ? "" : transport;
            this.bridgeVersion = bridgeVersion;
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
        JSONObject params = packageParams(pkg);
        try {
            params.put("enabled", enabled);
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
                BridgeGate.parseVersion(json));
    }

    private static void saveState(Context c, Response response) {
        String state = response.ok
                ? "running as shell UID " + response.bridgeUid + " (PID " + response.bridgePid + ")"
                : "off — restart needed (also after every reboot)";
        Prefs.get(c).edit().putString(Prefs.KEY_BRIDGE_STATE, state).apply();
    }
}
