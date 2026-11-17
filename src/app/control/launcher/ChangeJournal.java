package app.control.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The change journal (v1.37). Pure Java — no Android classes —
 * so the harness exercises the exact shipping code, including
 * the file codec (against temp files).
 *
 * Every persistent-state write the Deep controls perform
 * records an entry BEFORE the write runs: the op, the target,
 * the previous value (read first through the existing get/read
 * op), the new value, and the timestamp. An entry is persisted
 * in the PENDING state first and finalized only when the write
 * reports success — a refused or failed write leaves no entry,
 * because nothing changed (the audit log already records the
 * attempt). A pending entry left behind by a dead process is
 * dropped on load: its outcome is unknowable, and the audit log
 * is the record of the attempt.
 *
 * Restoring writes the previous value back through the same
 * governed op and marks the entry restored; restores never
 * append new entries, so restore-all cannot loop. Undo paths
 * (battery reset, Doze unforce, display reset-to-originals,
 * install-existing) are not journaled either: they are the
 * return to a previous state, not a new change.
 *
 * Entries whose previous value could not be read, and actions
 * with no meaningful restore (clear-data, Wi-Fi forget, user
 * removal, cache trim, user creation, APK install), are
 * recorded as unrestorable with a plain-words reason — never a
 * faked restore. A flag or setting that was UNSET before
 * Control changed it is unrestorable: removing a value needs a
 * delete the bridge does not offer, and the journal says so
 * instead of pretending an empty write is a removal.
 *
 * The journal lives in one app-private file
 * ({@link #FILE_NAME} under the app's files directory). It is
 * device-specific state and is NEVER part of the portable
 * profile export, which reads only Prefs keys.
 *
 * "Defaults" for the flags reset are the values recorded before
 * the app first changed each flag: the earliest entry's
 * previous value. Flags the app never changed have no journal
 * entries and are untouched; flags changed outside the app
 * cannot be known.
 */
public final class ChangeJournal {

    /** The journal file name, under the app's files dir. */
    public static final String FILE_NAME = "change-journal.json";

    /** Entries kept; beyond this the oldest restored entries
     *  are dropped first, then the oldest entries overall. */
    public static final int MAX_ENTRIES = 500;

    /** Ops that count as "flags" for the reset-all-flags
     *  action: device_config puts and settings puts (both the
     *  Display editor's op and the launcher-setting op, which
     *  write the same keys). */
    public static final String OP_DEVICE_CONFIG_PUT = "DEVICE_CONFIG_PUT";
    public static final String OP_SETTING_PUT = "SETTING_PUT";
    public static final String OP_LAUNCHER_SETTING = "SET_LAUNCHER_SETTING";

    /** One recorded change. Immutable: state transitions
     *  (pending → applied, unrestored → restored) replace the
     *  entry with a copy. */
    public static final class Entry {
        public final long id;
        public final long timeMs;
        /** The BridgeControl action name, e.g. SET_APP_ENABLED. */
        public final String op;
        /** Display target: a package, a flag, a setting. */
        public final String target;
        /** Raw previous value, restore-ready; null = unknown. */
        public final String previous;
        /** Raw new value, restore-ready. */
        public final String current;
        public final boolean restorable;
        /** Plain-words reason when unrestorable, else null. */
        public final String unrestorableReason;
        public final boolean restored;
        /** False while the write is still in flight. */
        public final boolean applied;
        /** Package target, when the op is package-scoped. */
        public final String pkg;
        /** Flag/setting namespace, when applicable. */
        public final String namespace;
        /** Flag/setting key, when applicable. */
        public final String key;
        /** Op-specific restore payload: the permission, the
         *  AppOp name, or null when the op needs none. */
        public final String detail;

        Entry(long id, long timeMs, String op, String target,
              String previous, String current, boolean restorable,
              String unrestorableReason, boolean restored, boolean applied,
              String pkg, String namespace, String key, String detail) {
            this.id = id;
            this.timeMs = timeMs;
            this.op = op;
            this.target = target;
            this.previous = previous;
            this.current = current;
            this.restorable = restorable;
            this.unrestorableReason = unrestorableReason;
            this.restored = restored;
            this.applied = applied;
            this.pkg = pkg;
            this.namespace = namespace;
            this.key = key;
            this.detail = detail;
        }

        Entry withApplied(boolean value) {
            return new Entry(id, timeMs, op, target, previous, current,
                    restorable, unrestorableReason, restored, value,
                    pkg, namespace, key, detail);
        }

        Entry withRestored(boolean value) {
            return new Entry(id, timeMs, op, target, previous, current,
                    restorable, unrestorableReason, value, applied,
                    pkg, namespace, key, detail);
        }
    }

    private final File file;
    /** Chronological insertion order (oldest first). */
    private final List<Entry> entries = new ArrayList<>();
    private long nextId = 1;

    /** Load the journal from its file. A missing or unreadable
     *  file starts empty; pending entries from a dead process
     *  are dropped (see the class doc). */
    public ChangeJournal(File file) {
        this.file = file;
        load();
    }

    private void load() {
        entries.clear();
        String text = null;
        try {
            if (file != null && file.isFile()) {
                text = new String(Files.readAllBytes(file.toPath()),
                        StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // An unreadable journal starts empty; the audit
            // log still holds every attempt.
        }
        List<Entry> decoded = text == null ? new ArrayList<Entry>() : decode(text);
        long max = 0;
        for (Entry e : decoded) {
            if (e.id > max) max = e.id;
            if (e.applied) entries.add(e);
        }
        nextId = max + 1;
    }

    private void save() {
        if (file == null) return;
        try {
            Files.write(file.toPath(), encode(entries).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The journal is best-effort history; a failed
            // write must never break the control that ran.
        }
    }

    private void pruneLocked() {
        while (entries.size() > MAX_ENTRIES) {
            int drop = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).restored) {
                    drop = i;
                    break;
                }
            }
            if (drop < 0) drop = 0;
            entries.remove(drop);
        }
    }

    /**
     * Record a change BEFORE its write runs. The entry is
     * persisted immediately in the pending state; the caller
     * finalizes it with {@link #markApplied} on success or
     * {@link #drop} on failure/refusal. {@code previous} null
     * means the before-value could not be read.
     */
    public synchronized Entry record(String op, String target,
                                     String previous, String current,
                                     boolean restorable, String unrestorableReason,
                                     String pkg, String namespace, String key,
                                     String detail) {
        Entry e = new Entry(nextId++, System.currentTimeMillis(),
                op, target, previous, current, restorable,
                unrestorableReason, false, false,
                pkg, namespace, key, detail);
        entries.add(e);
        pruneLocked();
        save();
        return e;
    }

    /** The write succeeded: the entry is a real change now. */
    public synchronized void markApplied(long id) {
        replace(id, true, false);
    }

    /** The write failed or was refused: nothing changed, so
     *  the entry goes away entirely. */
    public synchronized void drop(long id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                entries.remove(i);
                save();
                return;
            }
        }
    }

    /** The entry's previous value was written back. */
    public synchronized void markRestored(long id) {
        replace(id, false, true);
    }

    private void replace(long id, boolean applied, boolean restored) {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.id == id) {
                Entry next = e;
                if (applied) next = next.withApplied(true);
                if (restored) next = next.withRestored(true);
                entries.set(i, next);
                save();
                return;
            }
        }
    }

    /** Mark every applied entry for one flag/setting restored:
     *  a reset-to-default returns the flag to its earliest
     *  recorded value, superseding every change to it. */
    public synchronized void markRestoredFor(String namespace, String key) {
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.applied && isFlagOp(e.op)
                    && equalsOrNull(e.namespace, namespace)
                    && equalsOrNull(e.key, key) && !e.restored) {
                entries.set(i, e.withRestored(true));
                changed = true;
            }
        }
        if (changed) save();
    }

    private static boolean equalsOrNull(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Applied entries, newest first — the history order. */
    public synchronized List<Entry> newestFirst() {
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.applied) out.add(e);
        }
        return out;
    }

    /** The restore-all replay order: unrestored, restorable,
     *  applied entries, newest first (reverse chronological,
     *  so replaying ends at the earliest recorded values). */
    public synchronized List<Entry> restorePlan() {
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e.applied && e.restorable && !e.restored) out.add(e);
        }
        return out;
    }

    /** The reset-all-flags replay order: the restore plan
     *  restricted to flag/setting ops. */
    public synchronized List<Entry> flagRestorePlan() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : restorePlan()) {
            if (isFlagOp(e.op)) out.add(e);
        }
        return out;
    }

    /** Whether an op counts as a flag/setting write. */
    public static boolean isFlagOp(String op) {
        return OP_DEVICE_CONFIG_PUT.equals(op)
                || OP_SETTING_PUT.equals(op)
                || OP_LAUNCHER_SETTING.equals(op);
    }

    /**
     * The recorded default for one flag/setting: the previous
     * value of the EARLIEST unrestored applied entry for it —
     * the value before the app first changed it in the current
     * epoch. Restored entries are skipped: once every change
     * to a flag has been restored, the flag is already at its
     * recorded default and there is nothing to reset. Null
     * when there is no unrestored entry, when the earliest
     * such entry's previous value is unknown, or when it is
     * unrestorable (a flag that was unset before has no
     * recorded default).
     */
    public synchronized String recordedDefault(String namespace, String key) {
        for (Entry e : entries) {
            if (!e.applied || e.restored || !isFlagOp(e.op)) continue;
            if (!equalsOrNull(e.namespace, namespace)) continue;
            if (!equalsOrNull(e.key, key)) continue;
            if (!e.restorable || e.previous == null) return null;
            return e.previous;
        }
        return null;
    }

    /** Applied entries for one flag/setting, oldest first. */
    public synchronized List<Entry> entriesFor(String namespace, String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (!e.applied || !isFlagOp(e.op)) continue;
            if (!equalsOrNull(e.namespace, namespace)) continue;
            if (!equalsOrNull(e.key, key)) continue;
            out.add(e);
        }
        return out;
    }

    public synchronized Entry byId(long id) {
        for (Entry e : entries) {
            if (e.id == id) return e;
        }
        return null;
    }

    public synchronized int appliedCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.applied) n++;
        }
        return n;
    }

    // ---- display: op labels, value words, row text ----

    /** Plain-words label for an op; unknown ops read as-is. */
    public static String opLabel(String op) {
        if (op == null) return "Change";
        switch (op) {
            case "SET_APP_ENABLED": return "App enabled / disabled";
            case "UNINSTALL_USER": return "App removed for user 0";
            case "SUSPEND_APP": return "App suspended";
            case "UNSUSPEND_APP": return "App unsuspended";
            case "SETTING_PUT": return "Setting changed";
            case "SET_LAUNCHER_SETTING": return "Setting changed";
            case "DEVICE_CONFIG_PUT": return "Flag changed";
            case "WM_SET_SIZE": return "Display size";
            case "WM_SET_DENSITY": return "Display density";
            case "WM_SET_OVERSCAN": return "Overscan";
            case "NIGHT_SET": return "Night mode";
            case "BATTERY_SET_LEVEL": return "Battery level simulated";
            case "BATTERY_UNPLUG": return "Battery unplug simulated";
            case "STANDBY_SET": return "Standby bucket";
            case "DOZE": return "Doze state forced";
            case "GRANT_PERMISSION": return "Permission granted";
            case "REVOKE_PERMISSION": return "Permission revoked";
            case "SET_APP_OP": return "AppOp changed";
            case "SET_PRIVATE_DNS": return "Private DNS";
            case "SET_BACKGROUND_DATA": return "Background data policy";
            case "SET_DEVICE_IDLE_WHITELIST": return "Doze exemption";
            case "CLEAR_APP_DATA": return "App data cleared";
            case "WIFI_FORGET": return "Wi-Fi network forgotten";
            case "USER_REMOVE": return "User removed";
            case "TRIM_CACHES": return "Caches trimmed";
            case "USER_CREATE": return "User created";
            case "INSTALL_APK": return "APK installed";
            default: return op;
        }
    }

    /**
     * Display words for a raw stored value. Settings use their
     * preset labels, night mode its display words, enable
     * state its plain words; everything else reads raw. Null
     * reads "unknown" — never a guessed value.
     */
    public static String formatValue(Entry e, String raw) {
        if (raw == null) return "unknown";
        if ("SET_APP_ENABLED".equals(e.op)) {
            return "true".equals(raw) ? "enabled" : "disabled";
        }
        if ("NIGHT_SET".equals(e.op)) {
            return BridgeOps.nightModeDisplay(raw);
        }
        if ((OP_SETTING_PUT.equals(e.op) || OP_LAUNCHER_SETTING.equals(e.op))
                && e.namespace != null && e.key != null) {
            BridgeOps.SettingDef def = BridgeOps.findSetting(e.namespace, e.key);
            if (def != null) return BridgeOps.settingDisplay(def, raw);
        }
        if ("SET_PRIVATE_DNS".equals(e.op)) {
            int bar = raw.indexOf('|');
            String mode = bar < 0 ? raw : raw.substring(0, bar);
            String host = bar < 0 ? "" : raw.substring(bar + 1);
            if ("hostname".equals(mode) && !host.isEmpty()) return "hostname (" + host + ")";
            if ("opportunistic".equals(mode)) return "Automatic";
            if ("off".equals(mode)) return "Off";
            return raw;
        }
        if (OP_DEVICE_CONFIG_PUT.equals(e.op) && raw.isEmpty()) {
            return "(cleared)";
        }
        return raw;
    }

    /** The history row text: what, was → now, when, and the
     *  state (restored / cannot be undone + reason). */
    public static String describe(Entry e) {
        StringBuilder out = new StringBuilder();
        out.append(opLabel(e.op)).append(" — ").append(e.target).append('\n');
        out.append("Was: ").append(formatValue(e, e.previous))
                .append(" → Now: ").append(formatValue(e, e.current));
        out.append("\n").append(formatTime(e.timeMs));
        if (e.restored) {
            out.append(" · Restored");
        } else if (!e.restorable) {
            out.append(" · Cannot be undone");
            if (e.unrestorableReason != null) {
                out.append(": ").append(e.unrestorableReason);
            }
        }
        return out.toString();
    }

    /** "Nov 16, 6:42 PM" — short, local, no seconds. */
    public static String formatTime(long timeMs) {
        try {
            return new SimpleDateFormat("MMM d, h:mm a", Locale.US)
                    .format(new Date(timeMs));
        } catch (Exception e) {
            return "";
        }
    }

    // ---- codec: JSON array of entry objects ----

    static String encode(List<Entry> list) {
        JSONArray array = new JSONArray();
        for (Entry e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("time", e.timeMs);
                o.put("op", e.op == null ? "" : e.op);
                o.put("target", e.target == null ? "" : e.target);
                putNullable(o, "previous", e.previous);
                putNullable(o, "current", e.current);
                o.put("restorable", e.restorable);
                putNullable(o, "reason", e.unrestorableReason);
                o.put("restored", e.restored);
                o.put("applied", e.applied);
                putNullable(o, "pkg", e.pkg);
                putNullable(o, "namespace", e.namespace);
                putNullable(o, "key", e.key);
                putNullable(o, "detail", e.detail);
                array.put(o);
            } catch (Exception ignored) {
                // One unencodable entry never blocks the rest.
            }
        }
        return array.toString();
    }

    private static void putNullable(JSONObject o, String name, String value) throws Exception {
        if (value == null) o.put(name, JSONObject.NULL);
        else o.put(name, value);
    }

    static List<Entry> decode(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null) return out;
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) continue;
                out.add(new Entry(
                        o.optLong("id", 0),
                        o.optLong("time", 0),
                        o.optString("op", ""),
                        o.optString("target", ""),
                        optNullable(o, "previous"),
                        optNullable(o, "current"),
                        o.optBoolean("restorable", false),
                        optNullable(o, "reason"),
                        o.optBoolean("restored", false),
                        o.optBoolean("applied", true),
                        optNullable(o, "pkg"),
                        optNullable(o, "namespace"),
                        optNullable(o, "key"),
                        optNullable(o, "detail")));
            }
        } catch (Exception ignored) {
            // A corrupt journal decodes to whatever parsed
            // before the failure — never a crash, never
            // invented entries.
        }
        return out;
    }

    private static String optNullable(JSONObject o, String name) {
        if (o.isNull(name)) return null;
        return o.optString(name, null);
    }
}
