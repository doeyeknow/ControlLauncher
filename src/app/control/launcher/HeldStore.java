package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Notifications held back by the listener, newest first. JSON in prefs. */
public final class HeldStore {
    private static final String FILE = "held_store";
    private static final String KEY = "held";
    private static final int MAX = 100;

    public static final class Held {
        public final String pkg;
        public final String title;
        public final String text;
        public final long time;

        Held(String pkg, String title, String text, long time) {
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.time = time;
        }
    }

    private HeldStore() {}

    public static synchronized void add(Context c, String pkg, String title, String text) {
        try {
            JSONArray arr = raw(c);
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("title", title == null ? "" : title);
            o.put("text", text == null ? "" : text);
            o.put("time", System.currentTimeMillis());
            JSONArray next = new JSONArray();
            next.put(o);
            for (int i = 0; i < arr.length() && next.length() < MAX; i++) {
                next.put(arr.get(i));
            }
            save(c, next);
        } catch (Exception ignored) {
        }
    }

    public static synchronized List<Held> list(Context c) {
        List<Held> out = new ArrayList<>();
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Held(o.optString("pkg"), o.optString("title"),
                        o.optString("text"), o.optLong("time")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static int count(Context c) {
        try {
            return raw(c).length();
        } catch (Exception e) {
            return 0;
        }
    }

    public static synchronized void clear(Context c) {
        save(c, new JSONArray());
    }

    private static JSONArray raw(Context c) throws Exception {
        String s = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "[]");
        return new JSONArray(s == null ? "[]" : s);
    }

    private static void save(Context c, JSONArray arr) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }
}
