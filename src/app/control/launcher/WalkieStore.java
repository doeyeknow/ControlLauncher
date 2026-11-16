package app.control.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Device-local walkie-talkie history: one thread per channel slot
 * (keyed by slot identity) and a store-and-forward outbox for texts
 * written while no path was open. This is history, not identity — it
 * deliberately does NOT live in the portable profile.
 */
public final class WalkieStore {
    private static final String FILE_NAME = "walkie.json";
    private static final int MAX_THREAD = 200;

    private static JSONObject root;
    private static File file;

    private WalkieStore() {}

    public static synchronized void wipe(Context c) {
        try {
            new java.io.File(c.getFilesDir(), FILE_NAME).delete();
        } catch (Exception ignored) {
        }
        root = null;
        file = null;
    }

    private static synchronized JSONObject load(Context c) {
        if (root != null) return root;
        file = new File(c.getFilesDir(), FILE_NAME);
        JSONObject parsed = null;
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                parsed = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (Exception e) {
                parsed = null;
            }
        }
        root = parsed != null ? parsed : new JSONObject();
        if (!root.has("threads")) put(root, "threads", new JSONObject());
        if (!root.has("outbox")) put(root, "outbox", new JSONObject());
        return root;
    }

    /** Persist after mutating a live entry object in place. */
    public static synchronized void saveNow(Context c) {
        load(c);
        save(c);
    }

    private static void put(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
    }

    private static synchronized void save(Context c) {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static JSONArray bucket(Context c, String section, String slotIdentity) {
        JSONObject all = load(c).optJSONObject(section);
        JSONArray arr = all != null ? all.optJSONArray(slotIdentity) : null;
        if (arr == null) {
            arr = new JSONArray();
            if (all != null) put(all, slotIdentity, arr);
        }
        return arr;
    }

    // ---- thread ----

    public static synchronized List<JSONObject> thread(Context c, String slotIdentity) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = bucket(c, "threads", slotIdentity);
        for (int i = 0; i < arr.length(); i++) out.add(arr.optJSONObject(i));
        return out;
    }

    public static synchronized void append(Context c, String slotIdentity, JSONObject entry) {
        JSONArray arr = bucket(c, "threads", slotIdentity);
        arr.put(entry);
        while (arr.length() > MAX_THREAD) arr.remove(0);
        save(c);
    }

    /** True when the channel's stored thread already holds an
     *  entry with this relay client message id — the
     *  cross-poller dedup backstop (v1.19): the walkie page's
     *  loop and the subscription service share the persisted
     *  cursors, but either can restart, so the store is the
     *  authority that makes duplicate delivery impossible.
     *  An empty id never matches. */
    public static synchronized boolean hasCmid(Context c, String slotIdentity,
                                               String cmid) {
        if (cmid == null || cmid.isEmpty()) return false;
        return RelayIngest.alreadyStored(thread(c, slotIdentity), cmid);
    }

    public static JSONObject entry(String kind, String from, String text, String mxc,
                                   int samples, String road, boolean mine, String state) {
        JSONObject e = new JSONObject();
        put(e, "ts", System.currentTimeMillis());
        put(e, "kind", kind);
        put(e, "from", from);
        put(e, "text", text == null ? "" : text);
        put(e, "mxc", mxc == null ? "" : mxc);
        put(e, "samples", samples);
        put(e, "road", road == null ? "" : road);
        put(e, "mine", mine);
        put(e, "state", state == null ? "sent" : state);
        // v1.18: every entry starts unplayed; HistoryRule moves it
        // to History (played=true, in place) only after a completed
        // playback. Entries written before v1.18 lack the key and
        // read as unplayed — the same rule, by default.
        put(e, "played", false);
        return e;
    }

    // ---- outbox (store-and-forward) ----

    public static synchronized void queueText(Context c, String slotIdentity, String text) {
        queueText(c, slotIdentity, System.currentTimeMillis(), text, false);
    }

    public static synchronized void queueText(Context c, String slotIdentity,
                                              long ts, String text) {
        queueText(c, slotIdentity, ts, text, false);
    }

    /** v1.11: queued low-data texts keep their flag, so a message
     *  flushed later still arrives marked (and is read aloud). */
    public static synchronized void queueText(Context c, String slotIdentity,
                                              long ts, String text, boolean lowData) {
        JSONObject o = new JSONObject();
        put(o, "ts", ts);
        put(o, "text", text);
        if (lowData) put(o, "ld", true);
        bucket(c, "outbox", slotIdentity).put(o);
        save(c);
    }

    public static synchronized List<JSONObject> outbox(Context c, String slotIdentity) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = bucket(c, "outbox", slotIdentity);
        for (int i = 0; i < arr.length(); i++) out.add(arr.optJSONObject(i));
        return out;
    }

    public static synchronized void removeQueued(Context c, String slotIdentity, long ts) {
        JSONArray arr = bucket(c, "outbox", slotIdentity);
        JSONArray next = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optLong("ts") != ts) next.put(o);
        }
        JSONObject all = load(c).optJSONObject("outbox");
        if (all != null) put(all, slotIdentity, next);
        save(c);
    }
}
