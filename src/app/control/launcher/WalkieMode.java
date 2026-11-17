package app.control.launcher;

/**
 * The walkie-talkie transmission mode decision (v1.23).
 *
 * "Mode" is what the hold-to-talk button sends: VOICE bursts
 * (IMA-ADPCM audio, the original behavior) or TEXT (low-data:
 * on-device transcription, checked before sending, read aloud
 * on the other phone). Adam's order: the mode should follow
 * the connection — automatically set by testing the network
 * speed — with a manual override always available.
 *
 * The choice is three-way: AUTO, VOICE, or TEXT. Auto is the
 * DEFAULT (v1.24): a phone with no stored choice measures the
 * connection and picks the mode. A stored choice is respected
 * as the user's explicit pick — manual Voice/Text always
 * wins, immediately. There is no migration from the old
 * low-data switch: v1.23's one-time write is gone, and
 * nothing ever writes an implicit choice.
 *
 * The speed test is a timed round trip to the Control Relay
 * (see RelayCore.probeRoundTrip): one signed GET to the
 * read-only channel list, 5-second bound, no user data, nothing
 * posted. What it measures is round-trip time, and the face
 * says so in milliseconds — a throughput figure would need
 * real bytes moved through the relay, which would either
 * pollute a channel or measure the wrong path. Round-trip time
 * is the honest signal for this decision: live voice needs a
 * responsive link, and a link slow enough to make voice
 * unusable shows it in the round trip first.
 *
 * Thresholds (hysteresis, so the mode never flaps on a
 * borderline link): at or under {@link #VOICE_MAX_RTT_MS} the
 * link is fast enough for voice; at or over {@link
 * #TEXT_MIN_RTT_MS} it is slow enough that text wins; between
 * the two, the previous decision stands. Precedence, highest
 * first: the manual choice; a live local session (mesh, LAN,
 * or Bluetooth — a nearby link carries bursts fine, and no
 * probe is needed or run); the probe; the last decision.
 * A failed or missing probe never flips the mode: the last
 * decision stands and the face says the probe found nothing.
 *
 * Pure Java — no Android imports — so the harness exercises
 * the exact shipping decision.
 */
public final class WalkieMode {

    /** The user's choice: automatic, or a manual override. */
    public enum Choice { AUTO, VOICE, TEXT }

    /** What the talk button sends. */
    public enum Mode { VOICE, TEXT }

    /** What the last probe established. */
    public enum Probe { NONE, OK, FAILED }

    /** At or under this round trip, the link carries voice. */
    public static final long VOICE_MAX_RTT_MS = 400;

    /** At or over this round trip, text wins. Between the two
     *  thresholds the previous decision stands (hysteresis). */
    public static final long TEXT_MIN_RTT_MS = 1200;

    /** How often the idle re-test runs while the walkie page is
     *  shown: once a minute. Bounded, and never during a
     *  transmission (the page gates it on recording state). */
    public static final long PROBE_INTERVAL_MS = 60_000;

    /** Stored forms of the choice (Prefs.KEY_WALKIE_MODE). */
    public static final String STORE_AUTO = "auto";
    public static final String STORE_VOICE = "voice";
    public static final String STORE_TEXT = "text";

    private WalkieMode() {}

    /** Parses the stored choice. Anything unrecognized — null
     *  (no stored choice: a fresh install, or an upgrade
     *  where the user never picked), empty, a value from
     *  another version — is AUTO, the v1.24 default. A stored
     *  VOICE or TEXT is the user's explicit choice and is
     *  respected exactly. */
    public static Choice parseChoice(String stored) {
        if (STORE_VOICE.equals(stored)) return Choice.VOICE;
        if (STORE_TEXT.equals(stored)) return Choice.TEXT;
        return Choice.AUTO;
    }

    public static String storeChoice(Choice choice) {
        switch (choice) {
            case AUTO: return STORE_AUTO;
            case TEXT: return STORE_TEXT;
            case VOICE:
            default: return STORE_VOICE;
        }
    }

    /**
     * The effective mode. Precedence: manual choice; live local
     * session; probe with hysteresis; previous decision. A null
     * previous decision means "no decision yet" and reads as
     * VOICE, the pre-v1.23 behavior.
     */
    public static Mode decide(Choice choice, Mode previous,
                              boolean localSessionLive,
                              Probe probe, long rttMs) {
        Mode prev = previous == null ? Mode.VOICE : previous;
        if (choice == Choice.VOICE) return Mode.VOICE;
        if (choice == Choice.TEXT) return Mode.TEXT;
        // AUTO from here.
        if (localSessionLive) return Mode.VOICE;
        if (probe != Probe.OK) return prev;
        if (rttMs <= VOICE_MAX_RTT_MS) return Mode.VOICE;
        if (rttMs >= TEXT_MIN_RTT_MS) return Mode.TEXT;
        return prev;
    }

    /**
     * Signal bars for the face, from the same measurement: 3
     * for a fast link (or a live local session), 2 in the
     * hysteresis band, 1 for a slow link, 0 when the probe
     * failed (unreachable), -1 when no probe has run yet.
     * The bars describe the measured round trip, nothing more.
     */
    public static int signalBars(Probe probe, long rttMs,
                                 boolean localSessionLive) {
        if (localSessionLive) return 3;
        switch (probe) {
            case OK:
                if (rttMs <= VOICE_MAX_RTT_MS) return 3;
                if (rttMs >= TEXT_MIN_RTT_MS) return 1;
                return 2;
            case FAILED:
                return 0;
            case NONE:
            default:
                return -1;
        }
    }

    /** The bars as text for the face: full, partial, none, or
     *  the "not measured yet" dots. */
    public static String signalText(Probe probe, long rttMs,
                                    boolean localSessionLive) {
        switch (signalBars(probe, rttMs, localSessionLive)) {
            case 3: return "▂▄▆";
            case 2: return "▂▄";
            case 1: return "▂";
            case 0: return "✕";
            default: return "···";
        }
    }

    /**
     * The mode line for the radio face, honestly worded: the
     * choice, what the probe measured (or that it measured
     * nothing), and the mode in force. When the mode is TEXT
     * but the speech model is missing, the line says so —
     * hold-to-talk is paused until the model is downloaded.
     */
    public static String describe(Choice choice, Mode mode,
                                  Probe probe, long rttMs,
                                  boolean localSessionLive,
                                  boolean modelMissing) {
        StringBuilder sb = new StringBuilder();
        switch (choice) {
            case VOICE:
                sb.append("Voice");
                break;
            case TEXT:
                sb.append("Text");
                break;
            case AUTO:
            default:
                sb.append("Auto · ");
                if (localSessionLive) {
                    sb.append("nearby link");
                } else if (probe == Probe.OK) {
                    sb.append(rttMs).append(" ms");
                } else if (probe == Probe.FAILED) {
                    sb.append("unreachable");
                } else {
                    sb.append("no probe yet");
                }
                sb.append(" · ").append(mode == Mode.TEXT ? "Text" : "Voice");
                break;
        }
        if (mode == Mode.TEXT && modelMissing) {
            sb.append(" · model not downloaded");
        }
        return sb.toString();
    }
}
