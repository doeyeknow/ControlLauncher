package app.control.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The dictionary write-back rules (v1.30): which learned words
 * this phone proposes to the shared dictionary, and when.
 *
 * <p>POLICY: a word is proposed once, ever, from this phone —
 * the submitted set is persisted, so a restart never
 * re-proposes it. Proposals go out in first-learned order, at
 * most one batch per hour, and only when unsubmitted words
 * exist. A batch holds at most MAX_BATCH words (the server's
 * per-request cap). Failures are silent and retried at the
 * next window; nothing here ever blocks sending or any UI.</p>
 *
 * <p>SHAPE: the server accepts only 2..30 chars, lowercase
 * a-z, with at most one apostrophe strictly inside. Learned
 * words that do not fit that shape (long words, accented
 * letters, multi-apostrophe forms) are never proposed — they
 * stay local-only, and the filter runs again every cycle, so
 * no bookkeeping is needed for them.</p>
 *
 * <p>Pure Java, no Android types: the harness proves the
 * batching, dedupe, throttle, and body-building rules with
 * the exact shipping code. The transport itself is
 * RelayCore.proposeWords, over the same signed-request path
 * as every other relay call.</p>
 */
public final class DictSubmit {

    /** The server's per-request cap (PROTOCOL.md §7.5). */
    public static final int MAX_BATCH = 100;

    /** At most one submit attempt per hour. */
    public static final long SUBMIT_INTERVAL_MS = 60L * 60L * 1000L;

    private DictSubmit() {}

    /** The server's word shape: 2..30 chars, lowercase a-z,
     *  at most one apostrophe strictly inside the word. */
    public static boolean submittable(String word) {
        if (word == null) return false;
        int n = word.length();
        if (n < 2 || n > 30) return false;
        int apos = -1;
        for (int i = 0; i < n; i++) {
            char c = word.charAt(i);
            if (c == '\'') {
                if (apos >= 0) return false;
                apos = i;
            } else if (c < 'a' || c > 'z') {
                return false;
            }
        }
        // An apostrophe must have a letter on both sides.
        if (apos >= 0 && (apos == 0 || apos == n - 1)) return false;
        return true;
    }

    /** One submit decision: whether a batch is due, and the
     *  words it would carry. */
    public static final class Plan {
        /** True when the throttle allows an attempt AND the
         *  batch is non-empty. */
        public final boolean due;
        /** Unsubmitted, submittable words in first-learned
         *  order, capped at maxBatch. Empty when not due. */
        public final List<String> batch;

        Plan(boolean due, List<String> batch) {
            this.due = due;
            this.batch = batch;
        }
    }

    /**
     * Decides the next submit. {@code learnedByIndex} is the
     * learned words in first-learned order;
     * {@code submitted} is every word this phone already
     * proposed; {@code lastSubmitMs} is the last ATTEMPT
     * (success or failure), 0 when none. A batch is due
     * only when the hour has passed and at least one word
     * qualifies. The batch never repeats a submitted word
     * and never exceeds maxBatch (clamped to 1..MAX_BATCH).
     */
    public static Plan plan(List<String> learnedByIndex, Set<String> submitted,
                            long nowMs, long lastSubmitMs, int maxBatch) {
        int cap = Math.max(1, Math.min(MAX_BATCH, maxBatch));
        List<String> batch = new ArrayList<>();
        if (learnedByIndex != null) {
            for (String w : learnedByIndex) {
                if (batch.size() >= cap) break;
                if (w == null || !submittable(w)) continue;
                if (submitted != null && submitted.contains(w)) continue;
                if (batch.contains(w)) continue;
                batch.add(w);
            }
        }
        boolean due = !batch.isEmpty()
                && (lastSubmitMs <= 0 || nowMs - lastSubmitMs >= SUBMIT_INTERVAL_MS);
        return new Plan(due, due ? batch : new ArrayList<String>());
    }

    /** The POST /v1/dict/proposals body for a batch:
     *  {"words":["a","b"]}. Words are already validated, but
     *  the escaper runs anyway — a batch must never break
     *  the JSON it travels in. */
    public static String proposalBody(List<String> words) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"words\":[");
        boolean first = true;
        if (words != null) {
            for (String w : words) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                for (int i = 0; i < w.length(); i++) {
                    char c = w.charAt(i);
                    if (c == '"' || c == '\\') sb.append('\\');
                    sb.append(c);
                }
                sb.append('"');
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
