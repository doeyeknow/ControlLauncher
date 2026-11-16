package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Governed bridge controls. Every button names one bridge operation;
 * every execution is confirmed first and audited through BridgeControl.
 */
public class DeepControlActivity extends ThemedActivity {
    private TextView bridgeStatus;
    private EditText packageInput;
    private TextView targetStatus;
    private EditText dnsInput;
    private TextView output;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private interface BridgeCall {
        ShellBridgeClient.Response run();
    }

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

        root.addView(Ui.screenTitle(this, "Deep controls"));
        bridgeStatus = Ui.text(this, "Checking the bridge…", 15, Ui.ACCENT);
        root.addView(bridgeStatus, Ui.wrap(0, 4, 0, 0, this));
        root.addView(Ui.body(this,
                "These are governed writes, not a shell. Each one asks first, "
                + "names its target, is checked against a refuse-list, and is "
                + "written to the audit log. If the bridge is off, attempts are "
                + "still audited and nothing changes."));

        root.addView(Ui.sectionLabel(this, "Target app"));
        packageInput = Ui.input(this, "Package name, e.g. com.example.app");
        root.addView(packageInput);
        LinearLayout pickRow = Ui.row(this);
        Button pick = Ui.smallButton(this, "Pick a launchable app");
        pick.setOnClickListener(v -> pickApp());
        pickRow.addView(pick);
        Button inspect = Ui.smallButton(this, "Use typed package");
        inspect.setOnClickListener(v -> refreshTarget());
        pickRow.addView(inspect);
        root.addView(pickRow);
        targetStatus = Ui.body(this, "No target selected.");
        root.addView(targetStatus);

        root.addView(Ui.sectionLabel(this, "Permissions"));
        LinearLayout permRow = Ui.row(this);
        Button grant = Ui.smallButton(this, "Grant…");
        grant.setOnClickListener(v -> choosePermission(true));
        permRow.addView(grant);
        Button revoke = Ui.smallButton(this, "Revoke…");
        revoke.setOnClickListener(v -> choosePermission(false));
        permRow.addView(revoke);
        root.addView(permRow);

        root.addView(Ui.sectionLabel(this, "AppOps"));
        Button appOp = Ui.button(this, "Set an AppOp…", false);
        appOp.setOnClickListener(v -> chooseAppOp());
        root.addView(appOp, Ui.wrap(0, 6, 0, 0, this));
        root.addView(Ui.body(this,
                "AppOps is Android's per-capability policy layer: camera, mic, "
                + "location, notifications, and more can be allowed, ignored, or denied."));

        root.addView(Ui.sectionLabel(this, "App state"));
        LinearLayout stateRow1 = Ui.row(this);
        Button forceStop = Ui.smallButton(this, "Force-stop");
        forceStop.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(false, "Force-stop " + targetName(pkg) + "?",
                    "This stops the app now. It can be opened again normally.",
                    "Force-stop",
                    () -> BridgeControl.forceStop(this, BridgeControl.ORIGIN_UI, pkg));
        });
        stateRow1.addView(forceStop);
        Button toggleEnabled = Ui.smallButton(this, "Disable / enable…");
        toggleEnabled.setOnClickListener(v -> toggleEnabled());
        stateRow1.addView(toggleEnabled);
        root.addView(stateRow1);
        Button clear = Ui.button(this, "Clear app data…", false);
        clear.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(true, "Clear all data for " + targetName(pkg) + "?",
                    "DESTRUCTIVE: this deletes the app's accounts, settings, files, "
                    + "and sign-ins stored on this phone. It cannot be undone.",
                    "Clear data",
                    () -> BridgeControl.clearAppData(this, BridgeControl.ORIGIN_UI, pkg));
        });
        root.addView(clear, Ui.wrap(0, 6, 0, 0, this));

        root.addView(Ui.sectionLabel(this, "Background policy"));
        LinearLayout bgRow = Ui.row(this);
        Button restrict = Ui.smallButton(this, "Restrict background data");
        restrict.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(false, "Restrict background data for " + targetName(pkg) + "?",
                    "Android netpolicy will put this app's UID on the restrict-background blacklist.",
                    "Restrict",
                    () -> BridgeControl.setBackgroundData(this, BridgeControl.ORIGIN_UI, pkg, false));
        });
        bgRow.addView(restrict);
        Button allow = Ui.smallButton(this, "Allow background data");
        allow.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(false, "Allow background data for " + targetName(pkg) + "?",
                    "Android netpolicy will remove this app's UID from the restrict-background blacklist.",
                    "Allow",
                    () -> BridgeControl.setBackgroundData(this, BridgeControl.ORIGIN_UI, pkg, true));
        });
        bgRow.addView(allow);
        root.addView(bgRow);
        LinearLayout idleRow = Ui.row(this);
        Button idleAdd = Ui.smallButton(this, "Exempt from Doze");
        idleAdd.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(false, "Exempt " + targetName(pkg) + " from Doze battery limits?",
                    "The app is added to Android's device-idle whitelist.",
                    "Exempt",
                    () -> BridgeControl.setDeviceIdleWhitelist(this, BridgeControl.ORIGIN_UI, pkg, true));
        });
        idleRow.addView(idleAdd);
        Button idleRemove = Ui.smallButton(this, "Remove Doze exemption");
        idleRemove.setOnClickListener(v -> {
            String pkg = targetPackage();
            if (pkg == null) return;
            confirm(false, "Remove the Doze exemption for " + targetName(pkg) + "?",
                    "Normal Android battery optimization will apply again.",
                    "Remove",
                    () -> BridgeControl.setDeviceIdleWhitelist(this, BridgeControl.ORIGIN_UI, pkg, false));
        });
        idleRow.addView(idleRemove);
        root.addView(idleRow);

        root.addView(Ui.sectionLabel(this, "Private DNS"));
        dnsInput = Ui.input(this, "Filtering resolver hostname");
        dnsInput.setText(BridgeControl.FILTER_DNS_HOSTNAME);
        root.addView(dnsInput);
        LinearLayout dnsRow = Ui.row(this);
        Button dnsSet = Ui.smallButton(this, "Set filtered DNS");
        dnsSet.setOnClickListener(v -> {
            String host = dnsInput.getText().toString().trim();
            confirm(false, "Set Private DNS to " + host + "?",
                    "The bridge writes Android's global Private DNS mode and hostname directly.",
                    "Set DNS",
                    () -> BridgeControl.setPrivateDns(this, BridgeControl.ORIGIN_UI, "hostname", host));
        });
        dnsRow.addView(dnsSet);
        Button dnsAuto = Ui.smallButton(this, "Automatic");
        dnsAuto.setOnClickListener(v -> confirm(false, "Set Private DNS to Automatic?",
                "Android will use opportunistic Private DNS where the network offers it.",
                "Set automatic",
                () -> BridgeControl.setPrivateDns(this, BridgeControl.ORIGIN_UI, "opportunistic", "")));
        dnsRow.addView(dnsAuto);
        Button dnsOff = Ui.smallButton(this, "Off");
        dnsOff.setOnClickListener(v -> confirm(false, "Turn Private DNS off?",
                "Lookups return to the network's normal resolvers.",
                "Turn off",
                () -> BridgeControl.setPrivateDns(this, BridgeControl.ORIGIN_UI, "off", "")));
        dnsRow.addView(dnsOff);
        root.addView(dnsRow);

        root.addView(Ui.sectionLabel(this, "Launcher system setting"));
        Button timeout = Ui.button(this, "Set screen timeout…", false);
        timeout.setOnClickListener(v -> chooseTimeout());
        root.addView(timeout, Ui.wrap(0, 6, 0, 0, this));
        root.addView(Ui.body(this,
                "Only two plain system settings are exposed through the bridge: "
                + "screen timeout and font scale. There is no arbitrary settings endpoint. "
                + "Font scale is available to the bridge allowlist; screen timeout is shown here."));

        root.addView(Ui.sectionLabel(this, "Result"));
        output = Ui.body(this, "No control has been run yet.");
        root.addView(output);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ping();
        refreshTarget();
    }

    private void ping() {
        new Thread(() -> {
            ShellBridgeClient.Response r = ShellBridgeClient.ping(this);
            ui.post(() -> bridgeStatus.setText(r.ok
                    ? "Bridge on — shell UID " + r.bridgeUid + ", governed allowlist v2."
                    : "Deep view is off — tap to restart (needed after each reboot)."));
        }, "control-ping").start();
    }

    private void pickApp() {
        List<InstalledApps.Entry> apps = InstalledApps.all(this);
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label + " · " + apps.get(i).pkg;
        new AlertDialog.Builder(this)
                .setTitle("Choose target app")
                .setItems(labels, (d, which) -> {
                    packageInput.setText(apps.get(which).pkg);
                    refreshTarget();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String targetPackage() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            Toast.makeText(this, "Choose or type a target package first.",
                    Toast.LENGTH_LONG).show();
            return null;
        }
        return pkg;
    }

    private String targetName(String pkg) {
        return InstalledApps.label(this, pkg) + " (" + pkg + ")";
    }

    private void refreshTarget() {
        String pkg = packageInput.getText().toString().trim();
        if (pkg.isEmpty()) {
            targetStatus.setText("No target selected.");
            return;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            int setting = getPackageManager().getApplicationEnabledSetting(pkg);
            boolean enabled = setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            targetStatus.setText(targetName(pkg) + "\n"
                    + ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? "System app" : "User app")
                    + " · currently " + (enabled ? "enabled" : "disabled")
                    + (BridgeControl.REFUSE_ALL.contains(pkg)
                    ? "\nProtected by the bridge refuse-list." : ""));
        } catch (Exception e) {
            targetStatus.setText("That package is not installed on this phone.");
        }
    }

    private void choosePermission(boolean grant) {
        String pkg = targetPackage();
        if (pkg == null) return;
        new AlertDialog.Builder(this)
                .setTitle((grant ? "Grant" : "Revoke") + " which permission?")
                .setItems(BridgeControl.PERMISSION_LABELS, (d, which) -> {
                    String permission = BridgeControl.PERMISSIONS[which];
                    String label = BridgeControl.PERMISSION_LABELS[which];
                    confirm(false, (grant ? "Grant " : "Revoke ") + label + (grant ? " to " : " from ")
                                    + targetName(pkg) + "?",
                            "The bridge will ask Android's package manager to "
                                    + (grant ? "grant" : "revoke") + " " + permission + ".",
                            grant ? "Grant" : "Revoke",
                            () -> grant
                                    ? BridgeControl.grantPermission(this, BridgeControl.ORIGIN_UI, pkg, permission)
                                    : BridgeControl.revokePermission(this, BridgeControl.ORIGIN_UI, pkg, permission));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseAppOp() {
        String pkg = targetPackage();
        if (pkg == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Set which AppOp for " + targetName(pkg) + "?")
                .setItems(BridgeControl.APP_OP_LABELS, (d, opWhich) -> {
                    String op = BridgeControl.APP_OPS[opWhich];
                    new AlertDialog.Builder(this)
                            .setTitle("Mode for " + BridgeControl.APP_OP_LABELS[opWhich])
                            .setItems(BridgeControl.APP_OP_MODES, (d2, modeWhich) -> {
                                String mode = BridgeControl.APP_OP_MODES[modeWhich];
                                confirm(false, "Set " + BridgeControl.APP_OP_LABELS[opWhich]
                                                + " to " + mode + " for " + targetName(pkg) + "?",
                                        "The bridge writes this one AppOp policy only.",
                                        "Set AppOp",
                                        () -> BridgeControl.setAppOp(this,
                                                BridgeControl.ORIGIN_UI, pkg, op, mode));
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toggleEnabled() {
        String pkg = targetPackage();
        if (pkg == null) return;
        try {
            int setting = getPackageManager().getApplicationEnabledSetting(pkg);
            boolean currentlyEnabled = setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            boolean targetEnabled = !currentlyEnabled;
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            boolean strong = !targetEnabled
                    && ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || BridgeControl.REFUSE_DESTRUCTIVE.contains(pkg));
            confirm(strong, (targetEnabled ? "Enable " : "Disable ") + targetName(pkg) + "?",
                    targetEnabled
                            ? "The app becomes available to open and run again."
                            : "The app is disabled: it cannot run until re-enabled. "
                            + "Its data is not deleted.",
                    targetEnabled ? "Enable" : "Disable",
                    () -> BridgeControl.setAppEnabled(this, BridgeControl.ORIGIN_UI, pkg, targetEnabled));
        } catch (Exception e) {
            Toast.makeText(this, "That package is not installed.", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseTimeout() {
        String[] labels = {"30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes", "30 minutes"};
        String[] values = {"30000", "60000", "120000", "300000", "600000", "1800000"};
        new AlertDialog.Builder(this)
                .setTitle("Screen timeout")
                .setItems(labels, (d, which) -> confirm(false,
                        "Set the screen timeout to " + labels[which] + "?",
                        "This is the one named system setting exposed on this screen.",
                        "Set timeout",
                        () -> BridgeControl.setLauncherSetting(this, BridgeControl.ORIGIN_UI,
                                "screen_off_timeout", values[which])))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirm(boolean strong, String title, String message,
                         String actionLabel, BridgeCall call) {
        if (!strong) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message + "\n\nThis attempt will be written to the audit log.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(actionLabel, (d, w) -> runCall(title, call))
                    .show();
            return;
        }
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 18);
        box.setPadding(p, p, p, p);
        TextView body = Ui.body(this, message
                + "\n\nStrong confirmation: press and hold the button below for 3 seconds. "
                + "This attempt will be written to the audit log.");
        box.addView(body);
        Button hold = Ui.button(this, "Press and hold to confirm", false);
        box.addView(hold, Ui.wrap(0, 14, 0, 0, this));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .create();
        Runnable fire = () -> {
            dialog.dismiss();
            runCall(title, call);
        };
        hold.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                hold.setText("Keep holding…");
                ui.postDelayed(fire, 3000);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                ui.removeCallbacks(fire);
                hold.setText("Press and hold to confirm");
                return true;
            }
            return false;
        });
        dialog.show();
    }

    private void runCall(String title, BridgeCall call) {
        output.setText(title + "\n\nWorking…");
        new Thread(() -> {
            ShellBridgeClient.Response r = call.run();
            ui.post(() -> {
                output.setText(title + "\n\n" + (r.ok ? r.text : "Refused / failed: " + r.error));
                ping();
                refreshTarget();
            });
        }, "bridge-control").start();
    }
}
