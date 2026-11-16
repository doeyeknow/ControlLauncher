package app.control.launcher;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Control's own event ring buffer. This deliberately records events
 * generated inside this app; it is not Android's system log and does not
 * pretend to be. Entries persist on-device only and are capped.
 */
public final class OwnLog {
    private static final String FILE_NAME = "own-log.json";
    private static final int MAX = 240;

    public static final class Entry {
        public final long time;
        public final String area;
        public final String message;

        Entry(long time, String area, String message) {
            this.time = time;
            this.area = area;
            this.message = message;
        }
    }

    private OwnLog() {}

    public static synchronized void log(Context c, String area, String message) {
        String cleanArea = area == null || area.isEmpty() ? "Control" : area;
        String cleanMessage = message == null ? "" : message;
        Log.i("ControlOwnLog", cleanArea + ": " + cleanMessage);
        try {
            JSONArray old = raw(c);
            JSONArray next = new JSONArray();
            JSONObject entry = new JSONObject();
            entry.put("time", System.currentTimeMillis());
            entry.put("area", cleanArea);
            entry.put("message", cleanMessage);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < MAX; i++) {
                next.put(old.get(i));
            }
            write(c, next);
        } catch (Exception ignored) {
            // Logging must never break the feature doing the logging.
        }
    }

    public static synchronized List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = raw(c);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optLong("time"), o.optString("area"),
                        o.optString("message")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void clear(Context c) {
        try {
            write(c, new JSONArray());
        } catch (Exception ignored) {
        }
    }

    public static synchronized void wipe(Context c) {
        try {
            new File(c.getFilesDir(), FILE_NAME).delete();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray raw(Context c) throws Exception {
        File file = new File(c.getFilesDir(), FILE_NAME);
        if (!file.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 512_000)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
        }
    }

    private static void write(Context c, JSONArray arr) throws Exception {
        File tmp = new File(c.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
        File file = new File(c.getFilesDir(), FILE_NAME);
        if (!tmp.renameTo(file)) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            tmp.delete();
        }
    }
}
