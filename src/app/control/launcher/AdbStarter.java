package app.control.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computer-free bridge start (v1.1): the same job the one-line adb
 * command does, driven from inside the app.
 *
 *   1. If the bridge already answers, done.
 *   2. Discover the phone's wireless-debugging connect service
 *      (_adb-tls-connect._tcp) via mDNS — Android requires wireless
 *      debugging to be ON, which only the user can toggle.
 *   3. Connect with Control's own ADB key (AdbClient) and run the
 *      exact starter line the computer command runs
 *      (ShellBridgeClient.shellStarterCommand).
 *   4. Poll the bridge for up to ~10 seconds until it answers.
 *
 * Discovery is FRESH on every attempt (v1.3, from the Android 16
 * field report): the connect service's port rotates whenever
 * wireless debugging restarts, so no discovery result is ever
 * reused — including the one a pairing just made — and a failed
 * connect earns one re-discovery + retry. The three stages
 * (discover / connect+starter / bridge answer) are logged
 * distinctly; {@link #diagnose} reads the bridge's own log over ADB
 * when stage 3 fails.
 *
 * Step 3 only succeeds if Control's key is authorized on the phone —
 * i.e. paired. Pairing itself is {@link #pair}: the full AOSP
 * handshake (TLS 1.3 via Bouncy Castle, SPAKE2 over the 6-digit code,
 * encrypted PeerInfo exchange) implemented in {@link AdbPairing}.
 * This flow honestly reports "not paired" when authorization fails
 * instead of pretending.
 *
 * Every start attempt and its outcome is written to the shared audit
 * log with origin UI, like the other bridge events.
 */
public final class AdbStarter {

    public interface Callback {
        void onStatus(String line);
        void onDone(boolean bridgeUp, String summary);
    }

    public interface DiagnoseCallback {
        void onStatus(String line);
        /** The verbatim report block for the on-screen readout. */
        void onDone(String report);
    }

    /** Callback for one terminal command (v1.6). */
    public interface ShellCallback {
        void onStatus(String line);
        /**
         * @param ok      true when the shell service opened and the
         *                command ran (output may legitimately be
         *                empty — the ADB shell service reports no
         *                exit status, so "ran" is all that is
         *                claimed); false when nothing ran, with the
         *                reason in {@code error}.
         * @param output  verbatim shell output (stdout+stderr as the
         *                channel delivered them), token-redacted.
         * @param error   the failure reason, verbatim where it came
         *                from AdbClient (pair-again wording included
         *                when that is the reading).
         */
        void onDone(boolean ok, String output, String error);
    }

    private AdbStarter() {}

    /**
     * Start the bridge from this phone. The three stages are reported
     * (on-screen log + audit) distinctly, because the v1.2 field
     * failure hid somewhere between them:
     *   stage 1 — DISCOVER: a FRESH _adb-tls-connect discovery on
     *             every attempt (ports rotate whenever wireless
     *             debugging restarts; a remembered port is a bug, and
     *             a post-pairing start never reuses a discovery made
     *             before the pairing);
     *   stage 2 — CONNECT + STARTER: one retry on a second fresh
     *             discovery when the connect itself fails, then the
     *             starter line;
     *   stage 3 — BRIDGE ANSWER: poll for up to ~10 seconds (Android
     *             16 spin-up can lag) before declaring failure.
     *
     * The already-running early return is deliberate: a restart
     * must never kill a healthy bridge. But it means this method
     * cannot fix a STALE bridge (one that answers PING while
     * running old code — see BridgeGate): callers that know the
     * bridge may be stale must use {@link #restartStaleBridge},
     * which stops the old bridge first.
     */
    public static void restartBridge(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String cmd = "BRIDGE START (ADB)";
            ShellBridgeClient.Response ping = ShellBridgeClient.ping(app);
            if (ping.ok) {
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                        "Already running (UID " + ping.bridgeUid + ")");
                // Deep view is on (v1.16): the bridge is up and
                // the user asked for it — the watchdog may keep
                // it up from here.
                Prefs.get(app).edit().putBoolean(Prefs.KEY_DEEP_VIEW_ON, true).apply();
                BridgeWatchdogService.kick(app);
                cb.onDone(true, "Deep view is already on — the bridge is running.");
                return;
            }
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                    "Start requested from Deep view");
            cb.onStatus("Stage 1/3 — looking for this phone's wireless-debugging "
                    + "service (fresh discovery)…");
            Found found = discover(app, AdbMdns.TYPE_CONNECT, 15000);
            if (found.error != null) {
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                        "FAILED at stage DISCOVER: " + found.error);
                cb.onDone(false, "Stage 1 (discovery) failed: " + found.error);
                return;
            }
            if (found.host == null) {
                String msg = "No wireless-debugging service found. Android only "
                        + "offers it while Wireless debugging is ON: Developer "
                        + "options → Wireless debugging. Turn it on, then tap "
                        + "Restart deep view again.";
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                        "FAILED at stage DISCOVER: no connect service "
                                + "(wireless debugging off?)");
                cb.onDone(false, msg);
                return;
            }
            cb.onStatus("Stage 1/3 ✓ Debugging service at "
                    + found.host.getHostAddress() + ":" + found.port);
            cb.onStatus("Stage 2/3 — connecting with Control's key and "
                    + "sending the starter…");
            AdbClient.Result r = runShellPreferLocalhost(app, found,
                    ShellBridgeClient.shellStarterCommand(app), 25000, cb);
            if (!r.connected && !r.authorized) {
                // The connect itself failed — the port may have rotated
                // between discovery and connect. One fresh-discovery retry.
                cb.onStatus("Stage 2/3 — connect failed; re-discovering once "
                        + "(ports rotate)…");
                Found again = discover(app, AdbMdns.TYPE_CONNECT, 15000);
                if (again.host != null) {
                    found = again;
                    cb.onStatus("Stage 2/3 — retrying against "
                            + found.host.getHostAddress() + ":" + found.port + "…");
                    r = runShellPreferLocalhost(app, found,
                            ShellBridgeClient.shellStarterCommand(app), 25000, cb);
                }
            }
            if (!r.authorized) {
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge",
                        found.host.getHostAddress() + ":" + found.port,
                        "FAILED at stage CONNECT: " + r.error);
                cb.onDone(false, "Stage 2 (connect) failed: " + r.error);
                return;
            }
            Prefs.get(app).edit().putBoolean(Prefs.KEY_ADB_PAIRED, true).apply();
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge",
                    found.host.getHostAddress() + ":" + found.port,
                    "Stages DISCOVER + CONNECT ok — starter delivered "
                            + "over in-app ADB");
            cb.onStatus("Stage 2/3 ✓ Connected to debugging service — starter sent");
            cb.onStatus("Stage 3/3 — waiting for the bridge to answer "
                    + "(up to ~10 seconds)…");
            for (int i = 0; i < 10; i++) {
                try { Thread.sleep(1000); } catch (Exception ignored) {}
                ShellBridgeClient.Response p2 = ShellBridgeClient.ping(app);
                if (p2.ok) {
                    SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                            "Stage BRIDGE-ANSWER ok after ~" + (i + 1)
                                    + "s (UID " + p2.bridgeUid + ")");
                    cb.onStatus("Stage 3/3 ✓ Bridge answered — running as "
                            + "shell UID " + p2.bridgeUid);
                    // Deep view is on (v1.16): the start
                    // succeeded — the watchdog may keep the
                    // bridge up from here.
                    Prefs.get(app).edit().putBoolean(Prefs.KEY_DEEP_VIEW_ON, true).apply();
                    BridgeWatchdogService.kick(app);
                    cb.onDone(true, "Deep view is on — bridge started from this "
                            + "phone, no computer. (It still stops at reboot; "
                            + "auto-restart brings it back by itself while "
                            + "wireless debugging stays on.)");
                    return;
                }
            }
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                    "FAILED at stage BRIDGE-ANSWER: starter delivered but "
                            + "the bridge did not answer within ~10s");
            cb.onDone(false, "Stages 1–2 worked — Control connected and the "
                    + "starter was sent — but the bridge did not answer within "
                    + "~10 seconds. Tap Diagnose below: it reads the bridge's "
                    + "own log over ADB and shows it here, verbatim.");
        }, "adb-starter").start();
    }

    /**
     * Restart a STALE bridge (v1.15.1). A stale bridge answers
     * PING, so plain {@link #restartBridge} would early-return
     * "already running" and the old code would keep running
     * forever — and merely re-sending the starter cannot help
     * either, because the new bridge process could not bind
     * the socket the old one holds. So this stops the old
     * bridge first (STOP_BRIDGE, the same audited op Settings'
     * "Turn off deep control" uses), waits until the ping
     * fails, then runs the normal starter flow. A current
     * bridge is left alone (the "already running" early return
     * stays for the genuinely-current case — a restart must
     * never kill a healthy bridge), and a down bridge goes
     * straight to the starter. The completion callback only
     * reports success when a fresh ping shows version >=
     * BridgeGate.HOLD_WATCH_VERSION.
     */
    public static void restartStaleBridge(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            ShellBridgeClient.Response ping = ShellBridgeClient.ping(app);
            if (ping.ok && ping.bridgeVersion >= BridgeGate.HOLD_WATCH_VERSION) {
                // Deep view is on (v1.16): the bridge is current
                // and the user asked for it.
                Prefs.get(app).edit().putBoolean(Prefs.KEY_DEEP_VIEW_ON, true).apply();
                BridgeWatchdogService.kick(app);
                cb.onDone(true, "Deep view is already on — the bridge is current ("
                        + BridgeGate.versionName(ping.bridgeVersion) + ").");
                return;
            }
            if (ping.ok) {
                cb.onStatus("The bridge is running old code ("
                        + BridgeGate.versionName(ping.bridgeVersion)
                        + ") — stopping it so the current code can start…");
                BridgeControl.stopBridge(app, BridgeControl.ORIGIN_UI);
                boolean down = false;
                for (int i = 0; i < 16; i++) {
                    try { Thread.sleep(250); } catch (Exception ignored) {}
                    if (!ShellBridgeClient.ping(app).ok) {
                        down = true;
                        break;
                    }
                }
                if (!down) {
                    cb.onDone(false, "The old bridge did not stop. Turn it off "
                            + "in Settings → Deep view → “Turn off deep "
                            + "control”, then restart it from Deep view.");
                    return;
                }
            }
            restartBridge(app, new Callback() {
                @Override public void onStatus(String line) {
                    cb.onStatus(line);
                }

                @Override public void onDone(boolean up, String summary) {
                    if (!up) {
                        cb.onDone(false, summary);
                        return;
                    }
                    // The starter only proves a bridge answers.
                    // Prove it is the CURRENT code before
                    // claiming success.
                    ShellBridgeClient.Response p2 = ShellBridgeClient.ping(app);
                    if (p2.ok && p2.bridgeVersion >= BridgeGate.HOLD_WATCH_VERSION) {
                        cb.onDone(true, summary);
                    } else if (p2.ok) {
                        cb.onDone(false, "The bridge came back up but is still "
                                + "running old code ("
                                + BridgeGate.versionName(p2.bridgeVersion)
                                + "). Stop it in Settings → Deep view, then "
                                + "restart it from Deep view.");
                    } else {
                        cb.onDone(false, "The bridge answered during the "
                                + "restart but is not answering now. Try "
                                + "the restart again from Deep view.");
                    }
                }
            });
        }, "adb-stale-restart").start();
    }

    // ---- diagnose ----

    /** Read-only diagnostics over the ADB shell channel (the bridge
     *  itself is down — that's the point): the bridge log's tail,
     *  whether an app_process for ShellBridgeMain is alive, and the
     *  Android version. The output is shown verbatim on screen so it
     *  can be read back or screenshotted. The run is audit-logged. */
    private static final String DIAGNOSE_COMMAND =
            "echo '=== Android version ==='; "
            + "getprop ro.build.version.release; getprop ro.build.version.sdk; "
            + "echo '=== bridge log: /data/local/tmp/control-bridge.log (last 60 lines) ==='; "
            + "tail -n 60 /data/local/tmp/control-bridge.log 2>&1; "
            + "echo '=== app_process / bridge processes (ps -A) ==='; "
            + "ps -A 2>/dev/null | grep -i -e app_process -e shellbridge -e control; "
            + "echo '=== pm path app.control.launcher (the starter classpath source) ==='; "
            + "pm path app.control.launcher; "
            + "echo '=== end of diagnostics ==='";

    public static void diagnose(final Context ctx, final DiagnoseCallback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String cmd = "BRIDGE DIAGNOSE (ADB)";
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                    "Diagnose requested from Deep view");
            ShellBridgeClient.Response ping = ShellBridgeClient.ping(app);
            if (ping.ok) {
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                        "Diagnose skipped: bridge is answering (UID "
                                + ping.bridgeUid + ")");
                cb.onDone("The bridge is answering (shell UID " + ping.bridgeUid
                        + ", PID " + ping.bridgePid + ") — there is nothing "
                        + "to diagnose. If a control misbehaves, its own "
                        + "result line says what happened.");
                return;
            }
            cb.onStatus("Diagnose: fresh discovery of the debugging service…");
            Found found = discover(app, AdbMdns.TYPE_CONNECT, 15000);
            if (found.host == null) {
                String why = found.error != null ? found.error
                        : "no _adb-tls-connect service found — is Wireless "
                                + "debugging ON?";
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge", "",
                        "Diagnose could not discover: " + why);
                cb.onDone("Diagnose could not run: " + why);
                return;
            }
            cb.onStatus("Diagnose: running read-only checks over ADB at "
                    + found.host.getHostAddress() + ":" + found.port
                    + " (preferring 127.0.0.1)…");
            AdbClient.Result r = runShellPreferLocalhost(app, found,
                    DIAGNOSE_COMMAND, 25000, null);
            if (!r.authorized) {
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge",
                        found.host.getHostAddress() + ":" + found.port,
                        "Diagnose blocked at ADB connect: " + r.error);
                cb.onDone("Diagnose could not open the ADB shell channel: "
                        + r.error);
                return;
            }
            String out = r.output == null ? "" : r.output.trim();
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell bridge",
                    found.host.getHostAddress() + ":" + found.port,
                    "Diagnose ran read-only checks (" + out.length()
                            + " chars of output shown on screen)");
            cb.onDone(out.isEmpty()
                    ? "Diagnose ran, but the shell returned no output at all."
                    : out);
        }, "adb-diagnose").start();
    }

    // ---- terminal: one typed command over the ADB shell channel ----

    /**
     * Run ONE command over the ADB shell channel (v1.6, for
     * TerminalActivity) — the same channel a computer's
     * {@code adb shell} uses, so the command runs as the shell user.
     * Each call is its own shell-service invocation over a fresh
     * ADB connection: there is no persistent session, and nothing
     * carries over between commands.
     *
     * The discovery + retry shape mirrors {@link #restartBridge}:
     * fresh discovery on every call, one re-discovery + retry when
     * the connect itself fails (ports rotate). The failure paths
     * report honestly: no service → wireless-debugging guidance; a
     * rejected key → AdbClient's pair-again wording, verbatim.
     *
     * Audit: every invocation is written to the shared audit log
     * with origin UI under "TERMINAL (ADB)", with the command and
     * the outcome token-redacted (see {@link AdbShell}).
     */
    public static void runShellCommand(final Context ctx, final String command,
                                       final ShellCallback cb) {
        final Context app = ctx.getApplicationContext();
        final String token = ShellBridgeClient.token(app);
        final String shown = AdbShell.redact(command, token);
        new Thread(() -> {
            String cmd = "TERMINAL (ADB)";
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell (ADB)",
                    shown, "Terminal command requested");
            cb.onStatus("Looking for this phone's wireless-debugging service…");
            Found found = discover(app, AdbMdns.TYPE_CONNECT, 15000);
            if (found.host == null) {
                String why = found.error != null ? found.error
                        : "No wireless-debugging service found. Android only "
                                + "offers it while Wireless debugging is ON: "
                                + "Developer options → Wireless debugging. "
                                + "Turn it on, then run the command again.";
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell (ADB)",
                        shown, "FAILED at discovery: " + why);
                cb.onDone(false, "", why);
                return;
            }
            cb.onStatus("Connected to the debugging service at "
                    + found.host.getHostAddress() + ":" + found.port + " — running…");
            AdbClient.Result r = runShellPreferLocalhost(app, found, command, 25000,
                    new Callback() {
                        @Override public void onStatus(String line) {
                            cb.onStatus(line);
                        }
                        @Override public void onDone(boolean up, String summary) {}
                    });
            if (!r.connected && !r.authorized) {
                // The connect itself failed — the port may have
                // rotated between discovery and connect. One fresh
                // re-discovery + retry, as in restartBridge.
                cb.onStatus("Connect failed; re-discovering once (ports rotate)…");
                Found again = discover(app, AdbMdns.TYPE_CONNECT, 15000);
                if (again.host != null) {
                    found = again;
                    cb.onStatus("Retrying against "
                            + found.host.getHostAddress() + ":" + found.port + "…");
                    r = runShellPreferLocalhost(app, found, command, 25000,
                            new Callback() {
                                @Override public void onStatus(String line) {
                                    cb.onStatus(line);
                                }
                                @Override public void onDone(boolean up, String summary) {}
                            });
                }
            }
            if (!r.authorized) {
                SmsControl.logDetailed(app, "UI", "", cmd, "Shell (ADB)",
                        shown, "FAILED at ADB connect: "
                                + AdbShell.redact(r.error, token));
                cb.onDone(false, "", r.error);
                return;
            }
            Prefs.get(app).edit().putBoolean(Prefs.KEY_ADB_PAIRED, true).apply();
            String out = r.output == null ? "" : r.output;
            SmsControl.logDetailed(app, "UI", "", cmd, "Shell (ADB)",
                    shown, "Ran over in-app ADB (" + out.length()
                            + " chars of output shown on screen)");
            cb.onDone(true, AdbShell.redact(out, token), "");
        }, "adb-terminal").start();
    }

    /**
     * The pairing flow (v1.2 — real, not a stub). Discovers the
     * pairing service, then runs the full AOSP handshake via
     * {@link AdbPairing}: TLS 1.3 with Control's stored certificate,
     * SPAKE2 keyed by the 6-digit code plus the TLS exporter secret,
     * and the encrypted PeerInfo exchange that hands the phone
     * Control's RSA public key. Stage outcomes stream to the callback
     * as log lines (stage names + error classes only — the code and
     * all key material never leave {@link AdbPairing}). On success
     * the paired flag is persisted and the existing connect path
     * ({@link #restartBridge}) takes over immediately: pair once →
     * bridge starts, no computer. The code is used in memory only
     * and never stored.
     */
    public static void pair(final Context ctx, final String code, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String cmd = "ADB PAIR";
            SmsControl.logDetailed(app, "UI", "", cmd, "This phone", "",
                    "Pairing requested from Deep view");
            cb.onStatus("Looking for this phone's pairing service…");
            Found found = discover(app, AdbMdns.TYPE_PAIRING, 15000);
            if (found.error != null) {
                SmsControl.logDetailed(app, "UI", "", cmd, "This phone", "",
                        "Discovery failed: " + found.error);
                cb.onDone(false, found.error);
                return;
            }
            if (found.host == null) {
                String msg = "No pairing service found. Open Developer options "
                        + "→ Wireless debugging → “Pair device with pairing code” "
                        + "and leave that dialog open while you try again — the "
                        + "pairing service only exists while it is showing.";
                SmsControl.logDetailed(app, "UI", "", cmd, "This phone", "",
                        "No pairing service discovered (pairing dialog not open?)");
                cb.onDone(false, msg);
                return;
            }
            cb.onStatus("✓ Pairing service discovered — "
                    + found.host.getHostAddress() + ":" + found.port);
            AdbPairing.Result r = AdbPairing.pair(app, found.host, found.port, code,
                    (stage, ok, detail) -> cb.onStatus(
                            (ok ? "✓ " : "✗ ") + stage + " — " + detail));
            if (!r.success) {
                SmsControl.logDetailed(app, "UI", "", cmd, "This phone",
                        found.host.getHostAddress() + ":" + found.port,
                        "Pairing failed at stage '" + r.failedStage + "': " + r.error);
                cb.onDone(false, "Pairing failed at: " + r.failedStage + ". "
                        + r.error + " Nothing was paired; see the pairing log.");
                return;
            }
            Prefs.get(app).edit().putBoolean(Prefs.KEY_ADB_PAIRED, true).apply();
            SmsControl.logDetailed(app, "UI", "", cmd, "This phone",
                    found.host.getHostAddress() + ":" + found.port,
                    "Paired — phone accepted Control's key"
                            + (r.deviceGuid.isEmpty() ? "" : " (device " + r.deviceGuid + ")"));
            // Give adbd a moment to flush the newly authorized key
            // before the connect-side TLS handshake (OEM field
            // report: Identity accepted, then immediate connect
            // handshake_failure when connect raced the store).
            cb.onStatus("✓ Paired via " + found.host.getHostAddress()
                    + ". Settling 1s so the phone can store the key…");
            try { Thread.sleep(1000); } catch (Exception ignored) {}
            cb.onStatus("✓ Starting the bridge — the connect "
                    + "service is re-discovered fresh (ports rotate); "
                    + "connect prefers 127.0.0.1…");
            restartBridge(app, new Callback() {
                @Override public void onStatus(String line) {
                    cb.onStatus(line);
                }

                @Override public void onDone(boolean up, String summary) {
                    if (up) {
                        cb.onStatus("✓ First connection — Control's key authorized");
                        cb.onStatus("✓ Bridge started");
                        cb.onDone(true, summary);
                    } else {
                        cb.onDone(false, "Paired — Control's key is stored on "
                                + "this phone, so this was the last code you'll "
                                + "need. The bridge didn't start yet: " + summary);
                    }
                }
            });
        }, "adb-pairing").start();
    }

    // ---- on-device connect host preference ----

    /**
     * Run a shell command against the discovered connect service,
     * preferring {@code 127.0.0.1:<port>} over the mDNS-resolved
     * address.
     *
     * Why: when Control talks to its own adbd, mDNS returns a LAN or
     * VPN address (e.g. 192.168.x / 172.x). Hairpinning back to that
     * address from the same phone causes TLS client-certificate
     * rejection ({@code handshake_failure(40)}) on some OEMs even
     * after a successful pair — pairing and connect both completed
     * against real adbd in the field, but only the connect path
     * through the advertised IP failed. The connect service listens
     * on all interfaces, including loopback, so {@code 127.0.0.1}
     * reaches the same adbd without leaving the device. Falls back
     * to the discovered host if loopback fails.
     */
    private static AdbClient.Result runShellPreferLocalhost(
            Context app, Found found, String command, int timeoutMs,
            Callback statusCb) {
        InetAddress loopback = null;
        try {
            loopback = InetAddress.getByName("127.0.0.1");
        } catch (Exception ignored) {
        }
        if (loopback != null
                && (found.host == null
                || !loopback.getHostAddress().equals(found.host.getHostAddress()))) {
            if (statusCb != null) {
                statusCb.onStatus("Stage 2/3 — trying 127.0.0.1:" + found.port
                        + " (on-device loopback)…");
            }
            AdbClient.Result local = AdbClient.runShell(
                    app, loopback, found.port, command, timeoutMs);
            if (local.authorized) {
                if (statusCb != null) {
                    statusCb.onStatus("Stage 2/3 ✓ Connected via 127.0.0.1:"
                            + found.port);
                }
                return local;
            }
            if (statusCb != null) {
                statusCb.onStatus("Stage 2/3 — loopback connect failed ("
                        + (local.error == null || local.error.isEmpty()
                        ? "not authorized" : shortErr(local.error))
                        + "); trying "
                        + found.host.getHostAddress() + ":" + found.port
                        + "…");
            }
        }
        return AdbClient.runShell(app, found.host, found.port, command, timeoutMs);
    }

    private static String shortErr(String error) {
        if (error == null) return "";
        int cut = error.indexOf(". The phone no longer");
        if (cut > 0) return error.substring(0, cut);
        if (error.length() > 120) return error.substring(0, 120) + "…";
        return error;
    }

    // ---- discovery plumbing (blocking, latch-based) ----

    private static final class Found {
        InetAddress host;
        int port;
        String error;
    }

    private static Found discover(Context app, String type, long timeoutMs) {
        final Found out = new Found();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<AdbMdns.Session> session = new AtomicReference<>();
        // NsdManager listeners are happiest started from the main thread.
        new Handler(Looper.getMainLooper()).post(() -> {
            AdbMdns.Session s = AdbMdns.discover(app, type, new AdbMdns.Callback() {
                @Override public void onFound(InetAddress host, int port, String name) {
                    out.host = host;
                    out.port = port;
                    latch.countDown();
                }

                @Override public void onError(String message) {
                    out.error = message;
                    latch.countDown();
                }
            });
            session.set(s);
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        AdbMdns.Session s = session.get();
        if (s != null) s.stop();
        return out;
    }
}
