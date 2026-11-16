package app.control.launcher;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.BatteryManager;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SMS remote control.
 *
 * Security model: authentication, not encryption. A command is obeyed
 * only when (1) Control is enabled, (2) the sender matches a profile
 * contact flagged as a controller, and (3) the first whitespace-delimited
 * token hashes to the profile's stored command-secret hash. SMS itself
 * remains readable by carriers; the UI and notes say so plainly.
 */
public final class SmsControl {
    private static final String PREFS = "sms_audit";
    private static final String KEY = "entries";
    private static final int MAX_LOG = 120;

    public static final class Entry {
        public final long time;
        public final String sender;
        public final String command;
        public final String result;
        public final String origin;
        public final String target;
        public final String parameters;

        Entry(long time, String sender, String command, String result) {
            this(time, sender, command, result,
                    sender == null || sender.isEmpty() ? "SMS" : "SMS", "", "");
        }

        Entry(long time, String sender, String command, String result,
              String origin, String target, String parameters) {
            this.time = time;
            this.sender = sender;
            this.command = command;
            this.result = result;
            this.origin = origin == null || origin.isEmpty() ? "SMS" : origin;
            this.target = target == null ? "" : target;
            this.parameters = parameters == null ? "" : parameters;
        }
    }

    private SmsControl() {}

    public static void handle(Context context, String sender, String body) {
        Context c = context.getApplicationContext();
        if (!Profile.exists(c) || !Profile.smsControlEnabled(c)) return;
        if (!Profile.isController(c, sender)) return; // total silence for strangers
        if (body == null) return;
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return;
        String[] firstSplit = trimmed.split("\\s+", 2);
        String secret = firstSplit[0];
        if (!Profile.smsSecretMatches(c, secret)) {
            log(c, sender, "AUTH", "Rejected — wrong command secret");
            return;
        }
        if (firstSplit.length < 2 || firstSplit[1].trim().isEmpty()) {
            log(c, sender, "(empty)", "Ignored — no command after the secret");
            return;
        }
        String commandLine = firstSplit[1].trim();
        String upper = commandLine.toUpperCase(Locale.US);
        String[] parts = upper.split("\\s+");

        try {
            if ("STATUS".equals(upper)) {
                String reply = status(c);
                finish(c, sender, "STATUS", "Replied with status", reply);
            } else if ("QUIET".equals(upper)) {
                Prefs.get(c).edit().putBoolean(Prefs.KEY_QUIET_ALL, true).apply();
                OwnLog.log(c, "Text control", "QUIET executed from SMS");
                finish(c, sender, "QUIET", "All notifications now held",
                        "Control: QUIET on. All notifications are being held.");
            } else if ("LOUD".equals(upper)) {
                Prefs.get(c).edit().putBoolean(Prefs.KEY_QUIET_ALL, false).apply();
                OwnLog.log(c, "Text control", "LOUD executed from SMS");
                finish(c, sender, "LOUD", "Per-app notification muting restored",
                        "Control: QUIET off. Your normal per-app muting applies.");
            } else if ("FILTER".equals(parts[0]) && parts.length == 2
                    && ("ON".equals(parts[1]) || "OFF".equals(parts[1]))) {
                filter(c, sender, "ON".equals(parts[1]));
            } else if ("TUNE".equals(parts[0]) && parts.length == 2) {
                tune(c, sender, parts[1]);
            } else if ("LOCATE".equals(upper)) {
                String reply = locate(c);
                finish(c, sender, "LOCATE", reply.startsWith("Control: location unavailable")
                        ? "Location unavailable" : "Replied with last known location", reply);
            } else if ("LOCK".equals(upper)) {
                if (AdminControl.isActive(c)) {
                    // Device admin on: a real device lock, not just the UI.
                    boolean ok = AdminControl.lockNow(c, "SMS", sender);
                    finish(c, sender, "LOCK",
                            ok ? "Device locked (device admin)" : "Device lock failed",
                            ok ? "Control: phone locked."
                                    : "Control: the device lock failed — see the app log.");
                } else {
                    Prefs.get(c).edit().putBoolean(Prefs.KEY_APP_LOCKED, true).apply();
                    OwnLog.log(c, "Text control", "LOCK executed from SMS");
                    finish(c, sender, "LOCK", "Control UI locked (no device admin)",
                            "Control: locked (Control screens only — device admin is "
                            + "off, so this is not a full phone lock). Unlock with the "
                            + "command secret inside the app.");
                }
            } else if ("WIPE DEVICE".equals(upper)) {
                // Separate from WIPE PROFILE: this erases the WHOLE phone,
                // and only through device admin. Two-step, like WIPE PROFILE.
                long until = System.currentTimeMillis() + 10 * 60 * 1000L;
                Prefs.get(c).edit()
                        .putLong(Prefs.KEY_WIPE_DEVICE_ARMED_UNTIL, until)
                        .putString(Prefs.KEY_WIPE_DEVICE_ARMED_FROM, Profile.phoneKey(sender))
                        .apply();
                log(c, sender, "WIPE DEVICE", "Armed for 10 minutes; confirmation required");
                reply(c, sender, AdminControl.isActive(c)
                        ? "Control: WIPE DEVICE armed. This ERASES THE WHOLE PHONE — "
                            + "apps, accounts, photos, and Control itself. To confirm "
                            + "within 10 minutes, send your secret followed by: "
                            + "CONFIRM WIPE DEVICE"
                        : "Control: WIPE DEVICE armed, but device admin is OFF, so "
                            + "Control cannot erase this phone even if you confirm. "
                            + "Turn on device admin in Settings → Device admin first. "
                            + "(WIPE PROFILE still works without it.)");
            } else if ("CONFIRM WIPE DEVICE".equals(upper)) {
                confirmWipeDevice(c, sender);
            } else if ("WIPE PROFILE".equals(upper)) {
                long until = System.currentTimeMillis() + 10 * 60 * 1000L;
                Prefs.get(c).edit()
                        .putLong(Prefs.KEY_WIPE_ARMED_UNTIL, until)
                        .putString(Prefs.KEY_WIPE_ARMED_FROM, Profile.phoneKey(sender))
                        .apply();
                log(c, sender, "WIPE PROFILE", "Armed for 10 minutes; confirmation required");
                reply(c, sender, "Control: WIPE armed. This erases the profile, contacts, "
                        + "held notifications, walkie history, and app data for Control. "
                        + "To confirm within 10 minutes, send your secret followed by: CONFIRM WIPE");
            } else if ("CONFIRM WIPE".equals(upper)) {
                confirmWipe(c, sender);
            } else {
                log(c, sender, parts[0], "Ignored — unknown or malformed command");
            }
        } catch (Exception e) {
            log(c, sender, parts.length > 0 ? parts[0] : "COMMAND",
                    "Failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static void filter(Context c, String sender, boolean on) {
        if (!on) {
            Intent stop = new Intent(c, DnsVpnService.class);
            stop.setAction(DnsVpnService.ACTION_STOP);
            try {
                c.startService(stop);
            } catch (Exception ignored) {
            }
            Prefs.get(c).edit().putBoolean(Prefs.KEY_FILTER_ON, false).apply();
            finish(c, sender, "FILTER OFF", "Ad filter stopped", "Control: ad filter off.");
            return;
        }
        Intent consent = VpnService.prepare(c);
        if (consent != null) {
            finish(c, sender, "FILTER ON", "Blocked — VPN consent not yet granted",
                    "Control: the ad filter needs one-time VPN approval inside the app first.");
            return;
        }
        try {
            Intent start = new Intent(c, DnsVpnService.class);
            start.setAction(DnsVpnService.ACTION_START);
            c.startForegroundService(start);
            // Pre-declared SMS → bridge mapping (the only one): FILTER ON
            // may set the recommended Private DNS resolver, but only if
            // the governed bridge is already running. Audited as SMS.
            String dns = BridgeControl.trySetFilterDns(c, BridgeControl.ORIGIN_SMS);
            boolean dnsSet = dns.startsWith("Private DNS set");
            finish(c, sender, "FILTER ON", "Ad filter start requested; " + dns,
                    "Control: ad filter on." + (dnsSet ? " Private DNS set by the bridge." : ""));
        } catch (Exception e) {
            finish(c, sender, "FILTER ON", "Start failed: " + e.getMessage(),
                    "Control: Android would not start the filter from a text. Open Control and turn it on.");
        }
    }

    private static void tune(Context c, String sender, String numberText) {
        try {
            int n = Integer.parseInt(numberText);
            if (n < 1 || n > Profile.BANK_SIZE) throw new NumberFormatException();
            Prefs.get(c).edit().putInt(ChannelsActivity.PREF_LAST_CHANNEL, n).apply();
            org.json.JSONObject slot = Profile.slot(c, n);
            finish(c, sender, "TUNE " + n, "Walkie channel set",
                    "Control: tuned to " + Profile.slotDisplayName(slot)
                            + " (" + TunerView.frequencyText(n) + ").");
        } catch (Exception e) {
            log(c, sender, "TUNE", "Ignored — channel must be 1–" + Profile.BANK_SIZE);
        }
    }

    private static String status(Context c) {
        int battery = batteryPercent(c);
        boolean filter = Prefs.get(c).getBoolean(Prefs.KEY_FILTER_ON, false);
        int channel = Prefs.get(c).getInt(ChannelsActivity.PREF_LAST_CHANNEL, 1);
        org.json.JSONObject slot = Profile.slot(c, channel);
        boolean quiet = Prefs.get(c).getBoolean(Prefs.KEY_QUIET_ALL, false);
        return "Control: battery " + (battery >= 0 ? battery + "%" : "unknown")
                + "; filter " + (filter ? "on" : "off")
                + "; notifications " + (quiet ? "QUIET" : "normal")
                + "; channel " + Profile.slotDisplayName(slot)
                + "; internet " + (internetAvailable(c) ? "available" : "unavailable")
                + "; walkie " + Prefs.get(c).getString(Prefs.KEY_WALKIE_STATE,
                "no live local session reported") + ".";
    }

    private static int batteryPercent(Context c) {
        try {
            Intent battery = c.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return -1;
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return -1;
            return Math.round(level * 100f / scale);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean internetAvailable(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static String locate(Context c) {
        boolean granted = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            return "Control: location unavailable — location permission is off for Control.";
        }
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return "Control: location unavailable on this phone.";
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate != null && (best == null
                        || candidate.getTime() > best.getTime())) best = candidate;
            }
            if (best == null) {
                return "Control: location unavailable — Android has no recent location fix.";
            }
            long ageMinutes = Math.max(0,
                    (System.currentTimeMillis() - best.getTime()) / 60000L);
            return String.format(Locale.US,
                    "Control location: https://maps.google.com/?q=%.5f,%.5f (±%.0fm, %d min old)",
                    best.getLatitude(), best.getLongitude(),
                    best.hasAccuracy() ? best.getAccuracy() : -1, ageMinutes);
        } catch (SecurityException e) {
            return "Control: location unavailable — location permission is off for Control.";
        } catch (Exception e) {
            return "Control: location unavailable — " + e.getMessage();
        }
    }

    private static void confirmWipeDevice(Context c, String sender) {
        long until = Prefs.get(c).getLong(Prefs.KEY_WIPE_DEVICE_ARMED_UNTIL, 0);
        String from = Prefs.get(c).getString(Prefs.KEY_WIPE_DEVICE_ARMED_FROM, "");
        boolean armed = until >= System.currentTimeMillis()
                && Profile.phoneKey(sender).equals(from);
        if (!armed) {
            log(c, sender, "CONFIRM WIPE DEVICE", "Ignored — no matching armed wipe");
            return;
        }
        // Disarm first: the window never survives an attempt.
        Prefs.get(c).edit()
                .putLong(Prefs.KEY_WIPE_DEVICE_ARMED_UNTIL, 0)
                .putString(Prefs.KEY_WIPE_DEVICE_ARMED_FROM, "")
                .apply();
        if (!AdminControl.isActive(c)) {
            SmsControl.logDetailed(c, "SMS", sender, "WIPE DEVICE",
                    "Device admin", "", "Confirmed but refused — device admin is not active");
            reply(c, sender, "Control: device admin is off, so the phone was NOT erased. "
                    + "Turn on device admin in Settings → Device admin, then arm again.");
            return;
        }
        reply(c, sender, "Control: erasing this phone now.");
        AdminControl.wipeDevice(c, "SMS", sender);
    }

    private static void confirmWipe(Context c, String sender) {
        long until = Prefs.get(c).getLong(Prefs.KEY_WIPE_ARMED_UNTIL, 0);
        String from = Prefs.get(c).getString(Prefs.KEY_WIPE_ARMED_FROM, "");
        boolean armed = until >= System.currentTimeMillis()
                && Profile.phoneKey(sender).equals(from);
        if (!armed) {
            log(c, sender, "CONFIRM WIPE", "Ignored — no matching armed wipe");
            return;
        }
        log(c, sender, "CONFIRM WIPE", "Profile wipe executed");
        reply(c, sender, "Control: profile wiped.");
        wipeEverything(c);
    }

    private static void wipeEverything(Context c) {
        try {
            Intent stop = new Intent(c, DnsVpnService.class);
            stop.setAction(DnsVpnService.ACTION_STOP);
            c.startService(stop);
        } catch (Exception ignored) {
        }
        Profile.wipe(c);
        HeldStore.clear(c);
        NotificationFeedStore.wipe(c);
        WalkieStore.wipe(c);
        OwnLog.wipe(c);
        clearLog(c);
        deleteRecursive(new File(c.getFilesDir(), "walkie"));
        Prefs.get(c).edit().clear().apply();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private static void finish(Context c, String sender, String command,
                               String result, String replyText) {
        boolean sent = reply(c, sender, replyText);
        log(c, sender, command, result + (sent ? "" : " (reply not sent — SEND_SMS unavailable)"));
        OwnLog.log(c, "Text control", command + " — " + result);
    }

    private static boolean reply(Context c, String sender, String text) {
        try {
            if (c.checkSelfPermission(Manifest.permission.SEND_SMS)
                    != PackageManager.PERMISSION_GRANTED) return false;
            SmsManager manager = SmsManager.getDefault();
            if (manager == null) return false;
            ArrayList<String> parts = manager.divideMessage(text);
            if (parts.size() == 1) manager.sendTextMessage(sender, null, text, null, null);
            else manager.sendMultipartTextMessage(sender, null, parts, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- audit log ----

    public static synchronized void log(Context c, String sender, String command,
                                        String result) {
        logDetailed(c, "SMS", sender, command, "", "", result);
    }

    /** Shared command/control audit log. SMS commands and governed
     *  bridge writes use the same store and retention cap. */
    public static synchronized void logDetailed(Context c, String origin, String sender,
                                                String command, String target,
                                                String parameters, String result) {
        try {
            JSONArray old = raw(c);
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("time", System.currentTimeMillis());
            entry.put("sender", sender == null ? "" : sender);
            entry.put("command", command == null ? "" : command);
            entry.put("result", result == null ? "" : result);
            entry.put("origin", origin == null ? "" : origin);
            entry.put("target", target == null ? "" : target);
            entry.put("parameters", parameters == null ? "" : parameters);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < MAX_LOG; i++) {
                next.put(old.get(i));
            }
            save(c, next);
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String sender = o.optString("sender");
                String origin = o.optString("origin", "");
                if (origin.isEmpty()) origin = sender.isEmpty() ? "SMS" : "SMS";
                out.add(new Entry(o.optLong("time"), sender,
                        o.optString("command"), o.optString("result"), origin,
                        o.optString("target"), o.optString("parameters")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void clearLog(Context c) {
        save(c, new JSONArray());
    }

    public static synchronized void wipe(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static JSONArray raw(Context c) throws Exception {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]");
        return new JSONArray(s == null ? "[]" : s);
    }

    private static void save(Context c, JSONArray arr) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
