package app.control.launcher;

import java.util.Locale;

/**
 * The package-disable fix (v1.33), as pure Java so the harness
 * exercises the exact shipping code.
 *
 * Why this exists: the bridge's SET_APP_ENABLED op ran plain
 * {@code pm disable <pkg>} — the SYSTEM-level disable, which
 * Android reserves for system/signature holders behind the
 * CHANGE_COMPONENT_ENABLED_STATE gate. The shell user (UID 2000)
 * is denied, so every disable failed — and the op reported
 * success anyway, because it never checked pm's output. Two
 * bugs, fixed together:
 *
 * 1. Disable now runs {@code pm disable-user --user 0 <pkg>},
 *    the per-user variant the shell user IS allowed. Same
 *    visible effect: the app cannot run and is hidden.
 * 2. pm's output is verified before anything reports success.
 *    pm prints "Package &lt;pkg&gt; new state: disabled-user"
 *    (disable) or "new state: enabled" (enable) on success, and
 *    an Error/Exception line on refusal. Anything else —
 *    including empty output — is reported as "not confirmed",
 *    never as success.
 *
 * Enable keeps plain {@code pm enable <pkg>}: it clears the
 * user-disabled state (the "new state: enabled" line confirms
 * it), and it is the documented counterpart of disable-user.
 */
public final class PmOutcome {

    /** Verified outcome of one pm disable/enable run. */
    public static final class Result {
        /** True only when pm's own output confirmed the state. */
        public final boolean ok;
        /** On success: pm's confirming line. On failure: the
         *  honest reason, including pm's own words when it
         *  printed any. */
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private static final int MAX_ECHO_CHARS = 300;

    private PmOutcome() {}

    /** The exact argv for disabling a package as the shell user. */
    public static String[] disableCommand(String pkg) {
        return new String[]{"/system/bin/pm", "disable-user", "--user", "0", pkg};
    }

    /** The exact argv for re-enabling a package. */
    public static String[] enableCommand(String pkg) {
        return new String[]{"/system/bin/pm", "enable", pkg};
    }

    /**
     * Verify pm's combined stdout/stderr for one disable/enable
     * run. Success requires BOTH: no error marker anywhere in
     * the output, AND the expected "new state:" line. An error
     * marker wins over a state line (pm never prints both on a
     * clean run, so both together means something went wrong).
     * Empty, null, or unrecognized output is a failure — the
     * caller must never report success it cannot prove.
     */
    public static Result verify(String pmOutput, boolean enabling) {
        String out = pmOutput == null ? "" : pmOutput.trim();
        String low = out.toLowerCase(Locale.ROOT);
        boolean errorMarker = low.contains("error") || low.contains("exception")
                || low.contains("unknown package") || low.contains("not installed");
        String want = enabling ? "new state: enabled" : "new state: disabled";
        if (!errorMarker && low.contains(want)) {
            return new Result(true, firstLine(out));
        }
        String action = enabling ? "enable" : "disable";
        if (out.isEmpty()) {
            return new Result(false, "Android did not confirm the " + action
                    + " — pm printed nothing.");
        }
        return new Result(false, "Android did not confirm the " + action + ": "
                + cap(out, MAX_ECHO_CHARS));
    }

    private static String firstLine(String out) {
        int nl = out.indexOf('\n');
        return cap(nl < 0 ? out : out.substring(0, nl), MAX_ECHO_CHARS);
    }

    private static String cap(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }
}
