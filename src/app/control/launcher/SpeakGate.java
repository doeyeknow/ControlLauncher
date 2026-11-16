package app.control.launcher;

/**
 * The single decision point for reading a walkie message aloud.
 * The rule is absolute on one axis: QUIET-all silences speech in
 * every case — automatic reads, manual replays, previews of other
 * people's messages — with no exception path. Everything else is
 * a preference:
 *
 *  - automatic reads happen for every received walkie text, when
 *    the read-aloud switch is on (default ON). Since v1.17 the
 *    low-data marker no longer gates speech: a typed text is read
 *    aloud exactly like a low-data one (the marker still labels
 *    the thread entry — it just isn't a speech condition anymore);
 *  - a manual replay (tapping a received text entry, or the
 *    Preview button in a voice profile) bypasses the read-aloud
 *    switch but never QUIET.
 *
 * Pure Java so the harness can prove the whole truth table with
 * the exact shipping code; the Android side supplies the live
 * values (Prefs.KEY_QUIET_ALL, the read-aloud pref) at speak time,
 * never cached, so toggling QUIET takes effect immediately.
 */
public final class SpeakGate {
    private SpeakGate() {}

    public enum Decision {
        SPEAK,
        /** QUIET-all is on — never speak, no exception. */
        SKIP_QUIET,
        /** Read-aloud is off and this wasn't a manual replay. */
        SKIP_MUTED
    }

    /**
     * @param autoReadOn  the "read walkie messages aloud" switch
     * @param quietAll    QUIET-all (Prefs.KEY_QUIET_ALL), read live
     * @param manual      the user explicitly asked to hear this one
     */
    public static Decision decide(boolean autoReadOn, boolean quietAll,
                                  boolean manual) {
        if (quietAll) return Decision.SKIP_QUIET;
        if (manual) return Decision.SPEAK;
        if (!autoReadOn) return Decision.SKIP_MUTED;
        return Decision.SPEAK;
    }
}
