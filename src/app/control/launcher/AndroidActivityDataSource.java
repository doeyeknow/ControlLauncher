package app.control.launcher;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Process;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normal-API implementation of the Activity data seam. */
public final class AndroidActivityDataSource implements ActivityDataSource {
    private final Context context;

    public AndroidActivityDataSource(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<NotificationFeedStore.Entry> notifications() {
        return NotificationFeedStore.list(context);
    }

    @Override
    public List<AppUsage> appUsageToday() throws Exception {
        UsageStatsManager manager = manager();
        long start = startOfToday();
        long now = System.currentTimeMillis();
        List<UsageStats> stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, start, now);
        Map<String, MutableUsage> byPackage = new HashMap<>();
        if (stats != null) {
            for (UsageStats s : stats) {
                if (s.getLastTimeUsed() < start && s.getTotalTimeInForeground() <= 0) continue;
                MutableUsage m = byPackage.get(s.getPackageName());
                if (m == null) {
                    m = new MutableUsage();
                    byPackage.put(s.getPackageName(), m);
                }
                m.foregroundMs += s.getTotalTimeInForeground();
                m.lastUsed = Math.max(m.lastUsed, s.getLastTimeUsed());
            }
        }
        List<AppUsage> out = new ArrayList<>();
        for (Map.Entry<String, MutableUsage> e : byPackage.entrySet()) {
            if (e.getValue().foregroundMs <= 0 && e.getValue().lastUsed < start) continue;
            out.add(new AppUsage(e.getKey(), labelForPackage(e.getKey()),
                    e.getValue().foregroundMs, e.getValue().lastUsed));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.foregroundMs, a.foregroundMs));
        return out;
    }

    @Override
    public List<Transition> transitionsToday() throws Exception {
        UsageStatsManager manager = manager();
        long start = startOfToday();
        long now = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(start, now);
        List<Transition> out = new ArrayList<>();
        UsageEvents.Event event = new UsageEvents.Event();
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            if (type != UsageEvents.Event.MOVE_TO_FOREGROUND
                    && type != UsageEvents.Event.MOVE_TO_BACKGROUND) continue;
            String pkg = event.getPackageName();
            if (pkg == null) continue;
            out.add(new Transition(event.getTimeStamp(), pkg, labelForPackage(pkg),
                    type == UsageEvents.Event.MOVE_TO_FOREGROUND));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        if (out.size() > 120) return new ArrayList<>(out.subList(0, 120));
        return out;
    }

    @Override
    public List<NetworkUsage> networkUsage(long start, long end) throws Exception {
        NetworkStatsManager manager = (NetworkStatsManager)
                context.getSystemService(Context.NETWORK_STATS_SERVICE);
        if (manager == null) throw new Exception("Network stats are unavailable on this phone.");
        Map<Integer, long[]> byUid = new LinkedHashMap<>();
        addNetworkType(manager, ConnectivityManager.TYPE_WIFI, start, end, byUid);
        addNetworkType(manager, ConnectivityManager.TYPE_MOBILE, start, end, byUid);

        List<NetworkUsage> out = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : byUid.entrySet()) {
            long rx = e.getValue()[0];
            long tx = e.getValue()[1];
            if (rx + tx <= 0) continue;
            out.add(new NetworkUsage(uidLabelId(e.getKey()), labelForUid(e.getKey()), rx, tx));
        }
        Collections.sort(out, (a, b) -> Long.compare(b.totalBytes(), a.totalBytes()));
        if (out.size() > 60) return new ArrayList<>(out.subList(0, 60));
        return out;
    }

    private void addNetworkType(NetworkStatsManager manager, int type, long start,
                                long end, Map<Integer, long[]> byUid) {
        NetworkStats stats = null;
        try {
            stats = manager.querySummary(type, null, start, end);
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            while (stats != null && stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                long[] totals = byUid.get(bucket.getUid());
                if (totals == null) {
                    totals = new long[2];
                    byUid.put(bucket.getUid(), totals);
                }
                totals[0] += bucket.getRxBytes();
                totals[1] += bucket.getTxBytes();
            }
        } catch (Exception ignored) {
            // One radio family can be unavailable (for example mobile
            // subscriber identity); the other family's real data remains.
        } finally {
            if (stats != null) stats.close();
        }
    }

    @Override
    public List<String> ownStatus() {
        List<String> lines = new ArrayList<>();
        boolean filterOn = Prefs.get(context).getBoolean(Prefs.KEY_FILTER_ON, false);
        long blocked = Prefs.get(context).getLong(Prefs.KEY_BLOCKED_COUNT, 0);
        lines.add("Ad filter VPN: " + (filterOn ? "on" : "off") + " · " + blocked
                + " DNS lookups blocked · resolver " + Prefs.upstreamDns(context));
        lines.add("Notification listener: " + (listenerEnabled() ? "connected by Android permission"
                : "access not granted") + " · " + NotificationFeedStore.count(context)
                + " notifications in timeline · " + HeldStore.count(context) + " held");
        lines.add("Notification mode: " + (Prefs.get(context).getBoolean(Prefs.KEY_QUIET_ALL, false)
                ? "QUIET — all notifications held" : "normal per-app muting"));
        lines.add("Relay chat: profile key " + Profile.relayShortFingerprint(context)
                + (Prefs.get(context).getBoolean(Prefs.KEY_RELAY_REGISTERED, false)
                        ? " · registered with the relay" : " · not registered yet")); 
        int channel = Prefs.get(context).getInt(ChannelsActivity.PREF_LAST_CHANNEL, 1);
        org.json.JSONObject slot = Profile.slot(context, channel);
        lines.add("Walkie-talkie: channel " + Profile.slotDisplayName(slot)
                + " · " + TunerView.frequencyText(channel) + " · "
                + Prefs.get(context).getString(Prefs.KEY_WALKIE_STATE,
                "no live local session reported"));
        lines.add("Walkie outbox on this channel: "
                + WalkieStore.outbox(context, Profile.slotIdentity(slot)).size()
                + " queued text(s)");
        lines.add("Text control (SMS): " + (Profile.smsControlEnabled(context) ? "on" : "off")
                + " · " + SmsControl.entries(context).size() + " audited command(s)"
                + " · authenticated, not encrypted");
        lines.add("App lock: " + (Prefs.get(context).getBoolean(Prefs.KEY_APP_LOCKED, false)
                ? "locked" : "unlocked"));
        lines.add("Profile: " + (Profile.exists(context) ? "present on this phone" : "not created yet"));
        lines.add("Deep shell bridge: " + Prefs.get(context).getString(
                Prefs.KEY_BRIDGE_STATE, "not checked yet — open Deep view"));
        return lines;
    }

    @Override
    public List<OwnLog.Entry> ownLogs() {
        return OwnLog.entries(context);
    }

    private UsageStatsManager manager() throws Exception {
        UsageStatsManager manager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) throw new Exception("Usage stats are unavailable on this phone.");
        if (!hasUsageAccess()) throw new Exception("Usage access has not been granted.");
        return manager;
    }

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName me = new ComponentName(context, NotificationHoldService.class);
        return flat.contains(me.flattenToString()) || flat.contains(context.getPackageName());
    }

    private String labelForPackage(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private String labelForUid(int uid) {
        if (uid == Process.ROOT_UID) return "Android root / kernel accounting";
        if (uid == Process.SYSTEM_UID) return "Android system";
        if (uid == -4) return "Removed apps";
        if (uid == -5) return "Tethering";
        try {
            String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
            if (pkgs == null || pkgs.length == 0) return "UID " + uid;
            List<String> labels = new ArrayList<>();
            for (String pkg : pkgs) labels.add(labelForPackage(pkg));
            return String.join(", ", labels);
        } catch (Exception e) {
            return "UID " + uid;
        }
    }

    private String uidLabelId(int uid) {
        try {
            String[] pkgs = context.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) return String.join(", ", pkgs);
        } catch (Exception ignored) {
        }
        return "uid " + uid;
    }

    public static long startOfToday() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private static final class MutableUsage {
        long foregroundMs;
        long lastUsed;
    }
}
