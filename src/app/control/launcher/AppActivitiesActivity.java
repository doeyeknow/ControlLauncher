package app.control.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * One app's declared activities (v1.22): every activity the app's
 * manifest declares, each with its label, full class name, and
 * whether it is exported. Opened from the Apps sheet's long-press
 * menu ("View activities").
 *
 * The list is queried on demand when the screen opens — never
 * cached, because a manifest can change with an update. Tapping an
 * exported activity launches it with an explicit intent naming its
 * component; a launch Android refuses (SecurityException) shows an
 * honest toast instead of crashing. Non-exported activities are
 * shown but marked plainly: Android only lets an app open another
 * app's exported screens.
 *
 * v1.35: when Deep view is on with a current bridge, a
 * non-exported activity gains a "Launch via bridge" action: the
 * shell bridge runs `am start -n` for the component, which the
 * shell user is allowed to do. The action confirms with the
 * activity name first and is audit-logged like every other
 * bridge control; a refusal (the component does not exist, the
 * bridge is old or down) is reported honestly. Activity aliases
 * appear as their own rows and launch by the alias component
 * name.
 *
 * v1.38: a refusal opens a scrollable dialog with the bridge's
 * complete error words, and the verifier counts am start's
 * already-on-top warning as success (the activity came
 * forward), instead of reporting it as a refusal.
 */
public class AppActivitiesActivity extends ThemedActivity {

    public static final String EXTRA_PACKAGE = "app.control.launcher.PACKAGE";
    public static final String EXTRA_LABEL = "app.control.launcher.LABEL";

    private boolean destroyed = false;
    private LinearLayout listBox;
    private TextView statusLine;
    /** Whether the bridge is current (v6+): only then does a
     *  non-exported row offer the bridge launch. */
    private boolean bridgeCurrent = false;
    /** Whether any bridge answered at all: a stale bridge gets
     *  its own honest row copy. */
    private boolean bridgeAnswered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String pkg = getIntent().getStringExtra(EXTRA_PACKAGE);
        String labelExtra = getIntent().getStringExtra(EXTRA_LABEL);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(root);
        setContentView(scroll);

        if (pkg == null || pkg.isEmpty()) {
            root.addView(Ui.screenTitle(this, "Activities"));
            root.addView(Ui.body(this, "No app was given to inspect."));
            return;
        }

        // Header: the app's icon + name + package.
        String label = labelExtra != null && !labelExtra.isEmpty()
                ? labelExtra : InstalledApps.label(this, pkg);
        LinearLayout head = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(InstalledApps.iconFor(this, pkg));
        int sz = Ui.dp(this, 46);
        head.addView(icon, new LinearLayout.LayoutParams(sz, sz));
        LinearLayout names = Ui.column(this);
        names.setBackgroundColor(0x00000000);
        names.setPadding(Ui.dp(this, 12), 0, 0, 0);
        TextView nameView = Ui.text(this, label, 20, Ui.TEXT);
        nameView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        names.addView(nameView);
        TextView pkgView = Ui.text(this, pkg, 13, Ui.DIM);
        pkgView.setTypeface(Typeface.MONOSPACE);
        names.addView(pkgView);
        head.addView(names, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(head);

        root.addView(Ui.body(this,
                "Android only lets an app open another app's exported "
                + "screens. The exported activities below open when "
                + "tapped. When Deep view is on with a current bridge, "
                + "the others offer a bridge launch instead — the shell "
                + "user may start them."),
                Ui.wrap(0, 12, 0, 0, this));

        statusLine = Ui.text(this, "Reading the app's activities…", 13, Ui.ACCENT);
        statusLine.setTypeface(Typeface.MONOSPACE);
        root.addView(statusLine, Ui.wrap(0, 16, 0, 0, this));

        listBox = Ui.column(this);
        listBox.setBackgroundColor(0x00000000);
        root.addView(listBox, Ui.wrap(0, 8, 0, 0, this));

        queryAsync(pkg);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    /** The PackageManager query runs off the main thread: an app
     *  can declare many activities, and label lookups touch its
     *  resources. Results (or the honest failure) post back. */
    private void queryAsync(final String pkg) {
        new Thread(() -> {
            List<ActivityList.Raw> raw = new ArrayList<>();
            String failure = null;
            try {
                PackageManager pm = getPackageManager();
                PackageInfo info = queryPackage(pm, pkg);
                if (info.activities != null) {
                    for (ActivityInfo ai : info.activities) {
                        raw.add(new ActivityList.Raw(
                                ai.name, ownLabel(pm, ai), ai.exported,
                                ai.targetActivity));
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                failure = "That app is no longer installed.";
            } catch (Exception e) {
                failure = "Couldn't read this app's activities"
                        + (e.getMessage() != null && !e.getMessage().isEmpty()
                                ? ": " + e.getMessage() : ".");
            }
            // The bridge check rides the same background thread:
            // it decides whether non-exported rows offer the
            // bridge launch. A failed ping means Deep view is
            // off — the rows say so, and nothing is promised.
            ShellBridgeClient.Response ping = ShellBridgeClient.ping(this);
            BridgeGate.State gate = BridgeGate.classify(ping.ok, ping.bridgeVersion);
            final boolean current = gate == BridgeGate.State.CURRENT;
            final boolean answered = ping.ok;
            final List<ActivityList.Raw> found = raw;
            final String error = failure;
            runOnUiThread(() -> {
                if (destroyed) return;
                bridgeCurrent = current;
                bridgeAnswered = answered;
                if (error != null) {
                    statusLine.setText(error);
                    statusLine.setTextColor(Ui.SIGNAL);
                    return;
                }
                showRows(pkg, ActivityList.build(found));
            });
        }, "control-app-activities").start();
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo queryPackage(PackageManager pm, String pkg)
            throws PackageManager.NameNotFoundException {
        // The int-flags overload is deprecated on API 33+ but still
        // the one form that serves every API level this app runs
        // on; the flags form would need an API-33 branch for the
        // same answer.
        return pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
    }

    /** The activity's OWN label, or null when it declares none —
     *  the platform lookup would otherwise hand back the app's
     *  label for every unlabelled activity. */
    private static String ownLabel(PackageManager pm, ActivityInfo ai) {
        if (ai.labelRes == 0 && ai.nonLocalizedLabel == null) return null;
        try {
            CharSequence loaded = ai.loadLabel(pm);
            return loaded != null ? loaded.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void showRows(String pkg, List<ActivityList.Row> rows) {
        if (rows.isEmpty()) {
            statusLine.setText("This app declares no activities.");
            return;
        }
        statusLine.setText(ActivityList.summary(rows));
        boolean first = true;
        for (ActivityList.Row row : rows) {
            if (!first) listBox.addView(Ui.divider(this));
            first = false;
            listBox.addView(rowView(pkg, row));
        }
    }

    private View rowView(final String pkg, final ActivityList.Row row) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        int vPad = Ui.dp(this, 10);
        cell.setPadding(0, vPad, 0, vPad);

        TextView labelView = Ui.text(this, row.label, 16, Ui.TEXT);
        cell.addView(labelView);
        TextView classView = Ui.text(this, row.className, 13, Ui.DIM);
        classView.setTypeface(Typeface.MONOSPACE);
        cell.addView(classView);

        String status;
        int statusColor;
        if (row.exported) {
            status = "Tap to open";
            statusColor = Ui.ACCENT;
        } else if (bridgeCurrent) {
            status = "Tap for bridge launch";
            statusColor = Ui.ACCENT;
        } else if (bridgeAnswered) {
            status = "Bridge is old — restart Deep view to launch this";
            statusColor = Ui.DIM;
        } else {
            status = "Not openable from here — Deep view is off";
            statusColor = Ui.DIM;
        }
        if (row.alias) {
            status += " · alias for " + row.targetShortName;
        }
        TextView statusView = Ui.text(this, status, 13, statusColor);
        cell.addView(statusView);

        cell.setOnClickListener(v -> {
            if (row.exported) {
                launch(pkg, row);
            } else if (bridgeCurrent) {
                launchViaBridge(pkg, row);
            } else if (bridgeAnswered) {
                Toast.makeText(this,
                        "The running bridge is old code — restart Deep view "
                        + "to launch non-exported activities.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        "Android only lets an app open another app's "
                        + "exported screens — this one isn't exported, and "
                        + "Deep view is off, so the bridge cannot launch it.",
                        Toast.LENGTH_LONG).show();
            }
        });
        return cell;
    }

    /** Bridge launch for a non-exported activity: confirm with
     *  the activity name, run `am start -n` through the
     *  bridge, and audit the attempt. Success keeps a short
     *  toast; a refusal opens a scrollable dialog carrying the
     *  bridge's complete error words (v1.38) — the old result
     *  toast cut long reasons off mid-sentence and vanished,
     *  so the actual refusal was unreadable. */
    private void launchViaBridge(String pkg, ActivityList.Row row) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Launch via the bridge?")
                .setMessage("Start " + row.label + " (" + row.className + ") through "
                        + "the shell bridge? The bridge runs `am start -n` for this "
                        + "component, which the shell user is allowed to do.\n\n"
                        + "This attempt will be written to the audit log.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Launch", (d, w) -> {
                    new Thread(() -> {
                        ShellBridgeClient.Response r = BridgeControl.launchActivity(
                                this, BridgeControl.ORIGIN_UI, pkg, row.className);
                        runOnUiThread(() -> {
                            if (destroyed) return;
                            if (r.ok) {
                                Toast.makeText(this, "Launched " + row.label + ".",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                showLaunchRefusal(pkg, row, r.error);
                            }
                        });
                    }, "bridge-launch-activity").start();
                })
                .show();
    }

    /** The refusal dialog: the full component name plus the
     *  bridge's complete error words, in a scrolling,
     *  selectable view, so a long `am start` refusal can
     *  actually be read in full. */
    private void showLaunchRefusal(String pkg, ActivityList.Row row, String error) {
        TextView body = new TextView(this);
        body.setText(ActivityList.refusalDialogBody(pkg, row.className, error));
        body.setTextSize(14);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(Ui.TEXT);
        body.setTextIsSelectable(true);
        int p = Ui.dp(this, 20);
        body.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new android.app.AlertDialog.Builder(this)
                .setTitle("The bridge refused to launch this activity")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Launch by explicit component name. An exported activity can
     *  still refuse (a permission requirement, a removed target):
     *  the refusal is toasted honestly, never a crash. */
    private void launch(String pkg, ActivityList.Row row) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, row.className));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (SecurityException e) {
            String msg = e.getMessage();
            Toast.makeText(this,
                    "Android refused to open " + row.label
                    + (msg != null && !msg.isEmpty()
                            ? ": " + truncate(msg, 100) : "."),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open " + row.label + ".",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
