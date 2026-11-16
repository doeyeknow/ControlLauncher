package app.control.launcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The completion bookkeeping for TextToSpeech utterances (v1.18).
 *
 * The speaker registers one callback per utterance id when the
 * utterance is spoken. The engine's progress listener resolves
 * it: onDone TAKES the callback (it runs exactly once), onError
 * DROPS it (a failed utterance never counts as heard), and
 * stop()/shutdown() CLEAR all pending callbacks without running
 * any (an interrupted utterance never counts as heard).
 *
 * Pure Java so the harness can prove the exactly-once / never-on-
 * error / never-on-interrupt semantics with the exact shipping
 * code. The map is concurrent because the progress callbacks
 * arrive on binder threads while registration happens on the
 * calling thread.
 */
public final class UtteranceCallbacks {
    private final Map<String, Runnable> pending = new ConcurrentHashMap<>();

    /** Registers the callback for an utterance id. A null
     *  callback registers nothing. */
    public void add(String utteranceId, Runnable onDone) {
        if (utteranceId == null || onDone == null) return;
        pending.put(utteranceId, onDone);
    }

    /**
     * Resolves an utterance that finished: removes and returns
     * its callback, or null when none was registered (or it was
     * already resolved). The caller runs the returned callback.
     */
    public Runnable take(String utteranceId) {
        if (utteranceId == null) return null;
        return pending.remove(utteranceId);
    }

    /** Resolves an utterance that failed: removes its callback
     *  without returning it — it must never run. */
    public void drop(String utteranceId) {
        if (utteranceId == null) return;
        pending.remove(utteranceId);
    }

    /** Drops every pending callback without running any — the
     *  stop()/shutdown() path. Interrupted utterances are not
     *  heard. */
    public void clear() {
        pending.clear();
    }

    /** Pending callback count (harness / diagnostics only). */
    public int pendingCount() {
        return pending.size();
    }
}
