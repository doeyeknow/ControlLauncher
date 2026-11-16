package app.control.launcher;

import org.json.JSONObject;

import java.util.List;

/**
 * The played → history rule for walkie channel entries (v1.18).
 *
 * A received entry lives in the channel thread while it is
 * UNPLAYED. Once it has been PLAYED — its read-aloud ran to
 * completion, or its burst played through — it leaves the thread
 * and appears in that channel's History list instead. The move is
 * a state change on the entry itself (a "played" flag, persisted
 * with the entry by WalkieStore), never a delete + recreate, so a
 * played entry can neither appear in both lists nor be lost in
 * the move.
 *
 * The rules, all in one place so the harness can prove them with
 * the exact shipping code:
 *
 *  - an entry with no "played" key is unplayed (every entry
 *    written before v1.18, and every new entry, starts unplayed);
 *  - only a RECEIVED entry can move: own ("mine") entries stay in
 *    the thread forever — the user never "receives" them, so
 *    playback never marks them;
 *  - only a COMPLETED playback marks an entry: QUIET-blocked,
 *    switch-off, engine-less, failed, or interrupted playbacks
 *    never call markPlayed, and the entry stays in the thread —
 *    the honest consequence, since it hasn't been heard;
 *  - marking is idempotent: an already-played entry stays played,
 *    and replaying from History moves nothing.
 *
 * Pure Java over org.json (the store's own entry shape) — no
 * Android types — so the harness proves the split and the
 * transitions with the exact shipping code.
 */
public final class HistoryRule {
    private HistoryRule() {}

    /** True when the entry has been played (flag present and true).
     *  Absent means unplayed. */
    public static boolean isPlayed(JSONObject e) {
        return e != null && e.optBoolean("played", false);
    }

    public static boolean isMine(JSONObject e) {
        return e != null && e.optBoolean("mine", false);
    }

    /** The channel thread shows own entries plus unplayed
     *  received entries. */
    public static boolean showsInThread(JSONObject e) {
        return isMine(e) || !isPlayed(e);
    }

    /** History shows played received entries only. */
    public static boolean showsInHistory(JSONObject e) {
        return !isMine(e) && isPlayed(e);
    }

    /**
     * Marks an entry played after a completed playback. Returns
     * true when the entry CHANGED state (it was an unplayed
     * received entry); false for own entries and already-played
     * entries, which are left untouched. Callers persist after a
     * true return (WalkieStore.saveNow) and move the entry
     * between the in-memory lists.
     */
    public static boolean markPlayed(JSONObject e) {
        if (e == null || isMine(e) || isPlayed(e)) return false;
        try {
            e.put("played", true);
        } catch (Exception ignored) {
            return false;
        }
        return true;
    }

    /**
     * Partitions every entry into exactly one list: thread or
     * history. Nothing is dropped, nothing is duplicated. The
     * input order is preserved within each list.
     */
    public static void split(List<JSONObject> all,
                             List<JSONObject> thread,
                             List<JSONObject> history) {
        thread.clear();
        history.clear();
        if (all == null) return;
        for (JSONObject e : all) {
            if (showsInHistory(e)) history.add(e);
            else thread.add(e);
        }
    }
}
