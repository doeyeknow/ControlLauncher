package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Rolling, on-device timeline of notifications Android delivered to the
 * listener. Title/text are only what the posting app exposed in the
 * notification extras. This data is intentionally not part of the
 * portable profile export.
 */
public final class NotificationFeedStore {
    private static final String PREFS = "notification_feed";
    private static final String KEY = "entries";
    private static final int MAX = 500;

    public static final class Entry {
        public final long time;
        public final String key;
        public final String pkg;
        public final String app;
        public final String title;
        public final String text;
        public final boolean held;

        Entry(long time, String key, String pkg, String app, String title,
              String text, boolean held) {
            this.time = time;
            this.key = key;
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
            this.held = held;
        }
    }

    private NotificationFeedStore() {}

    public static synchronized void record(Context c, String key, long postTime,
                                           String pkg, String title, String text,
                                           boolean held) {
        try {
            JSONArray old = raw(c);
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("time", System.currentTimeMillis());
            entry.put("postTime", postTime);
            entry.put("key", key == null ? "" : key);
            entry.put("pkg", pkg == null ? "" : pkg);
            entry.put("title", title == null ? "" : title);
            entry.put("text", text == null ? "" : text);
            entry.put("held", held);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < MAX; i++) {
                JSONObject o = old.getJSONObject(i);
                // Notification updates reuse the key. Keep the newest
                // version once rather than flooding the timeline.
                if (entry.optString("key").equals(o.optString("key"))) continue;
                next.put(o);
            }
            save(c, next);
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String pkg = o.optString("pkg");
                out.add(new Entry(o.optLong("time"), o.optString("key"), pkg,
                        InstalledApps.label(c, pkg), o.optString("title"),
                        o.optString("text"), o.optBoolean("held")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized int count(Context c) {
        try {
            return raw(c).length();
        } catch (Exception e) {
            return 0;
        }
    }

    public static synchronized void clear(Context c) {
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
