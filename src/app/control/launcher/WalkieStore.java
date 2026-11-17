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

    // ---- wire forms (v1.32) ----

    /** Stamps the wire form exactly as sent/received onto a
     *  thread entry, plus the original text's byte count and
     *  whether the payload arrived coded — the long-press
     *  "View encoded message" dialog reads these three keys
     *  and nothing else. Entries written before v1.32 lack
     *  the keys and read as unrecorded (hasWire false), never
     *  a crash. A plainBytes of -1 means the original size
     *  is unknown (an undecoded payload); the dialog omits
     *  the size line. HistoryRule moves the whole entry, so
     *  a played message keeps its wire form in History. */
    public static void stampWire(JSONObject e, String wire, int plainBytes,
                                 boolean coded) {
        if (e == null || wire == null) return;
        put(e, "wire", wire);
        put(e, "plainBytes", plainBytes);
        put(e, "coded", coded);
    }

    /** True when the entry carries a recorded wire form. */
    public static boolean hasWire(JSONObject e) {
        return e != null && e.has("wire");
    }

    // ---- bridged bids (v1.24) ----

    /** Bridge ids this phone has already FORWARDED, persisted
     *  across restarts: "a message already forwarded is never
     *  forwarded again" must survive a restart, and live
     *  bursts leave no thread entry to check. Capped at 500
     *  (oldest first out) — the traversed set on the message
     *  itself is the backstop for an evicted bid. This is a
     *  forwarding record, not a message store: ids only,
     *  never contents. */
    private static final String BRIDGE_SEEN_KEY = "bridgeSeen";
    private static final int MAX_BRIDGE_SEEN = 500;

    public static synchronized boolean hasForwardedBid(Context c, String bid) {
        if (bid == null || bid.isEmpty()) return false;
        JSONArray arr = load(c).optJSONArray(BRIDGE_SEEN_KEY);
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            if (bid.equals(arr.optString(i, ""))) return true;
        }
        return false;
    }

    public static synchronized void noteForwardedBid(Context c, String bid) {
        if (bid == null || bid.isEmpty()) return;
        JSONArray arr = load(c).optJSONArray(BRIDGE_SEEN_KEY);
        if (arr == null) {
            arr = new JSONArray();
            put(load(c), BRIDGE_SEEN_KEY, arr);
        }
        for (int i = 0; i < arr.length(); i++) {
            if (bid.equals(arr.optString(i, ""))) return;
        }
        arr.put(bid);
        while (arr.length() > MAX_BRIDGE_SEEN) arr.remove(0);
        save(c);
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
