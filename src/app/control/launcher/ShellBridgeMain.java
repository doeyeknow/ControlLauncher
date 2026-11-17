package app.control.launcher;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
 *   FORCE_STOP, SET_APP_ENABLED (v5 — disable runs
 *   `pm disable-user --user 0`, the per-user variant the shell
 *   user is allowed, and pm's output is verified before success
 *   is reported; plain `pm disable` is the system-level disable
 *   and was always refused for shell), CLEAR_APP_DATA
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
 *   HOLD_WATCH_STATUS (v10 — request/response diagnostics for
 *              the hold watch: the picked device, the frames
 *              and holds the current/last watch saw, whether a
 *              watch is streaming, and any probe failure.
 *              Counts and names only, never coordinates.)
 *
 * v6 (the bridge expansion) adds named operations only, all of
 * them fixed command arrays shaped by BridgeOps, every write
 * verified from the tool's own output before success is
 * reported, and every package target checked against the
 * refuse-lists:
 *   UNINSTALL_USER / INSTALL_EXISTING / LIST_REMOVED /
 *   INSTALL_APK (staged path only) / SUSPEND_APP /
 *   UNSUSPEND_APP / TRIM_CACHES
 *   WM_GET / WM_SET_SIZE / WM_SET_DENSITY / WM_SET_OVERSCAN
 *   SETTING_GET / SETTING_PUT (eight-key allowlist only)
 *   NIGHT_GET / NIGHT_SET
 *   INPUT_KEY (fixed menu) / INPUT_TAP / INPUT_SWIPE /
 *   INPUT_TEXT (escaped, never logged)
 *   SCREENSHOT (PNG as base64 in the "data" field)
 *   SCREENRECORD_START / STATUS / STOP and BUGREPORT_START /
 *   STATUS / STOP (start-and-poll; the file lands in shared
 *   storage and the status names its path and size)
 *   DOZE / DEVICEIDLE_STATE / BATTERY_GET / BATTERY_SET_LEVEL /
 *   BATTERY_UNPLUG / BATTERY_RESET / STANDBY_GET / STANDBY_SET /
 *   RUN_JOB
 *   DEVICE_CONFIG_LIST / GET / PUT / WIFI_LIST / WIFI_FORGET /
 *   USERS_LIST / CURRENT_USER / USER_CREATE / USER_REMOVE /
 *   LAUNCH_ACTIVITY
 *   DUMPSYS (fixed service menu, never the whole dump)
 *   LOGCAT_TAIL (STREAMING, like HOLD_WATCH: `logcat -T 20`,
 *   bounded lines, optional substring filter, ends on
 *   disconnect, on Stop, or on the line cap)
 *
 * v7 (the protected-package override) changes no op's shape:
 * SET_APP_ENABLED reads one new boolean field, "override".
 * When it is true, disable/enable is permitted on refuse-listed
 * packages — EXCEPT Control itself, which stays refused always
 * (disabling it would remove the only tool that can re-enable
 * it). Every other op ignores the field and keeps the existing
 * refusals. An old bridge (v6 and below) never reads the field,
 * so it refuses protected disables exactly as before: the
 * override fails closed, and the version bump makes stale
 * detection force the one-tap restart before it can work.
 *
 * v8 changes one verifier, not any op's shape: LAUNCH_ACTIVITY
 * counts am start's already-on-top warning ("intent has been
 * delivered to currently running top-most instance") as
 * success, because the activity came forward. A v7 bridge
 * still reports that case as a refusal, so the bump forces
 * the one-tap restart before launches of already-running
 * activities stop looking refused. All other verifier rules
 * are unchanged.
 *
 * v9 changes HOLD_WATCH's evaluation, not its wire shape:
 * the hold threshold is now judged by a 100ms timer thread in
 * addition to frame boundaries, because hardware that reports
 * only on change sends no frames for a still finger and the
 * hold could never fire (the overlay ring never opened over
 * other apps). The hold lines are unchanged, so no client
 * change is needed beyond the version gate — but a v8 bridge
 * still runs the frame-only parser, so the bump forces the
 * restart before the ring can work over other apps.
 *
 * v10 adds HOLD_WATCH_STATUS, a request/response diagnostics
 * op: the picked input device, the frames and holds the
 * current/last watch saw, whether a watch is streaming, and
 * any probe failure. It exists because on 1.43 the ring still
 * opened only inside the launcher with every gate passing and
 * no visible reason — the only existing carrier for the
 * picked device was the bridge's stderr log file, readable
 * only over an ADB shell connection, which is itself broken
 * on Adam's phone. Counts and names only, never coordinates.
 * A v9 bridge refuses the op with the allowlist error, so the
 * bump forces the restart before the Settings diagnostic line
 * can show bridge-side data.
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
    public static final int VERSION = 10;

    private static final int MAX_REQUEST_CHARS = 16384;
    private static final int MAX_OUTPUT_CHARS = 350_000;
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+"
                    + "[A-Za-z]{2,63}$");

    /** Refuse-lists, owned by ProcessRules (v1.33): one source
     *  of truth, so the list the Deep controls UI warns about
     *  is exactly the list the bridge enforces. */
    private static final java.util.Set<String> REFUSE_ALL = ProcessRules.REFUSE_ALL;
    private static final java.util.Set<String> REFUSE_DESTRUCTIVE =
            ProcessRules.REFUSE_DESTRUCTIVE;

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

    /** Start-and-poll long tasks (v6): at most one screen
     *  recording and one bugreport at a time. Each slot holds
     *  the running process and the file it writes; STATUS
     *  reads liveness + file size, STOP ends the process, and
     *  a finished process clears its slot on the next STATUS
     *  (or on the next START, which refuses while one is
     *  alive). Guarded by their own locks; the request
     *  handler threads are the only writers. */
    private static final Object RECORD_LOCK = new Object();
    private static java.lang.Process recordProcess;
    private static String recordPath;
    private static final Object BUGREPORT_LOCK = new Object();
    private static java.lang.Process bugreportProcess;
    private static String bugreportPath;

    /** Hold-watch diagnostics (v10), read by HOLD_WATCH_STATUS:
     *  the picked device, whether a watch is streaming, any
     *  probe failure, and the last ENDED watch's frame/hold
     *  snapshot. A live watch's counts are read straight from
     *  its parser via {@link #activeHoldParser} (its getters
     *  are synchronized), so the status op never sees a stale
     *  snapshot mid-watch; the finally block snapshots the
     *  same counters once when the watch ends, before clearing
     *  the reference. Counts and names only — never
     *  coordinates. The parser counters are always read
     *  OUTSIDE this lock (the lock guards the snapshot fields
     *  only), so no lock nesting exists anywhere on this path. */
    private static final Object HOLD_STATUS_LOCK = new Object();
    private static String holdStatusDevice = "";
    private static long holdStatusFrames;
    private static long holdStatusHolds;
    private static boolean holdStatusActive;
    private static String holdStatusEndReason = "";
    private static volatile HoldEventParser activeHoldParser;

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
        String probeOp = probe == null ? "" : probe.optString("op", "");
        if ("HOLD_WATCH".equals(probeOp) || "LOGCAT_TAIL".equals(probeOp)) {
            if (!transportOk || !bridgeToken.equals(probe.optString("token", ""))) {
                JSONObject err = new JSONObject();
                put(err, "version", VERSION);
                put(err, "ok", false);
                put(err, "error", "Streaming op refused: transport or token.");
                writeLine(out, err.toString());
                return;
            }
            if ("HOLD_WATCH".equals(probeOp)) {
                streamHoldWatch(in, out, probe);
            } else {
                streamLogcatTail(in, out, probe);
            }
            return;
        }
        JSONObject response = handleRequest(line, transportOk, localSocket);
        writeLine(out, response.toString());
    }

    /**
     * The hold watch (v4; timer evaluation v9): find the touchscreen, stream its
     * {@code getevent} output through HoldEventParser, and write
     * one JSON line per detected hold. A 100ms timer thread
     * re-judges the threshold between frames (v1.43), so a
     * still finger fires even on hardware that reports only on
     * change. Runs on the connection thread until the client
     * disconnects (a drain thread on the request stream notices
     * the close and kills getevent) or getevent itself ends.
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
            synchronized (HOLD_STATUS_LOCK) {
                holdStatusActive = false;
                holdStatusDevice = "";
                holdStatusEndReason = "No touchscreen input device found.";
            }
            return;
        }
        if (!Pattern.matches("^/dev/input/event\\d+$", device.path)) {
            writeLineQuiet(out,
                    "{\"ok\":false,\"error\":\"Touchscreen device path refused.\"}");
            System.err.println("Hold watch ended: device path refused");
            synchronized (HOLD_STATUS_LOCK) {
                holdStatusActive = false;
                holdStatusDevice = "";
                holdStatusEndReason = "Touchscreen device path refused.";
            }
            return;
        }
        // The picked device, for Diagnose: a wrong pick (a
        // digitizer, a key device) streams no touch frames, so
        // the hold lines never come — the device line tells
        // that apart from a dead watch. A node path and a
        // device name are not coordinates.
        System.err.println("Hold watch device: " + device.path
                + " (" + device.name + ")");
        // The same device, for HOLD_WATCH_STATUS (v10): the log
        // line above is reachable only over ADB, so the status
        // op is how the Settings row learns the pick. A fresh
        // watch resets the counts and clears a stale probe
        // failure; the snapshot survives in the finally below.
        final String pickedDevice = device.path + " (" + device.name + ")";
        synchronized (HOLD_STATUS_LOCK) {
            holdStatusDevice = pickedDevice;
            holdStatusActive = true;
            holdStatusEndReason = "";
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
        // The live parser, for HOLD_WATCH_STATUS: the status op
        // reads its frame/hold counters directly, so a
        // timer-path fire is visible even when no further
        // getevent line ever arrives. Cleared in the finally
        // below, after the end snapshot is taken.
        activeHoldParser = parser;
        // Timer-driven evaluation (v1.43): re-judge the hold
        // threshold every 100ms even when no getevent frame
        // arrives — on change-only hardware a still finger
        // produces no frames, so the frame-only judgment could
        // never fire and the ring never opened over other apps.
        // uptimeMillis shares the CLOCK_MONOTONIC domain of the
        // getevent -t timestamps, so the comparison is the same
        // one the frame path makes. Stopped in the finally
        // below, alongside getevent.
        final boolean[] evalStop = {false};
        Thread evaluator = new Thread(() -> {
            while (!evalStop[0] && !clientGone[0]) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    break;
                }
                if (evalStop[0] || clientGone[0]) break;
                parser.evaluateAt(android.os.SystemClock.uptimeMillis());
            }
        }, "control-bridge-holdwatch-eval");
        evaluator.setDaemon(true);
        evaluator.start();
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
            evalStop[0] = true;
            evaluator.interrupt();
            java.lang.Process p = holder[0];
            if (p != null) p.destroyForcibly();
            // The end snapshot, for HOLD_WATCH_STATUS: the
            // counters are read outside the lock, then stored
            // under it, and only then is the live reference
            // cleared — a status read racing this block sees
            // either the live parser (same counters) or the
            // snapshot, never a half state.
            long endFrames = parser.framesSeen();
            long endHolds = parser.holdsFired();
            synchronized (HOLD_STATUS_LOCK) {
                holdStatusFrames = endFrames;
                holdStatusHolds = endHolds;
                holdStatusActive = false;
            }
            activeHoldParser = null;
            System.err.println("Hold watch ended");
        }
    }

    /**
     * The live log tail (v6): `logcat -T 20 -v threadtime`
     * streams the last 20 lines and then follows, one JSON
     * line per log line. Mirrors the hold watch's shape: the
     * same drain thread ends the stream when the client
     * disconnects, the stream ends on the line cap (with a
     * done line saying so), and an optional substring query
     * filters lines bridge-side, like the LOGS op. The cap
     * counts lines SENT, so a filter never ends the tail
     * early. logcat's own output is the only thing that
     * crosses the socket; nothing is logged or stored here.
     */
    private static void streamLogcatTail(InputStream in, OutputStream out,
                                         JSONObject request) {
        System.err.println("Logcat tail starting");
        int maxLines = request.optInt("maxLines", 200);
        if (maxLines < 50) maxLines = 50;
        if (maxLines > 1000) maxLines = 1000;
        String needle = request.optString("query", "").trim().toLowerCase();

        final boolean[] clientGone = {false};
        final java.lang.Process[] holder = {null};
        Thread drain = new Thread(() -> {
            try {
                byte[] buf = new byte[64];
                while (in.read(buf) >= 0) {
                    // The client sends nothing after its request;
                    // any bytes are ignored, EOF ends the tail.
                }
            } catch (Exception ignored) {
            }
            clientGone[0] = true;
            java.lang.Process p = holder[0];
            if (p != null) p.destroyForcibly();
        }, "control-bridge-logcat-drain");
        drain.setDaemon(true);
        drain.start();

        try {
            if (!writeLineQuiet(out, "{\"ok\":true,\"op\":\"LOGCAT_TAIL\"}")) return;
            java.lang.Process logcat = new ProcessBuilder(
                    "/system/bin/logcat", "-T", "20", "-v", "threadtime")
                    .redirectErrorStream(true)
                    .start();
            holder[0] = logcat;
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    logcat.getInputStream(), StandardCharsets.UTF_8));
            String line;
            int sent = 0;
            while (!clientGone[0] && (line = reader.readLine()) != null) {
                if (!needle.isEmpty() && !line.toLowerCase().contains(needle)) continue;
                JSONObject msg = new JSONObject();
                put(msg, "line", line);
                synchronized (out) {
                    if (!writeLineQuiet(out, msg.toString())) {
                        clientGone[0] = true;
                        break;
                    }
                }
                if (++sent >= maxLines) {
                    JSONObject done = new JSONObject();
                    put(done, "done", true);
                    put(done, "reason", "Line cap reached (" + maxLines + " lines).");
                    writeLineQuiet(out, done.toString());
                    break;
                }
            }
        } catch (Exception e) {
            // logcat failed to start or its stream broke: the
            // client sees the connection close and reports the
            // tail as ended.
        } finally {
            java.lang.Process p = holder[0];
            if (p != null) p.destroyForcibly();
            System.err.println("Logcat tail ended");
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
            // Binary payloads (v6 SCREENSHOT) ride a separate
            // "data" field, outside the capped "text" field, so
            // no generic display ever dumps megabytes of base64.
            String data = null;
            switch (op) {
                case "PING":
                    text = "Control shell bridge is running as UID " + Process.myUid()
                            + " (PID " + Process.myPid() + "). Governed read/control allowlist v" + VERSION + ".";
                    break;
                case "HOLD_WATCH_STATUS": {
                    // Diagnostics (v10): the picked input
                    // device, the frames/holds the current or
                    // last watch saw, whether a watch is
                    // streaming, and any probe failure. The
                    // payload rides the "text" field as JSON,
                    // like every other op's answer. A live
                    // watch's counters come straight from its
                    // parser (read outside the lock); an ended
                    // watch's come from the end snapshot. A v9
                    // bridge has no such op and refuses with
                    // the allowlist error, which the client
                    // treats as "no bridge-side data".
                    HoldEventParser live = activeHoldParser;
                    long liveFrames = live == null ? -1 : live.framesSeen();
                    long liveHolds = live == null ? -1 : live.holdsFired();
                    JSONObject st = new JSONObject();
                    synchronized (HOLD_STATUS_LOCK) {
                        put(st, "active", holdStatusActive);
                        put(st, "device", holdStatusDevice);
                        put(st, "endReason", holdStatusEndReason);
                        put(st, "frames", live == null ? holdStatusFrames : liveFrames);
                        put(st, "holds", live == null ? holdStatusHolds : liveHolds);
                    }
                    text = st.toString();
                    break;
                }
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
                    // v7: the protected-package override. The
                    // client sends "override" only when the
                    // Deep view switch is on; the BRIDGE decides
                    // — a request that omits the field (or an
                    // old client) reads false and refuses
                    // protected packages exactly as before.
                    // Control itself is refused always.
                    boolean override = request.optBoolean("override", false);
                    String pkg = checkedPackageForEnabled(request, override);
                    boolean enabled = request.optBoolean("enabled", true);
                    // v5: disable runs `pm disable-user --user 0`
                    // (the per-user variant shell is allowed —
                    // plain `pm disable` is system-level and was
                    // always refused), and pm's output is
                    // verified before success is reported. A
                    // refusal used to read as success here.
                    String[] command = enabled
                            ? PmOutcome.enableCommand(pkg)
                            : PmOutcome.disableCommand(pkg);
                    String out = runFixed(command);
                    PmOutcome.Result verified = PmOutcome.verify(out, enabled);
                    if (!verified.ok) {
                        throw new Exception(verified.message);
                    }
                    text = (enabled ? "Enabled " : "Disabled ") + pkg
                            + ". " + verified.message
                            + (override && ProtectedOverride.isProtected(pkg)
                                    ? " Protected-package override."
                                    : "");
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
                case "UNINSTALL_USER": {
                    String pkg = checkedPackage(request, true);
                    CmdResult r = runFixedResult(BridgeOps.uninstallUserCommand(pkg));
                    BridgeOps.Outcome verified = BridgeOps.verifyUninstall(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = "Uninstalled " + pkg + " for user 0. " + verified.message
                            + " The app's files stay on the phone — Restore brings it back.";
                    break;
                }
                case "INSTALL_EXISTING": {
                    // No installed-check here: the package is by
                    // definition NOT installed for user 0 — that
                    // is the point. pm itself refuses honestly
                    // when there is nothing to restore.
                    String pkg = request.optString("package", "");
                    if (!PACKAGE_PATTERN.matcher(pkg).matches()) {
                        throw new Exception("Invalid package name.");
                    }
                    if (REFUSE_ALL.contains(pkg)) {
                        throw new Exception("Refused: " + pkg + " is on the bridge refuse-list.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.installExistingCommand(pkg));
                    BridgeOps.Outcome verified = BridgeOps.verifyInstallExisting(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = "Restored " + pkg + " for user 0. " + verified.message;
                    break;
                }
                case "LIST_REMOVED": {
                    String installed = runFixed(BridgeOps.listPackagesCommand(false));
                    String all = runFixed(BridgeOps.listPackagesCommand(true));
                    List<String> removed = BridgeOps.removedPackages(installed, all);
                    StringBuilder joined = new StringBuilder();
                    for (String pkg : removed) {
                        if (joined.length() > 0) joined.append('\n');
                        joined.append(pkg);
                    }
                    text = joined.toString();
                    break;
                }
                case "INSTALL_APK": {
                    String path = request.optString("path", "");
                    if (!BridgeOps.validateApkStagePath(path)) {
                        throw new Exception("Refused: the bridge installs only from "
                                + "its staged path, never from an arbitrary file.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.installApkCommand(path));
                    BridgeOps.Outcome verified = BridgeOps.verifyInstallApk(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = "Installed from the staged APK. " + verified.message;
                    break;
                }
                case "SUSPEND_APP":
                case "UNSUSPEND_APP": {
                    String pkg = checkedPackage(request, false);
                    boolean suspend = "SUSPEND_APP".equals(op);
                    CmdResult r = runFixedResult(BridgeOps.suspendCommand(pkg, suspend));
                    BridgeOps.Outcome verified = BridgeOps.verifySuspend(r.out, r.exit, suspend);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "TRIM_CACHES": {
                    CmdResult r = runFixedResult(
                            BridgeOps.trimCachesCommand(BridgeOps.TRIM_CACHES_TARGET_BYTES));
                    BridgeOps.Outcome verified = BridgeOps.verifyTrimCaches(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "WM_GET": {
                    BridgeOps.WmValue size = BridgeOps.parseWmSize(
                            runFixed(BridgeOps.wmSizeGetCommand()));
                    BridgeOps.WmValue density = BridgeOps.parseWmDensity(
                            runFixed(BridgeOps.wmDensityGetCommand()));
                    BridgeOps.WmValue over = BridgeOps.parseWmOverscan(
                            runFixed(BridgeOps.wmOverscanGetCommand()));
                    text = BridgeOps.composeWmState(size, density, over);
                    break;
                }
                case "WM_SET_SIZE": {
                    int width = request.optInt("width", -1);
                    int height = request.optInt("height", -1);
                    if (!BridgeOps.validSize(width, height)) {
                        throw new Exception("Size is outside the allowed range.");
                    }
                    CmdResult set = runFixedResult(
                            BridgeOps.wmSizeSetCommand(width, height));
                    if (set.exit != 0 || BridgeOps.hasErrorMarker(set.out)) {
                        throw new Exception("Android did not accept the new size: "
                                + shortOut(set.out));
                    }
                    BridgeOps.WmValue after = BridgeOps.parseWmSize(
                            runFixed(BridgeOps.wmSizeGetCommand()));
                    BridgeOps.Outcome verified = BridgeOps.verifyWmSet(
                            after, width + "x" + height, "size");
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "WM_SET_DENSITY": {
                    int density = request.optInt("density", -1);
                    if (!BridgeOps.validDensity(density)) {
                        throw new Exception("Density must be 120–640.");
                    }
                    CmdResult set = runFixedResult(
                            BridgeOps.wmDensitySetCommand(density));
                    if (set.exit != 0 || BridgeOps.hasErrorMarker(set.out)) {
                        throw new Exception("Android did not accept the new density: "
                                + shortOut(set.out));
                    }
                    BridgeOps.WmValue after = BridgeOps.parseWmDensity(
                            runFixed(BridgeOps.wmDensityGetCommand()));
                    BridgeOps.Outcome verified = BridgeOps.verifyWmSet(
                            after, String.valueOf(density), "density");
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "WM_SET_OVERSCAN": {
                    int left = request.optInt("left", -1);
                    int top = request.optInt("top", -1);
                    int right = request.optInt("right", -1);
                    int bottom = request.optInt("bottom", -1);
                    if (!BridgeOps.validOverscan(left, top, right, bottom)) {
                        throw new Exception("Overscan values must be 0–500.");
                    }
                    CmdResult set = runFixedResult(
                            BridgeOps.wmOverscanSetCommand(left, top, right, bottom));
                    if (set.exit != 0 || BridgeOps.hasErrorMarker(set.out)) {
                        throw new Exception("Android did not accept the new overscan: "
                                + shortOut(set.out));
                    }
                    BridgeOps.WmValue after = BridgeOps.parseWmOverscan(
                            runFixed(BridgeOps.wmOverscanGetCommand()));
                    String want = left + "," + top + "," + right + "," + bottom;
                    if (after.physical == null || !after.physical.equals(want)) {
                        throw new Exception("Android did not confirm the new overscan — "
                                + "the re-read did not show " + want + ".");
                    }
                    text = "Overscan set to " + want + ".";
                    break;
                }
                case "SETTING_GET": {
                    BridgeOps.SettingDef def = BridgeOps.findSetting(
                            request.optString("namespace", ""),
                            request.optString("key", ""));
                    if (def == null) {
                        throw new Exception("Setting is not on the bridge allowlist.");
                    }
                    text = runFixed(BridgeOps.settingsGetCommand(def)).trim();
                    break;
                }
                case "SETTING_PUT": {
                    BridgeOps.SettingDef def = BridgeOps.findSetting(
                            request.optString("namespace", ""),
                            request.optString("key", ""));
                    if (def == null) {
                        throw new Exception("Setting is not on the bridge allowlist.");
                    }
                    String value = request.optString("value", "");
                    String refusal = BridgeOps.validateSettingValue(def, value);
                    if (refusal != null) throw new Exception(refusal);
                    CmdResult set = runFixedResult(
                            BridgeOps.settingsPutCommand(def, value.trim()));
                    if (set.exit != 0 || BridgeOps.hasErrorMarker(set.out)) {
                        throw new Exception("Android did not accept the setting: "
                                + shortOut(set.out));
                    }
                    String back = runFixed(BridgeOps.settingsGetCommand(def));
                    if (!BridgeOps.settingReadbackMatches(def, back, value)) {
                        throw new Exception("Android did not confirm the new value — "
                                + "the re-read did not show it.");
                    }
                    text = "Set " + def.label + " to "
                            + BridgeOps.settingDisplay(def, back) + ".";
                    break;
                }
                case "NIGHT_GET": {
                    String mode = BridgeOps.parseNightMode(
                            runFixed(BridgeOps.nightModeGetCommand()));
                    text = mode == null ? "unknown" : mode;
                    break;
                }
                case "NIGHT_SET": {
                    String mode = request.optString("mode", "");
                    if (!BridgeOps.validNightMode(mode)) {
                        throw new Exception("Night mode must be yes, no, or auto.");
                    }
                    CmdResult set = runFixedResult(BridgeOps.nightModeSetCommand(mode));
                    if (set.exit != 0 || BridgeOps.hasErrorMarker(set.out)) {
                        throw new Exception("Android did not accept the night mode: "
                                + shortOut(set.out));
                    }
                    String after = BridgeOps.parseNightMode(
                            runFixed(BridgeOps.nightModeGetCommand()));
                    if (!mode.equals(after)) {
                        throw new Exception("Android did not confirm the night mode "
                                + "change — the current mode could not be read back.");
                    }
                    text = "Night mode set to " + BridgeOps.nightModeDisplay(mode) + ".";
                    break;
                }
                case "INPUT_KEY": {
                    int code = request.optInt("code", -1);
                    if (!BridgeOps.validKeyCode(code)) {
                        throw new Exception("Key code is not on the bridge menu.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.keyEventCommand(code));
                    BridgeOps.Outcome verified = BridgeOps.verifyInput(
                            r.out, r.exit, "Key " + BridgeOps.keyLabel(code));
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "INPUT_TAP": {
                    int x = request.optInt("x", -1);
                    int y = request.optInt("y", -1);
                    if (!BridgeOps.validCoord(x, BridgeOps.COORD_SANITY_MAX)
                            || !BridgeOps.validCoord(y, BridgeOps.COORD_SANITY_MAX)) {
                        throw new Exception("Tap coordinates are outside the allowed range.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.tapCommand(x, y));
                    BridgeOps.Outcome verified = BridgeOps.verifyInput(
                            r.out, r.exit, "Tap at " + x + "," + y);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "INPUT_SWIPE": {
                    int x1 = request.optInt("x1", -1);
                    int y1 = request.optInt("y1", -1);
                    int x2 = request.optInt("x2", -1);
                    int y2 = request.optInt("y2", -1);
                    int ms = request.optInt("durationMs", -1);
                    if (!BridgeOps.validCoord(x1, BridgeOps.COORD_SANITY_MAX)
                            || !BridgeOps.validCoord(y1, BridgeOps.COORD_SANITY_MAX)
                            || !BridgeOps.validCoord(x2, BridgeOps.COORD_SANITY_MAX)
                            || !BridgeOps.validCoord(y2, BridgeOps.COORD_SANITY_MAX)) {
                        throw new Exception("Swipe coordinates are outside the allowed range.");
                    }
                    if (!BridgeOps.validSwipeDuration(ms)) {
                        throw new Exception("Swipe duration must be 100–5000 ms.");
                    }
                    CmdResult r = runFixedResult(
                            BridgeOps.swipeCommand(x1, y1, x2, y2, ms));
                    BridgeOps.Outcome verified = BridgeOps.verifyInput(
                            r.out, r.exit, "Swipe");
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "INPUT_TEXT": {
                    String escaped = request.optString("escaped", "");
                    if (!BridgeOps.validEscapedText(escaped)) {
                        throw new Exception("Text is empty, too long, or contains "
                                + "characters the input tool cannot type.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.inputTextCommand(escaped));
                    BridgeOps.Outcome verified = BridgeOps.verifyInput(
                            r.out, r.exit, "Text entry");
                    if (!verified.ok) throw new Exception(verified.message);
                    // The length only — the typed text itself is
                    // never written to a response, a log, or the
                    // audit record (it may be a password).
                    text = "Typed " + escaped.length() + " characters.";
                    break;
                }
                case "SCREENSHOT": {
                    CmdBytes shot = runFixedBytes(BridgeOps.screencapCommand(),
                            16 * 1024 * 1024);
                    if (shot.exit != 0 || shot.data.length < 8
                            || shot.data[0] != (byte) 0x89 || shot.data[1] != 0x50
                            || shot.data[2] != 0x4E || shot.data[3] != 0x47) {
                        throw new Exception("screencap did not return a PNG image."
                                + (shot.stderr.isEmpty() ? "" : " " + shortOut(shot.stderr)));
                    }
                    data = Base64.encodeToString(shot.data, Base64.NO_WRAP);
                    text = "Screenshot captured (" + BridgeOps.humanSize(shot.data.length) + ").";
                    break;
                }
                case "SCREENRECORD_START": {
                    int seconds = request.optInt("seconds", 0);
                    if (!BridgeOps.validRecordSeconds(seconds)) {
                        throw new Exception("Recording length must be 10, 30, or 60 seconds.");
                    }
                    synchronized (RECORD_LOCK) {
                        if (recordProcess != null && recordProcess.isAlive()) {
                            throw new Exception("A recording is already running — "
                                    + "stop it before starting another.");
                        }
                        runFixed(new String[]{"/system/bin/mkdir", "-p", "/sdcard/Movies"});
                        String path = BridgeOps.recordingPath(System.currentTimeMillis());
                        java.lang.Process p = new ProcessBuilder(
                                BridgeOps.screenrecordCommand(seconds, path))
                                .redirectErrorStream(true)
                                .start();
                        drainStream(p.getInputStream(), "control-bridge-record-drain");
                        recordProcess = p;
                        recordPath = path;
                        text = BridgeOps.composeLongTaskStatus("RUNNING", path, 0);
                    }
                    break;
                }
                case "SCREENRECORD_STATUS": {
                    synchronized (RECORD_LOCK) {
                        text = longTaskStatus(recordProcess, recordPath);
                        if (recordProcess != null && !recordProcess.isAlive()) {
                            recordProcess = null;
                            recordPath = null;
                        }
                    }
                    break;
                }
                case "SCREENRECORD_STOP": {
                    synchronized (RECORD_LOCK) {
                        if (recordProcess == null) {
                            text = BridgeOps.composeLongTaskStatus("NONE", null, -1);
                        } else {
                            boolean clean = stopProcess(recordProcess);
                            long size = fileSize(recordPath);
                            String path = recordPath;
                            recordProcess = null;
                            recordPath = null;
                            text = BridgeOps.composeLongTaskStatus("FINISHED", path, size)
                                    + (clean ? ""
                                            : "\nnote=The recorder had to be force-stopped — "
                                            + "the file may not play.");
                        }
                    }
                    break;
                }
                case "BUGREPORT_START": {
                    synchronized (BUGREPORT_LOCK) {
                        if (bugreportProcess != null && bugreportProcess.isAlive()) {
                            throw new Exception("A bugreport is already running — "
                                    + "stop it before starting another.");
                        }
                        runFixed(new String[]{"/system/bin/mkdir", "-p", "/sdcard/Download"});
                        String path = BridgeOps.bugreportPath(System.currentTimeMillis());
                        java.lang.Process p = new ProcessBuilder(BridgeOps.bugreportCommand())
                                .redirectOutput(new File(path))
                                .start();
                        drainStream(p.getErrorStream(), "control-bridge-bugreport-drain");
                        bugreportProcess = p;
                        bugreportPath = path;
                        text = BridgeOps.composeLongTaskStatus("RUNNING", path, 0);
                    }
                    break;
                }
                case "BUGREPORT_STATUS": {
                    synchronized (BUGREPORT_LOCK) {
                        text = longTaskStatus(bugreportProcess, bugreportPath);
                        if (bugreportProcess != null && !bugreportProcess.isAlive()) {
                            bugreportProcess = null;
                            bugreportPath = null;
                        }
                    }
                    break;
                }
                case "BUGREPORT_STOP": {
                    synchronized (BUGREPORT_LOCK) {
                        if (bugreportProcess == null) {
                            text = BridgeOps.composeLongTaskStatus("NONE", null, -1);
                        } else {
                            stopProcess(bugreportProcess);
                            long size = fileSize(bugreportPath);
                            String path = bugreportPath;
                            bugreportProcess = null;
                            bugreportPath = null;
                            text = BridgeOps.composeLongTaskStatus("FINISHED", path, size)
                                    + "\nnote=Stopped early — the report is partial.";
                        }
                    }
                    break;
                }
                case "DOZE": {
                    String action = request.optString("action", "");
                    if (!BridgeOps.validDozeAction(action)) {
                        throw new Exception("Doze action is not on the bridge allowlist.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.dozeCommand(action));
                    String stateAfter = null;
                    try {
                        stateAfter = BridgeOps.parseDeviceIdleState(
                                runFixed(BridgeOps.deviceIdleStateCommand()));
                    } catch (Exception ignored) {
                        // The re-read is informational; the
                        // command's own result decides success.
                    }
                    BridgeOps.Outcome verified = BridgeOps.verifyDoze(
                            r.out, r.exit, action, stateAfter);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "DEVICEIDLE_STATE": {
                    String state = BridgeOps.parseDeviceIdleState(
                            runFixed(BridgeOps.deviceIdleStateCommand()));
                    text = state == null ? "unknown" : state;
                    break;
                }
                case "BATTERY_GET": {
                    text = runFixed(BridgeOps.batteryGetCommand());
                    break;
                }
                case "BATTERY_SET_LEVEL": {
                    int level = request.optInt("level", -1);
                    if (!BridgeOps.validBatteryLevel(level)) {
                        throw new Exception("Battery level must be 0–100.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.batterySetLevelCommand(level));
                    if (r.exit != 0 || BridgeOps.hasErrorMarker(r.out)) {
                        throw new Exception("Android did not accept the simulated level: "
                                + shortOut(r.out));
                    }
                    BridgeOps.BatteryInfo after = BridgeOps.parseBattery(
                            runFixed(BridgeOps.batteryGetCommand()));
                    BridgeOps.Outcome verified = BridgeOps.verifyBatteryLevel(after, level);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "BATTERY_UNPLUG": {
                    CmdResult r = runFixedResult(BridgeOps.batteryUnplugCommand());
                    if (r.exit != 0 || BridgeOps.hasErrorMarker(r.out)) {
                        throw new Exception("Android did not accept the unplug simulation: "
                                + shortOut(r.out));
                    }
                    text = "Simulated unplug: the battery now reports discharging. "
                            + "Reset returns to the real state.";
                    break;
                }
                case "BATTERY_RESET": {
                    CmdResult r = runFixedResult(BridgeOps.batteryResetCommand());
                    if (r.exit != 0 || BridgeOps.hasErrorMarker(r.out)) {
                        throw new Exception("Android did not accept the battery reset: "
                                + shortOut(r.out));
                    }
                    text = "Battery simulation reset — the real battery state is reported again.";
                    break;
                }
                case "STANDBY_GET": {
                    String pkg = checkedPackage(request, false);
                    String bucket = BridgeOps.parseStandbyBucket(
                            runFixed(BridgeOps.standbyGetCommand(pkg)));
                    text = bucket == null ? "unknown" : bucket;
                    break;
                }
                case "STANDBY_SET": {
                    String pkg = checkedPackage(request, false);
                    String bucket = request.optString("bucket", "");
                    if (!BridgeOps.STANDBY_BUCKETS.containsKey(bucket)) {
                        throw new Exception("Standby bucket is not on the bridge allowlist.");
                    }
                    CmdResult r = runFixedResult(BridgeOps.standbySetCommand(pkg, bucket));
                    if (r.exit != 0 || BridgeOps.hasErrorMarker(r.out)) {
                        throw new Exception("Android did not accept the standby bucket: "
                                + shortOut(r.out));
                    }
                    String after = BridgeOps.parseStandbyBucket(
                            runFixed(BridgeOps.standbyGetCommand(pkg)));
                    if (!bucket.equals(after)) {
                        throw new Exception("Android did not confirm the standby bucket — "
                                + "the re-read did not show " + bucket + ".");
                    }
                    text = "Standby bucket for " + pkg + " is now " + bucket + ".";
                    break;
                }
                case "RUN_JOB": {
                    String pkg = checkedPackage(request, false);
                    String rawId = request.optString("jobId", "");
                    if (!BridgeOps.validJobId(rawId)) {
                        throw new Exception("Job id must be a non-negative whole number.");
                    }
                    CmdResult r = runFixedResult(
                            BridgeOps.runJobCommand(pkg, Integer.parseInt(rawId.trim())));
                    BridgeOps.Outcome verified = BridgeOps.verifyRunJob(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "DEVICE_CONFIG_LIST": {
                    String namespace = request.optString("namespace", "").trim();
                    if (!namespace.isEmpty() && !BridgeOps.validNamespace(namespace)) {
                        throw new Exception("Invalid device_config namespace.");
                    }
                    text = runFixed(BridgeOps.deviceConfigListCommand(
                            namespace.isEmpty() ? null : namespace));
                    break;
                }
                case "DEVICE_CONFIG_GET": {
                    String namespace = request.optString("namespace", "");
                    String key = request.optString("key", "");
                    if (!BridgeOps.validNamespace(namespace)) {
                        throw new Exception("Invalid device_config namespace.");
                    }
                    if (!BridgeOps.validConfigKey(key)) {
                        throw new Exception("Invalid device_config key.");
                    }
                    text = runFixed(BridgeOps.deviceConfigGetCommand(namespace, key)).trim();
                    break;
                }
                case "DEVICE_CONFIG_PUT": {
                    String namespace = request.optString("namespace", "");
                    String key = request.optString("key", "");
                    String value = request.optString("value", "");
                    if (!BridgeOps.validNamespace(namespace)) {
                        throw new Exception("Invalid device_config namespace.");
                    }
                    if (!BridgeOps.validConfigKey(key)) {
                        throw new Exception("Invalid device_config key.");
                    }
                    if (!BridgeOps.validConfigValue(value)) {
                        throw new Exception(
                                "Value must be a single line of at most 512 characters.");
                    }
                    CmdResult set = runFixedResult(
                            BridgeOps.deviceConfigPutCommand(namespace, key, value));
                    if (set.exit != 0 || BridgeOps.hasErrorMarker(set.out)) {
                        throw new Exception("Android did not accept the flag: "
                                + shortOut(set.out));
                    }
                    String back = runFixed(
                            BridgeOps.deviceConfigGetCommand(namespace, key));
                    // An empty value clears the flag: the re-read
                    // then shows "null", which is the confirmation.
                    boolean confirmed = value.isEmpty()
                            ? back.trim().isEmpty() || "null".equals(back.trim())
                            : BridgeOps.configReadbackMatches(back, value);
                    if (!confirmed) {
                        throw new Exception("Android did not confirm the flag — "
                                + "the re-read did not show the new value.");
                    }
                    text = "Set " + namespace + " / " + key + " to \"" + value
                            + "\" (verified by re-read).";
                    break;
                }
                case "WIFI_LIST": {
                    text = runFixed(BridgeOps.wifiListCommand());
                    break;
                }
                case "WIFI_FORGET": {
                    String rawId = request.optString("networkId", "");
                    if (!BridgeOps.validNetworkId(rawId)) {
                        throw new Exception("Network id must be a non-negative whole number.");
                    }
                    CmdResult r = runFixedResult(
                            BridgeOps.wifiForgetCommand(Integer.parseInt(rawId.trim())));
                    BridgeOps.Outcome verified = BridgeOps.verifyWifiForget(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "USERS_LIST": {
                    text = runFixed(BridgeOps.listUsersCommand());
                    break;
                }
                case "CURRENT_USER": {
                    int current = BridgeOps.parseCurrentUser(
                            runFixed(BridgeOps.currentUserCommand()));
                    text = String.valueOf(current);
                    break;
                }
                case "USER_CREATE": {
                    String name = request.optString("name", "");
                    if (!BridgeOps.validUserName(name)) {
                        throw new Exception("User name must be 1–40 characters, "
                                + "no leading dash, no newlines.");
                    }
                    CmdResult r = runFixedResult(
                            BridgeOps.createUserCommand(name.trim()));
                    int id = BridgeOps.parseCreateUser(r.out);
                    if (r.exit != 0 || id < 0) {
                        throw new Exception("Android did not confirm the new user: "
                                + shortOut(r.out));
                    }
                    text = "Created user \"" + name.trim() + "\" (id " + id + ").";
                    break;
                }
                case "USER_REMOVE": {
                    int userId = request.optInt("userId", -1);
                    if (userId < 0) throw new Exception("Invalid user id.");
                    int current = -1;
                    try {
                        current = BridgeOps.parseCurrentUser(
                                runFixed(BridgeOps.currentUserCommand()));
                    } catch (Exception ignored) {
                        // Unreadable current user: only user 0 is
                        // refused here; Android itself refuses
                        // removing the current user as the backstop.
                    }
                    String refusal = BridgeOps.refuseRemoveUser(userId, current);
                    if (refusal != null) throw new Exception(refusal);
                    CmdResult r = runFixedResult(BridgeOps.removeUserCommand(userId));
                    BridgeOps.Outcome verified = BridgeOps.verifyRemoveUser(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "LAUNCH_ACTIVITY": {
                    String pkg = request.optString("package", "");
                    String cls = request.optString("class", "");
                    if (!PACKAGE_PATTERN.matcher(pkg).matches()) {
                        throw new Exception("Invalid package name.");
                    }
                    String resolved = BridgeOps.resolveClassName(pkg, cls);
                    if (!BridgeOps.validClassName(resolved)) {
                        throw new Exception("Invalid activity class name.");
                    }
                    CmdResult r = runFixedResult(
                            BridgeOps.launchActivityCommand(pkg, resolved));
                    BridgeOps.Outcome verified = BridgeOps.verifyAmStart(r.out, r.exit);
                    if (!verified.ok) throw new Exception(verified.message);
                    text = verified.message;
                    break;
                }
                case "DUMPSYS": {
                    String service = request.optString("service", "");
                    if (!BridgeOps.validDumpsysService(service)) {
                        throw new Exception("Dumpsys service is not on the bridge menu.");
                    }
                    // 14 seconds: dumpsys can be slow, and the
                    // client waits 15 — the timeout error stays
                    // honest if the service does not answer in time.
                    text = runFixed(BridgeOps.dumpsysCommand(service), 14);
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
            if (data != null) put(response, "data", data);
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
        requireInstalled(pkg);
        return pkg;
    }

    /** SET_APP_ENABLED's package check (v7): the same pattern
     *  and installed checks as checkedPackage, but the
     *  refuse-lists go through ProtectedOverride, so the
     *  override permits protected packages and Control itself
     *  stays refused always. No other op uses this path. */
    private static String checkedPackageForEnabled(JSONObject request, boolean override)
            throws Exception {
        String pkg = request.optString("package", "");
        if (!PACKAGE_PATTERN.matcher(pkg).matches()) {
            throw new Exception("Invalid package name.");
        }
        ProtectedOverride.Decision decision =
                ProtectedOverride.decideEnable(pkg, override);
        if (decision != ProtectedOverride.Decision.ALLOW) {
            throw new Exception(ProtectedOverride.refusalText(pkg, decision));
        }
        requireInstalled(pkg);
        return pkg;
    }

    private static void requireInstalled(String pkg) throws Exception {
        String path = runFixed(new String[]{"/system/bin/pm", "path", pkg});
        if (!path.trim().startsWith("package:")) {
            throw new Exception("Refused: " + pkg + " is not installed.");
        }
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

    /** A finished fixed command: merged stdout+stderr text
     *  plus the exit code. The v6 verifiers need both — pm's
     *  words AND its exit status decide success. */
    private static final class CmdResult {
        final String out;
        final int exit;

        CmdResult(String out, int exit) {
            this.out = out;
            this.exit = exit;
        }
    }

    /** A finished fixed command that returns bytes
     *  (screencap): raw stdout, the exit code, and stderr
     *  kept SEPARATE, because merging it would corrupt the
     *  PNG. */
    private static final class CmdBytes {
        final byte[] data;
        final int exit;
        final String stderr;

        CmdBytes(byte[] data, int exit, String stderr) {
            this.data = data;
            this.exit = exit;
            this.stderr = stderr;
        }
    }

    private static String runFixed(String[] command) throws Exception {
        return runFixedResult(command, 12).out;
    }

    private static String runFixed(String[] command, int timeoutSeconds) throws Exception {
        return runFixedResult(command, timeoutSeconds).out;
    }

    private static CmdResult runFixedResult(String[] command) throws Exception {
        return runFixedResult(command, 12);
    }

    private static CmdResult runFixedResult(String[] command, int timeoutSeconds)
            throws Exception {
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
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new Exception("The fixed allowlisted command timed out.");
        }
        reader.join(1000);
        int exit;
        try {
            exit = process.exitValue();
        } catch (Exception e) {
            exit = -1;
        }
        synchronized (captured) {
            return new CmdResult(
                    new String(captured.toByteArray(), StandardCharsets.UTF_8), exit);
        }
    }

    private static CmdBytes runFixedBytes(String[] command, int maxBytes) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ByteArrayOutputStream errCaptured = new ByteArrayOutputStream();
        Thread outReader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (captured) {
                        if (captured.size() < maxBytes) {
                            captured.write(buffer, 0, Math.min(n, maxBytes - captured.size()));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "control-bridge-command-bytes");
        Thread errReader = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    synchronized (errCaptured) {
                        if (errCaptured.size() < 4096) {
                            errCaptured.write(buffer, 0,
                                    Math.min(n, 4096 - errCaptured.size()));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "control-bridge-command-stderr");
        outReader.start();
        errReader.start();
        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new Exception("The fixed allowlisted command timed out.");
        }
        outReader.join(1000);
        errReader.join(1000);
        int exit;
        try {
            exit = process.exitValue();
        } catch (Exception e) {
            exit = -1;
        }
        byte[] data;
        String stderr;
        synchronized (captured) {
            data = captured.toByteArray();
        }
        synchronized (errCaptured) {
            stderr = new String(errCaptured.toByteArray(), StandardCharsets.UTF_8);
        }
        return new CmdBytes(data, exit, stderr);
    }

    /** Tool output trimmed for an error line: short, never
     *  empty, never a dump. */
    private static String shortOut(String out) {
        if (out == null) return "(no output)";
        String trimmed = out.trim();
        if (trimmed.isEmpty()) return "(no output)";
        if (trimmed.length() > 200) trimmed = trimmed.substring(0, 200) + "…";
        return trimmed;
    }

    /** File size in bytes, or -1 when the file does not exist. */
    private static long fileSize(String path) {
        if (path == null) return -1;
        File file = new File(path);
        return file.isFile() ? file.length() : -1;
    }

    /** Discard a long-running process's stream on a daemon
     *  thread, so a full pipe can never block it. */
    private static void drainStream(final InputStream stream, String name) {
        Thread t = new Thread(() -> {
            try (InputStream in = stream) {
                byte[] buffer = new byte[8192];
                while (in.read(buffer) >= 0) {
                    // Discarded: the file is the output.
                }
            } catch (Exception ignored) {
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    /** The status text for a start-and-poll slot: NONE when
     *  no task was ever started, RUNNING with the live file
     *  size while it runs, FINISHED with the final size once
     *  the process has exited. */
    private static String longTaskStatus(java.lang.Process process, String path) {
        if (process == null) {
            return BridgeOps.composeLongTaskStatus("NONE", null, -1);
        }
        if (process.isAlive()) {
            return BridgeOps.composeLongTaskStatus("RUNNING", path, fileSize(path));
        }
        return BridgeOps.composeLongTaskStatus("FINISHED", path, fileSize(path));
    }

    /** End a long-running process: SIGTERM first (screenrecord
     *  finalizes its file on a clean stop), force only if it
     *  does not exit within 3 seconds. Returns true when the
     *  process exited on the gentle stop. */
    private static boolean stopProcess(java.lang.Process process) {
        process.destroy();
        try {
            if (process.waitFor(3, TimeUnit.SECONDS)) return true;
        } catch (Exception ignored) {
        }
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        return false;
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
