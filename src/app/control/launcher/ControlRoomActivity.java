package app.control.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Activity / Control Room: a plain-language view of what Android allows
 * Control to observe — notifications it received, app usage Android
 * reports, per-app network totals, and Control's own services/logs.
 */
public class ControlRoomActivity extends ThemedActivity {
    private enum Section { NOTIFICATIONS, APPS, NETWORK, OWN }

    /** Opens on a tab instead of Notifications (v1.9: the circle
     *  menu's Activity sub-ring). Values: "notifications", "apps",
     *  "network", "own". Anything else opens Notifications. */
    public static final String EXTRA_SECTION = "control.room_section";

    private static final class Row {
        final String title;
        final String subtitle;
        final String detail;
        final boolean header;

        Row(String title, String subtitle, String detail) {
            this(title, subtitle, detail, false);
        }

        Row(String title, String subtitle, String detail, boolean header) {
            this.title = title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.detail = detail == null ? "" : detail;
            this.header = header;
        }
    }

    private ActivityDataSource source;
    private Section section = Section.NOTIFICATIONS;
    private boolean networkSevenDays;
    private final List<Row> rows = new ArrayList<>();
    private RowAdapter adapter;
    private TextView accessLine;
    private Button notificationsButton;
    private Button appsButton;
    private Button networkButton;
    private Button ownButton;
    private Button periodButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        source = new AndroidActivityDataSource(this);
        OwnLog.log(this, "Activity", "Control Room opened");

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 12));
        setContentView(root);

        root.addView(Ui.screenTitle(this, "Activity"));
        root.addView(Ui.body(this,
                "What your phone tried to show you, which apps were active, "
                + "and which ones used the network. Calm facts, in plain words."));
        root.addView(Ui.body(this,
                "Honest limit: Android walls off other apps' internal services "
                + "and the system-wide log from normal apps. Control shows only "
                + "what Android grants it — nothing here is guessed."));
        accessLine = Ui.text(this, "", 13, Ui.ACCENT);
        root.addView(accessLine, Ui.wrap(0, 8, 0, 0, this));

        LinearLayout actions = Ui.row(this);
        Button refresh = Ui.smallButton(this, "Refresh");
        refresh.setOnClickListener(v -> load());
        actions.addView(refresh);
        Button usage = Ui.smallButton(this, "Usage access");
        usage.setOnClickListener(v -> openUsageAccess());
        actions.addView(usage);
        Button notif = Ui.smallButton(this, "Notification access");
        notif.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "Open Settings → Notifications → Device and app notifications",
                        Toast.LENGTH_LONG).show();
            }
        });
        actions.addView(notif);
        periodButton = Ui.smallButton(this, "Network: today");
        periodButton.setOnClickListener(v -> {
            networkSevenDays = !networkSevenDays;
            periodButton.setText(networkSevenDays ? "Network: 7 days" : "Network: today");
            if (section == Section.NETWORK) load();
        });
        actions.addView(periodButton);
        root.addView(actions);
        LinearLayout deepActions = Ui.row(this);
        Button deep = Ui.smallButton(this, "Deep view (shell bridge)");
        deep.setOnClickListener(v -> startActivity(new Intent(this, DeepViewActivity.class)));
        deepActions.addView(deep);
        Button controls = Ui.smallButton(this, "Governed controls");
        controls.setOnClickListener(v -> startActivity(new Intent(this, DeepControlActivity.class)));
        deepActions.addView(controls);
        root.addView(deepActions);

        LinearLayout tabs = Ui.row(this);
        notificationsButton = tabButton("Notifications", Section.NOTIFICATIONS);
        appsButton = tabButton("Apps", Section.APPS);
        networkButton = tabButton("Network", Section.NETWORK);
        ownButton = tabButton("This app", Section.OWN);
        tabs.addView(notificationsButton);
        tabs.addView(appsButton);
        tabs.addView(networkButton);
        tabs.addView(ownButton);
        root.addView(tabs, Ui.wrap(0, 8, 0, 0, this));

        ListView list = new ListView(this);
        list.setDivider(null);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Row row = rows.get(pos);
            if (row.header) return;
            new AlertDialog.Builder(this)
                    .setTitle(row.title)
                    .setMessage(row.detail.isEmpty() ? row.subtitle : row.detail)
                    .setPositiveButton("Done", null)
                    .show();
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout footer = Ui.row(this);
        Button clear = Ui.smallButton(this, "Clear shown history");
        clear.setOnClickListener(v -> confirmClear());
        footer.addView(clear);
        root.addView(footer);

        // The ring's Activity leaves land on their tab; the first
        // load() (onResume) styles the tabs for the chosen section.
        section = sectionFromExtra(getIntent());
    }

    private Section sectionFromExtra(Intent intent) {
        if (intent == null) return Section.NOTIFICATIONS;
        String s = intent.getStringExtra(EXTRA_SECTION);
        if ("apps".equals(s)) return Section.APPS;
        if ("network".equals(s)) return Section.NETWORK;
        if ("own".equals(s)) return Section.OWN;
        return Section.NOTIFICATIONS;
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private Button tabButton(String label, Section target) {
        Button b = Ui.smallButton(this, label);
        b.setOnClickListener(v -> {
            section = target;
            load();
        });
        return b;
    }

    private void styleTabs() {
        styleTab(notificationsButton, section == Section.NOTIFICATIONS);
        styleTab(appsButton, section == Section.APPS);
        styleTab(networkButton, section == Section.NETWORK);
        styleTab(ownButton, section == Section.OWN);
    }

    private void styleTab(Button b, boolean active) {
        b.setBackgroundColor(active ? Ui.ACCENT : Ui.SURFACE2);
        b.setTextColor(active ? Ui.BG : Ui.TEXT);
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            Toast.makeText(this, "Turn on usage access for Control.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Apps → Special app access → Usage access",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmClear() {
        String message;
        if (section == Section.NOTIFICATIONS) {
            message = "Clear the notification timeline stored on this phone?";
        } else if (section == Section.OWN) {
            message = "Clear Control's own event log and SMS command audit log?";
        } else {
            Toast.makeText(this, "App and network history belongs to Android and cannot be cleared here.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Clear history")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    if (section == Section.NOTIFICATIONS) {
                        NotificationFeedStore.clear(this);
                        HeldStore.clear(this);
                    } else {
                        OwnLog.clear(this);
                        SmsControl.clearLog(this);
                    }
                    load();
                })
                .show();
    }

    private void load() {
        styleTabs();
        boolean usageAccess = source.hasUsageAccess();
        accessLine.setText(usageAccess
                ? "Usage access: granted. Notification history depends on notification access."
                : "Usage access: not granted — Apps and Network need it. Tap Usage access.");
        rows.clear();
        rows.add(new Row("Loading…", "", ""));
        adapter.notifyDataSetChanged();

        new Thread(() -> {
            List<Row> loaded = new ArrayList<>();
            try {
                switch (section) {
                    case NOTIFICATIONS: loaded = notificationRows(); break;
                    case APPS: loaded = appRows(); break;
                    case NETWORK: loaded = networkRows(); break;
                    case OWN: loaded = ownRows(); break;
                }
            } catch (Exception e) {
                loaded.add(new Row("Not available", e.getMessage(),
                        "Android did not provide this data. Control will not guess or fill it in."));
            }
            final List<Row> result = loaded;
            runOnUiThread(() -> {
                rows.clear();
                rows.addAll(result);
                adapter.notifyDataSetChanged();
            });
        }, "activity-load").start();
    }

    private List<Row> notificationRows() {
        List<Row> out = new ArrayList<>();
        List<NotificationFeedStore.Entry> entries = source.notifications();
        out.add(new Row("Notifications", entries.size() + " in the rolling timeline (max 500)",
                "Newest first. HELD means Control removed it from the shade under your muting rules. "
                + "SHOWN means Android delivered it normally. Title and text are only what the posting app exposed.", true));
        if (entries.isEmpty()) {
            out.add(new Row("Nothing recorded yet", "Grant notification access and notifications will appear here.",
                    "The listener can only record notifications after Android grants notification access."));
        }
        for (NotificationFeedStore.Entry e : entries) {
            String title = e.title.isEmpty() ? e.app : e.app + " — " + e.title;
            String sub = time(e.time) + " · " + (e.held ? "HELD" : "SHOWN")
                    + (e.text.isEmpty() ? "" : " · " + e.text);
            String detail = "App: " + e.app + "\nPackage: " + e.pkg + "\nTime: "
                    + fullTime(e.time) + "\nState: " + (e.held ? "HELD" : "SHOWN")
                    + "\nTitle: " + dash(e.title) + "\nText: " + dash(e.text);
            out.add(new Row(title, sub, detail));
        }
        return out;
    }

    private List<Row> appRows() throws Exception {
        List<Row> out = new ArrayList<>();
        if (!source.hasUsageAccess()) {
            out.add(new Row("Usage access needed", "Tap Usage access above and turn on Control.",
                    "Android requires a separate permission before any app can see foreground time or app transitions."));
            return out;
        }
        List<ActivityDataSource.AppUsage> usage = source.appUsageToday();
        out.add(new Row("Foreground time today", usage.size() + " apps reported by Android",
                "Totals come from UsageStatsManager. Android may round or delay them.", true));
        if (usage.isEmpty()) out.add(new Row("No app usage reported yet", "", ""));
        for (ActivityDataSource.AppUsage u : usage) {
            String sub = "Foreground: " + duration(u.foregroundMs)
                    + " · Last used: " + (u.lastUsed > 0 ? time(u.lastUsed) : "not reported");
            out.add(new Row(u.app, sub, "App: " + u.app + "\nPackage: " + u.pkg
                    + "\n" + sub));
        }
        List<ActivityDataSource.Transition> transitions = source.transitionsToday();
        out.add(new Row("Foreground / background changes today", transitions.size() + " shown, newest first",
                "These are transitions Android chose to report through UsageStatsManager events.", true));
        if (transitions.isEmpty()) out.add(new Row("No transitions reported yet", "", ""));
        for (ActivityDataSource.Transition t : transitions) {
            String sub = time(t.time) + " · " + (t.foreground ? "came to foreground" : "went to background");
            out.add(new Row(t.app, sub, "App: " + t.app + "\nPackage: " + t.pkg + "\n" + sub));
        }
        return out;
    }

    private List<Row> networkRows() throws Exception {
        List<Row> out = new ArrayList<>();
        if (!source.hasUsageAccess()) {
            out.add(new Row("Usage access needed", "Tap Usage access above and turn on Control.",
                    "NetworkStatsManager is in the same Android permission family."));
            return out;
        }
        long end = System.currentTimeMillis();
        long start = networkSevenDays ? end - 7L * 24 * 60 * 60 * 1000
                : AndroidActivityDataSource.startOfToday();
        List<ActivityDataSource.NetworkUsage> usage = source.networkUsage(start, end);
        out.add(new Row(networkSevenDays ? "Network use — last 7 days" : "Network use — today",
                usage.size() + " apps/owners with traffic shown",
                "Wi-Fi and mobile totals combined from NetworkStatsManager. This shows volume, not destinations: Android does not tell normal apps which servers another app contacted.", true));
        if (usage.isEmpty()) {
            out.add(new Row("No network totals reported", "Android returned no positive Wi-Fi/mobile totals for this period.", ""));
        }
        for (ActivityDataSource.NetworkUsage n : usage) {
            String sub = "Total " + bytes(n.totalBytes()) + " · down " + bytes(n.rxBytes)
                    + " · up " + bytes(n.txBytes);
            out.add(new Row(n.app, sub, "Owner: " + n.app + "\nIdentifier: " + n.id + "\n" + sub));
        }
        return out;
    }

    private List<Row> ownRows() {
        List<Row> out = new ArrayList<>();
        out.add(new Row("Control's services", "Live state stored by Control on this phone",
                "The walkie-talkie line reflects the last session state reported by the Walkie screen.", true));
        for (String line : source.ownStatus()) {
            int split = line.indexOf(':');
            if (split > 0) out.add(new Row(line.substring(0, split), line.substring(split + 1).trim(), line));
            else out.add(new Row(line, "", line));
        }

        List<SmsControl.Entry> sms = SmsControl.entries(this);
        out.add(new Row("Command & bridge audit", sms.size() + " entr" + (sms.size() == 1 ? "y" : "ies"),
                "One shared audit log: authenticated SMS commands, rejected controller attempts, and every governed bridge control attempt with its origin, target, parameters, and result. SMS is authenticated, not encrypted.", true));
        if (sms.isEmpty()) out.add(new Row("No commands or bridge controls yet", "", ""));
        for (SmsControl.Entry e : sms) {
            String sub = fullTime(e.time) + " · origin " + e.origin + " · " + e.result;
            out.add(new Row(e.origin + " · " + e.command, sub,
                    "Time: " + fullTime(e.time) + "\nOrigin: " + e.origin
                    + "\nSender: " + dash(e.sender) + "\nAction: " + e.command
                    + "\nTarget: " + dash(e.target) + "\nParameters: " + dash(e.parameters)
                    + "\nResult: " + e.result));
        }

        List<OwnLog.Entry> logs = source.ownLogs();
        out.add(new Row("Control's own event log", logs.size() + " entries (ring buffer)",
                "Events generated inside Control only. This is not Android's system log, which normal apps cannot read.", true));
        if (logs.isEmpty()) out.add(new Row("No Control events yet", "", ""));
        for (OwnLog.Entry e : logs) {
            out.add(new Row(e.area, time(e.time) + " · " + e.message,
                    "Time: " + fullTime(e.time) + "\nArea: " + e.area + "\nEvent: " + e.message));
        }
        return out;
    }

    private class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int pos) { return rows.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Row row = rows.get(pos);
            LinearLayout layout = Ui.column(ControlRoomActivity.this);
            layout.setPadding(Ui.dp(ControlRoomActivity.this, 10),
                    Ui.dp(ControlRoomActivity.this, row.header ? 12 : 8),
                    Ui.dp(ControlRoomActivity.this, 10),
                    Ui.dp(ControlRoomActivity.this, row.header ? 8 : 8));
            layout.setBackgroundColor(row.header ? Ui.SURFACE2 : Ui.SURFACE);
            TextView title = Ui.text(ControlRoomActivity.this, row.title,
                    row.header ? 16 : 15, row.header ? Ui.ACCENT : Ui.TEXT);
            layout.addView(title);
            if (!row.subtitle.isEmpty()) {
                TextView sub = Ui.text(ControlRoomActivity.this, row.subtitle, 13, Ui.DIM);
                sub.setMaxLines(3);
                layout.addView(sub);
            }
            return layout;
        }
    }

    private static String time(long millis) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(millis));
    }

    private static String fullTime(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                .format(new Date(millis));
    }

    private static String duration(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return seconds + "s";
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        double kb = value / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String dash(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }
}
