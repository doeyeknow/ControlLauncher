package app.control.launcher;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Off-device verification for the v1.18 played → history rule,
 * exercising the exact shipping pure-Java code (HistoryRule,
 * UtteranceCallbacks):
 *
 * Legs:
 *   1. PLAYED FLAG: absent key reads unplayed (pre-v1.18 entries
 *      and new entries), explicit true/false honored, null-safe.
 *   2. LIST MEMBERSHIP: the full mine × played matrix — thread
 *      shows own + unplayed received, History shows played
 *      received only, and every entry shows in exactly one list.
 *   3. MARK TRANSITIONS: an unplayed received entry marks and
 *      changes state; own entries never mark; already-played
 *      entries are idempotent; null is safe; marking moves the
 *      entry between the lists (split before/after).
 *   4. SPLIT: partition is lossless and duplicate-free, order is
 *      preserved within each list, null/empty input is safe, and
 *      a mixed realistic thread splits exactly.
 *   5. UTTERANCE CALLBACKS: done fires exactly once; a second
 *      take returns nothing; error drops without firing; clear
 *      drops everything without firing; unknown ids and nulls are
 *      no-ops; re-registering an id replaces the callback.
 *
 * What this harness cannot prove: the TTS engine actually calling
 * onDone/onError on Adam's phone, burst play-through on real
 * audio hardware, persistence across a real restart, or the
 * History dialog in hand — all of that is Adam's on-device test.
 * These legs bound the pure-Java risk; they do not eliminate the
 * rest.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -cp harness/org-json.jar -d harness/classes \
 *     harness/HistoryHarness.java
 *   java -cp harness/classes:harness/org-json.jar \
 *     app.control.launcher.HistoryHarness
 */
public class HistoryHarness {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void eq(Object a, Object b, String name) {
        check(a == null ? b == null : a.equals(b),
                name + " (got " + a + ", want " + b + ")");
    }

    public static void main(String[] args) {
        flagLeg();
        membershipLeg();
        markLeg();
        splitLeg();
        utteranceLeg();
        System.out.println("HistoryHarness: " + checks + " checks, "
                + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    private static JSONObject entry(boolean mine, Boolean played) {
        JSONObject e = new JSONObject();
        try {
            e.put("kind", "text");
            e.put("mine", mine);
            if (played != null) e.put("played", played);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return e;
    }

    // ---- 1. played flag ----

    private static void flagLeg() {
        check(!HistoryRule.isPlayed(entry(false, null)),
                "absent key reads unplayed");
        check(!HistoryRule.isPlayed(entry(true, null)),
                "absent key reads unplayed (own)");
        check(HistoryRule.isPlayed(entry(false, true)),
                "explicit true reads played");
        check(!HistoryRule.isPlayed(entry(false, false)),
                "explicit false reads unplayed");
        check(!HistoryRule.isPlayed(null), "null entry reads unplayed");
        check(!HistoryRule.isMine(entry(false, null)), "received is not mine");
        check(HistoryRule.isMine(entry(true, null)), "own is mine");
        check(!HistoryRule.isMine(null), "null is not mine");
    }

    // ---- 2. list membership ----

    private static void membershipLeg() {
        // unplayed received → thread only
        JSONObject a = entry(false, null);
        check(HistoryRule.showsInThread(a), "unplayed received in thread");
        check(!HistoryRule.showsInHistory(a), "unplayed received not in history");
        // played received → history only
        JSONObject b = entry(false, true);
        check(!HistoryRule.showsInThread(b), "played received not in thread");
        check(HistoryRule.showsInHistory(b), "played received in history");
        // own unplayed → thread only
        JSONObject c = entry(true, null);
        check(HistoryRule.showsInThread(c), "own unplayed in thread");
        check(!HistoryRule.showsInHistory(c), "own unplayed not in history");
        // own played (should never happen) → thread only, never history
        JSONObject d = entry(true, true);
        check(HistoryRule.showsInThread(d), "own played still in thread");
        check(!HistoryRule.showsInHistory(d), "own played never in history");
        // null → thread (the safe default: never lost, never hidden)
        check(HistoryRule.showsInThread(null), "null defaults to thread");
        check(!HistoryRule.showsInHistory(null), "null never in history");
        // exactly one list, for every combination
        for (boolean mine : new boolean[]{false, true}) {
            for (Boolean played : new Boolean[]{null, false, true}) {
                JSONObject e = entry(mine, played);
                boolean inT = HistoryRule.showsInThread(e);
                boolean inH = HistoryRule.showsInHistory(e);
                check(inT ^ inH, "exactly one list mine=" + mine
                        + " played=" + played);
            }
        }
    }

    // ---- 3. mark transitions ----

    private static void markLeg() {
        // unplayed received marks and changes state
        JSONObject a = entry(false, null);
        check(HistoryRule.markPlayed(a), "mark unplayed received returns true");
        check(HistoryRule.isPlayed(a), "flag set after mark");
        check(HistoryRule.showsInHistory(a), "marked entry shows in history");
        // idempotent: second mark changes nothing
        check(!HistoryRule.markPlayed(a), "second mark returns false");
        check(HistoryRule.isPlayed(a), "still played after second mark");
        // already played: no-op
        JSONObject b = entry(false, true);
        check(!HistoryRule.markPlayed(b), "already-played mark returns false");
        // own entries never mark, flag untouched
        JSONObject c = entry(true, null);
        check(!HistoryRule.markPlayed(c), "own mark returns false");
        check(!c.has("played"), "own entry gains no played key");
        check(HistoryRule.showsInThread(c), "own entry stays in thread");
        JSONObject d = entry(true, false);
        check(!HistoryRule.markPlayed(d), "own explicit-false mark returns false");
        check(!HistoryRule.isPlayed(d), "own explicit-false stays unplayed");
        // null safe
        check(!HistoryRule.markPlayed(null), "null mark returns false");
        // marking moves the entry between the lists (split before/after)
        JSONObject e = entry(false, null);
        List<JSONObject> all = new ArrayList<>();
        all.add(e);
        List<JSONObject> thread = new ArrayList<>();
        List<JSONObject> history = new ArrayList<>();
        HistoryRule.split(all, thread, history);
        eq(thread.size(), 1, "before mark: in thread");
        eq(history.size(), 0, "before mark: not in history");
        HistoryRule.markPlayed(e);
        HistoryRule.split(all, thread, history);
        eq(thread.size(), 0, "after mark: left thread");
        eq(history.size(), 1, "after mark: in history");
    }

    // ---- 4. split ----

    private static void splitLeg() {
        List<JSONObject> all = new ArrayList<>();
        // a realistic mixed thread, in arrival order
        JSONObject ownText = entry(true, null);
        JSONObject recvUnplayed1 = entry(false, null);
        JSONObject recvPlayed = entry(false, true);
        JSONObject recvUnplayed2 = entry(false, false);
        JSONObject ownBurst = entry(true, null);
        JSONObject recvPlayed2 = entry(false, true);
        all.add(ownText);
        all.add(recvUnplayed1);
        all.add(recvPlayed);
        all.add(recvUnplayed2);
        all.add(ownBurst);
        all.add(recvPlayed2);
        List<JSONObject> thread = new ArrayList<>();
        List<JSONObject> history = new ArrayList<>();
        HistoryRule.split(all, thread, history);
        eq(thread.size(), 4, "split: thread size");
        eq(history.size(), 2, "split: history size");
        // lossless: every input in exactly one output, no duplicates
        Set<JSONObject> seen = new HashSet<>();
        for (JSONObject e : thread) check(seen.add(e), "split: no thread dup");
        for (JSONObject e : history) check(seen.add(e), "split: no cross-list dup");
        eq(seen.size(), all.size(), "split: lossless");
        // order preserved within each list
        check(thread.get(0) == ownText, "split: thread order 0");
        check(thread.get(1) == recvUnplayed1, "split: thread order 1");
        check(thread.get(2) == recvUnplayed2, "split: thread order 2");
        check(thread.get(3) == ownBurst, "split: thread order 3");
        check(history.get(0) == recvPlayed, "split: history order 0");
        check(history.get(1) == recvPlayed2, "split: history order 1");
        // split clears previous contents
        HistoryRule.split(new ArrayList<>(), thread, history);
        eq(thread.size(), 0, "split: empty clears thread");
        eq(history.size(), 0, "split: empty clears history");
        // null input is safe
        HistoryRule.split(null, thread, history);
        eq(thread.size(), 0, "split: null clears thread");
        eq(history.size(), 0, "split: null clears history");
    }

    // ---- 5. utterance callbacks ----

    private static void utteranceLeg() {
        UtteranceCallbacks cb = new UtteranceCallbacks();
        AtomicInteger runs = new AtomicInteger(0);
        Runnable r = runs::incrementAndGet;
        // done fires exactly once
        cb.add("u1", r);
        eq(cb.pendingCount(), 1, "callbacks: pending after add");
        Runnable taken = cb.take("u1");
        check(taken == r, "callbacks: take returns the callback");
        taken.run();
        eq(runs.get(), 1, "callbacks: done runs once");
        check(cb.take("u1") == null, "callbacks: second take is null");
        eq(cb.pendingCount(), 0, "callbacks: empty after take");
        // error drops without firing
        cb.add("u2", r);
        cb.drop("u2");
        eq(runs.get(), 1, "callbacks: error never runs");
        eq(cb.pendingCount(), 0, "callbacks: empty after drop");
        check(cb.take("u2") == null, "callbacks: dropped take is null");
        // clear drops everything without firing
        cb.add("u3", r);
        cb.add("u4", r);
        eq(cb.pendingCount(), 2, "callbacks: two pending");
        cb.clear();
        eq(cb.pendingCount(), 0, "callbacks: clear empties");
        eq(runs.get(), 1, "callbacks: clear never runs");
        check(cb.take("u3") == null, "callbacks: cleared take is null");
        // unknown ids are no-ops
        check(cb.take("nope") == null, "callbacks: unknown take is null");
        cb.drop("nope");
        eq(cb.pendingCount(), 0, "callbacks: unknown drop is a no-op");
        // nulls are no-ops
        cb.add(null, r);
        cb.add("u5", null);
        eq(cb.pendingCount(), 0, "callbacks: null add is a no-op");
        check(cb.take(null) == null, "callbacks: null take is null");
        cb.drop(null);
        // re-registering replaces
        AtomicInteger runs2 = new AtomicInteger(0);
        cb.add("u6", r);
        cb.add("u6", runs2::incrementAndGet);
        eq(cb.pendingCount(), 1, "callbacks: re-register keeps one");
        Runnable taken2 = cb.take("u6");
        taken2.run();
        eq(runs.get(), 1, "callbacks: replaced callback never runs");
        eq(runs2.get(), 1, "callbacks: replacement runs");
    }
}
