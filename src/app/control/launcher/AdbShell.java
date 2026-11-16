package app.control.launcher;

/**
 * Pure-Java helpers for the built-in terminal (v1.6) — no Android
 * types, so the off-device harness exercises the exact shipping
 * code.
 *
 * The terminal runs commands over the ADB shell channel (the same
 * channel a computer's {@code adb shell} uses), which means a typed
 * command runs as Android's shell user. Two honesty rules live
 * here:
 *
 *  1. TOKEN REDACTION. The bridge starter command carries Control's
 *     private bridge token. The token value must NEVER appear in
 *     the on-screen transcript or the shared audit log — not in the
 *     preset's echo, not in a typed command, and not in command
 *     output (a command could echo it back). Every string bound for
 *     either surface passes through {@link #redact}. The rest of a
 *     command is shown verbatim; only the token itself is masked.
 *  2. HISTORY HYGIENE. A command that contains the token is never
 *     recorded in history ({@link #shouldRecord}), so history can
 *     neither leak the token nor offer a broken re-run of a
 *     redacted starter line. The "Enable deep view" preset is the
 *     way to re-run the starter.
 *
 * What this class deliberately does NOT do: report exit codes. The
 * ADB legacy {@code shell:} service has no exit-status channel —
 * output simply ends with CLSE — so the terminal never claims one.
 * {@link AdbClient.Result} carries no exit status, and neither
 * does anything built on it.
 */
public final class AdbShell {

    /** The mask shown wherever the token value would appear. */
    public static final String TOKEN_MASK = "<bridge token>";

    private AdbShell() {}

    /**
     * Returns {@code text} with every occurrence of the bridge token
     * replaced by {@link #TOKEN_MASK}. Null-safe; a null, empty, or
     * implausibly short token redacts nothing (a short "token"
     * would mask innocent substrings — the real token is 48 hex
     * chars; anything under 8 chars is not treated as one).
     */
    public static String redact(String text, String token) {
        if (text == null) return "";
        if (token == null || token.length() < 8) return text;
        if (text.indexOf(token) < 0) return text;
        return text.replace(token, TOKEN_MASK);
    }

    /**
     * The transcript echo for a command about to run: a {@code $}
     * prompt plus the command, token-redacted.
     */
    public static String echoLine(String command, String token) {
        return "$ " + redact(command, token);
    }

    /**
     * Whether a typed command may be recorded in history. A command
     * containing the token value is never recorded (see the class
     * doc); blank commands are not recorded either.
     */
    public static boolean shouldRecord(String command, String token) {
        if (command == null || command.trim().isEmpty()) return false;
        if (token != null && token.length() >= 8 && command.contains(token)) return false;
        return true;
    }
}
