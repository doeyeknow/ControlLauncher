package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Deep view powered by Control's own shell bridge. The bridge is a
 * separate process started through adb as the shell user; it is not
 * Shizuku and does not depend on another app. It never survives reboot
 * — but it DOES survive APK upgrades, so after an update it keeps
 * running the OLD code until it is restarted (see BridgeGate and
 * AdbStarter.restartStaleBridge: the app detects a stale bridge and
 * offers the restart instead of misreporting it as down).
 */
public class DeepViewActivity extends ThemedActivity {
    private TextView status;
    private TextView watchdogLine;
    private TextView commandView;
    private EditText input;
    private TextView output;
    private TextView adbStatus;
    private TextView pairLog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Deep view"));
        status = Ui.text(this, "Checking the bridge…", 16, Ui.ACCENT);
        root.addView(status, Ui.wrap(0, 6, 0, 0, this));
        watchdogLine = Ui.text(this, "", 14, Ui.DIM);
        root.addView(watchdogLine, Ui.wrap(0, 4, 0, 0, this));
        root.addView(Ui.body(this,
                "Deep view uses Control's own shell bridge — a separate process "
                + "started through adb so it runs as Android's shell user. It "
                + "exposes fixed reads plus a governed control set, and only "
                + "Control can ask it for them."));
        Button controls = Ui.button(this, "Governed controls (permissions, AppOps, apps, DNS)", true);
        controls.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, DeepControlActivity.class)));
        root.addView(controls, Ui.wrap(0, 10, 0, 0, this));
        root.addView(Ui.body(this,
                "A shell process does not survive reboot. Once Deep "
                + "view has run, Control's auto-restart watches the "
                + "bridge and brings it back by itself after a "
                + "reboot, while wireless debugging stays on — "
                + "otherwise, start it again here. No root is "
                + "required when adb starts it."));

        root.addView(Ui.sectionLabel(this, "Start or restart — from a computer"));
        root.addView(Ui.body(this,
                "The always-works path: connect the phone to a computer with "
                + "USB or wireless adb, then run this one line. It starts the code "
                + "already inside Control's APK; nothing else is installed."));
        commandView = Ui.text(this, ShellBridgeClient.starterCommand(this), 12, Ui.TEXT);
        commandView.setTextIsSelectable(true);
        commandView.setTypeface(Typeface.MONOSPACE);
        commandView.setBackgroundColor(Ui.SURFACE);
        commandView.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        root.addView(commandView, Ui.wrap(0, 8, 0, 0, this));
        LinearLayout startRow = Ui.row(this);
        Button copy = Ui.smallButton(this, "Copy starter command");
        copy.setOnClickListener(v -> copyStarter());
        startRow.addView(copy);
        Button check = Ui.smallButton(this, "Check again");
        check.setOnClickListener(v -> ping());
        startRow.addView(check);
        root.addView(startRow);

        root.addView(Ui.sectionLabel(this, "Start Deep view from this phone (no computer)"));
        root.addView(Ui.body(this,
                "Android 11+ lets the phone debug itself over Wi-Fi. The "
                + "one-time setup pairs Control's own ADB key with the phone; "
                + "after that, each reboot is just: turn Wireless debugging "
                + "on and tap Restart below — no code, no computer.\n\n"
                + "1. Developer options → Wireless debugging → turn it ON.\n"
                + "2. Tap “Pair device with pairing code” there.\n"
                + "3. Come back here and type the 6-digit code.\n\n"
                + "Pairing is real in this build: Control runs the same "
                + "handshake a computer runs (see “How pairing works” "
                + "below), the phone stores Control's key, and from then "
                + "on each reboot is just step 1 + Restart — no code, no "
                + "computer. The computer starter above also always "
                + "works."));
        adbStatus = Ui.text(this, "", 14, Ui.ACCENT);
        root.addView(adbStatus, Ui.wrap(0, 8, 0, 0, this));
        Button restart = Ui.button(this, "Restart deep view (no computer)", true);
        restart.setOnClickListener(v -> {
            adbStatus.setText("Working…");
            appendStartLog("— Restart deep view —");
            // Stale-aware restart (v1.15.1): a bridge that
            // survived an APK upgrade answers PING while running
            // old code, and the plain restart would no-op on it.
            // restartStaleBridge stops the old bridge first and
            // leaves a current bridge alone.
            AdbStarter.restartStaleBridge(this, new AdbStarter.Callback() {
                @Override public void onStatus(String line) {
                    runOnUiThread(() -> {
                        adbStatus.setText(line);
                        appendStartLog(line);
                    });
                }

                @Override public void onDone(boolean up, String summary) {
                    runOnUiThread(() -> {
                        adbStatus.setText(summary);
                        appendStartLog(up ? "✓ Bridge is up." : "✗ " + summary);
                        refreshAdbStates();
                        ping();
                    });
                }
            });
        });
        root.addView(restart, Ui.wrap(0, 8, 0, 0, this));
        Button pair = Ui.button(this, "Enter pairing code", false);
        pair.setOnClickListener(v -> pairingDialog());
        root.addView(pair, Ui.wrap(0, 8, 0, 0, this));
        // Diagnose (v1.3): for when pairing worked but the bridge
        // won't answer — reads the bridge's own log over ADB and
        // shows it verbatim below (in the Look output block).
        Button diagnose = Ui.button(this, "Diagnose (bridge won't start)", false);
        diagnose.setOnClickListener(v -> {
            adbStatus.setText("Diagnosing…");
            output.setText("Running read-only diagnostics over ADB…");
            AdbStarter.diagnose(this, new AdbStarter.DiagnoseCallback() {
                @Override public void onStatus(String line) {
                    runOnUiThread(() -> adbStatus.setText(line));
                }

                @Override public void onDone(String report) {
                    runOnUiThread(() -> {
                        adbStatus.setText("Diagnose finished — results below, "
                                + "verbatim. Read them back or screenshot them.");
                        output.setText("Diagnose — bridge start\n\n" + report);
                        appendStartLog("Diagnose ran — full output in the "
                                + "Look block below.");
                    });
                }
            });
        });
        root.addView(diagnose, Ui.wrap(0, 8, 0, 0, this));
        root.addView(Ui.body(this,
                "Diagnose runs only read-only checks over the ADB shell "
                + "channel: the tail of the bridge's log file "
                + "(/data/local/tmp/control-bridge.log), whether its "
                + "app_process is alive, and the Android version. The "
                + "output appears verbatim in the output block at the "
                + "bottom of this screen."));
        root.addView(Ui.body(this,
                "How pairing works: Control speaks the phone's own pairing "
                + "protocol (the AOSP one a computer uses). A TLS 1.3 "
                + "session is opened with Control's stored certificate; "
                + "the 6-digit code feeds a SPAKE2 exchange bound to that "
                + "session; then Control's public key travels to the phone "
                + "inside encryption keyed by the exchange. A wrong code "
                + "fails the decryption on the phone's side — nothing is "
                + "stored, and the code itself is never stored here "
                + "either. The log below names each stage as it happens; "
                + "it never shows the code or any key material."));
        root.addView(Ui.sectionLabel(this, "Pairing / start log"));
        pairLog = Ui.text(this, "No pairing or start attempt yet this visit.", 12, Ui.TEXT);
        pairLog.setTypeface(Typeface.MONOSPACE);
        pairLog.setBackgroundColor(Ui.SURFACE);
        pairLog.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        root.addView(pairLog, Ui.wrap(0, 4, 0, 0, this));
        root.addView(Ui.body(this,
                "Radio switches (Wi-Fi, Bluetooth, mobile data, NFC, "
                + "airplane mode) live on the Connections screen — hold "
                + "anywhere on a launcher page for the circle menu, then "
                + "Connections. While Deep view is on, the switches there "
                + "become real; without it, Android keeps radio switches "
                + "system-only."));
        Button dev = Ui.button(this, "Open Developer options", false);
        dev.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Open Settings → System → Developer options",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(dev, Ui.wrap(0, 8, 0, 0, this));

        root.addView(Ui.sectionLabel(this, "Shell terminal"));
        root.addView(Ui.body(this,
                "A command line inside Control. Commands run over the ADB "
                + "shell channel — the same channel a computer's adb shell "
                + "uses — as the shell user, and it carries a one-tap "
                + "“Enable deep view” that runs the starter above through "
                + "this phone's own ADB channel. Wireless debugging must "
                + "be on and Control's key must be paired. The terminal "
                + "is outside the bridge's allowlist below: it talks to "
                + "the phone's debugging service, not to the bridge."));
        Button terminal = Ui.button(this, "Open terminal", false);
        terminal.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, TerminalActivity.class)));
        root.addView(terminal, Ui.wrap(0, 8, 0, 0, this));

        root.addView(Ui.sectionLabel(this, "Look"));
        input = Ui.input(this, "Log search text, or a package for AppOps");
        root.addView(input, Ui.wrap(0, 4, 0, 0, this));
        LinearLayout row1 = Ui.row(this);
        Button processes = Ui.smallButton(this, "Processes");
        processes.setOnClickListener(v -> runAsync("Processes",
                () -> ShellBridgeClient.processes(this)));
        row1.addView(processes);
        Button details = Ui.smallButton(this, "Process detail");
        details.setOnClickListener(v -> runAsync("Process detail",
                () -> ShellBridgeClient.processesDetail(this)));
        row1.addView(details);
        Button services = Ui.smallButton(this, "Running services");
        services.setOnClickListener(v -> runAsync("Running services",
                () -> ShellBridgeClient.services(this)));
        row1.addView(services);
        root.addView(row1);
        LinearLayout row2 = Ui.row(this);
        Button serviceList = Ui.smallButton(this, "System services");
        serviceList.setOnClickListener(v -> runAsync("System services",
                () -> ShellBridgeClient.serviceList(this)));
        row2.addView(serviceList);
        Button logs = Ui.smallButton(this, "Device log");
        logs.setOnClickListener(v -> {
            String query = input.getText().toString();
            runAsync("Device log", () -> ShellBridgeClient.logs(this, query, 400));
        });
        row2.addView(logs);
        Button appOps = Ui.smallButton(this, "AppOps");
        appOps.setOnClickListener(v -> {
            String pkg = input.getText().toString();
            runAsync("AppOps", () -> ShellBridgeClient.appOps(this, pkg));
        });
        row2.addView(appOps);
        root.addView(row2);
        root.addView(Ui.body(this,
                "The buttons send only an operation name. For AppOps, the field "
                + "is a package name and is validated. For the log, the field is "
                + "only a substring filter applied after a fixed logcat read."));

        output = Ui.text(this, "Choose a view above.", 12, Ui.TEXT);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setBackgroundColor(Ui.SURFACE);
        output.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 10));
        root.addView(output, Ui.wrap(0, 10, 0, 0, this));

        root.addView(Ui.sectionLabel(this, "Security boundary"));
        root.addView(Ui.body(this,
                "Allowlist only: process/service/log/AppOps reads, plus the "
                + "named governed controls on the Deep controls screen "
                + "(permissions, AppOp policy, force-stop, enable/disable, "
                + "clear data, background-data, Doze whitelist, Private DNS, "
                + "and two named launcher settings). No general shell command "
                + "and no endpoint for other apps. The bridge checks Control's "
                + "app UID on its local socket and requires Control's private token. "
                + "Every control attempt is audited."));
    }

    @Override
    protected void onResume() {
        super.onResume();
        commandView.setText(ShellBridgeClient.starterCommand(this));
        refreshAdbStates();
        // A foreground visit wakes the bridge watchdog: if its
        // backoff is exhausted, this is a retry trigger.
        BridgeWatchdogService.kick(this);
        ping();
    }

    /** Paired / key state lines. Bridge state itself is in `status`
     *  (kept fresh by ping()). */
    private void refreshAdbStates() {
        if (adbStatus == null) return;
        boolean paired = Prefs.get(this).getBoolean(Prefs.KEY_ADB_PAIRED, false);
        adbStatus.setText(paired
                ? "ADB key: paired — Restart should reconnect with no code."
                : "ADB key: not paired yet — pair once with the code (below).");
        new Thread(() -> {
            String fp = AdbKeyStore.ownFingerprint(this);
            runOnUiThread(() -> {
                if (adbStatus != null) {
                    adbStatus.setText(adbStatus.getText()
                            + "\nControl's ADB key fingerprint: " + fp);
                }
            });
        }, "adb-fingerprint").start();
    }

    /** Append a line to the on-screen pairing/start log. The log
     *  starts from its placeholder line; the first real append
     *  replaces it. */
    private void appendStartLog(String line) {
        if (pairLog == null) return;
        String cur = pairLog.getText().toString();
        if (cur.startsWith("No pairing or start attempt")) {
            pairLog.setText(line);
        } else {
            pairLog.setText(cur + "\n" + line);
        }
    }

    private void pairingDialog() {
        EditText code = Ui.input(this, "6-digit pairing code");
        code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this)
                .setTitle("Pair with this phone")
                .setMessage("Read the code from Developer options → Wireless "
                        + "debugging → “Pair device with pairing code”, and type "
                        + "it here. Keep that dialog open while Control looks "
                        + "for the pairing service.")
                .setView(code)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (d, w) -> {
                    String v = code.getText().toString().trim();
                    if (v.length() != 6) {
                        Toast.makeText(this, "The pairing code is 6 digits.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    adbStatus.setText("Pairing attempt started…");
                    pairLog.setText("Pairing attempt (the code is never logged):");
                    AdbStarter.pair(this, v, new AdbStarter.Callback() {
                        @Override public void onStatus(String line) {
                            runOnUiThread(() -> {
                                adbStatus.setText(line);
                                pairLog.setText(pairLog.getText() + "\n" + line);
                            });
                        }

                        @Override public void onDone(boolean up, String summary) {
                            runOnUiThread(() -> {
                                adbStatus.setText(summary);
                                pairLog.setText(pairLog.getText() + "\n"
                                        + (up ? "✓ Done — bridge is up."
                                              : "✗ Attempt ended — see the last line above."));
                                refreshAdbStates();
                                ping();
                            });
                        }
                    });
                })
                .show();
    }

    private void copyStarter() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Control bridge starter",
                ShellBridgeClient.starterCommand(this)));
        Toast.makeText(this, "Starter command copied. Run it from a computer connected by adb.",
                Toast.LENGTH_LONG).show();
    }

    private void ping() {
        status.setText("Checking the bridge…");
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.ping(this);
            runOnUiThread(() -> {
                if (r.ok) {
                    // Deep view is on (v1.16): the bridge is up
                    // — including via the computer starter, which
                    // never passes through AdbStarter — so the
                    // watchdog may keep it up from here.
                    Prefs.get(this).edit()
                            .putBoolean(Prefs.KEY_DEEP_VIEW_ON, true).apply();
                    status.setText("Deep view is on — bridge "
                            + BridgeGate.versionFragment(r.bridgeVersion)
                            + "running as shell UID "
                            + r.bridgeUid + " (PID " + r.bridgePid + ", via "
                            + r.transport + ")."
                            + (r.bridgeVersion < BridgeGate.HOLD_WATCH_VERSION
                                    ? " This is an old bridge version — restart it "
                                      + "for hold-anywhere (the Restart button "
                                      + "below stops the old one first)."
                                    : ""));
                    OwnLog.log(this, "Deep view", "Bridge ping succeeded as UID " + r.bridgeUid
                            + " (" + BridgeGate.versionName(r.bridgeVersion) + ")");
                } else {
                    status.setText("Deep view is off — tap to restart (needed after each reboot).");
                }
                if (watchdogLine != null) {
                    watchdogLine.setText("Auto-restart: "
                            + BridgeWatchdogService.stateLine(this));
                }
                // A bridge that just came up (or went away) changes
                // the hold-anywhere menu's gates — re-enforce them.
                OverlayRingService.reconcile(this);
            });
        }, "bridge-ping").start();
    }

    private void runAsync(String title, Operation operation) {
        output.setText("Working…");
        new Thread(() -> {
            ShellBridgeClient.Response r;
            try {
                r = operation.call();
            } catch (Exception e) {
                r = new ShellBridgeClient.Response(false, "", e.getMessage(), -1, -1, "none", 0);
            }
            final ShellBridgeClient.Response result = r;
            runOnUiThread(() -> showResult(title, result));
        }, "bridge-operation").start();
    }

    private interface Operation {
        ShellBridgeClient.Response call();
    }

    private void showResult(String title, ShellBridgeClient.Response r) {
        if (!r.ok) {
            status.setText("Deep view is off — tap to restart (needed after each reboot).");
            output.setText(title + " is unavailable.\n\n" + r.error
                    + "\n\nStart the bridge with the command above, then tap Check again.");
            return;
        }
        status.setText("Deep view is on — bridge "
                + BridgeGate.versionFragment(r.bridgeVersion)
                + "running as shell UID " + r.bridgeUid + ".");
        String text = r.text;
        if (text.length() > 60_000) {
            text = text.substring(text.length() - 60_000)
                    + "\n\n[Only the last 60,000 characters are shown in this screen.]";
        }
        output.setText(title + "\n\n" + text);
    }
}
