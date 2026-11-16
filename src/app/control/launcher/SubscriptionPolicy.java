package app.control.launcher;

import java.util.Collection;
import java.util.TreeSet;

/**
 * The channel-subscription rules (v1.19), in one pure-Java place
 * so the harness can prove them with the exact shipping code.
 *
 * A subscription means: this phone keeps listening to a walkie
 * channel over the internet relay even when it is tuned
 * elsewhere, and messages on that channel arrive, land in the
 * channel's thread, and are read aloud exactly as if the phone
 * were tuned there. Subscriptions are relay-only by Adam's
 * decision (2026-11-15): the mesh and Bluetooth roads stay
 * single-channel, tuned-channel-only, and are untouched.
 *
 * The rules:
 *
 *  - the subscription set is slot NUMBERS (1..50), not channel
 *    identities: subscribing follows the number, so a slot that
 *    is reconfigured (new key, new label) stays subscribed under
 *    its new relay id, and the per-channel cursor follows the
 *    relay id, not the subscription;
 *  - polling is round-robin, one request per step: the spacing
 *    between requests is max(1s, 4s / n), so one subscription
 *    is polled every 4s (the tuned channel's cadence), and the
 *    request rate NEVER exceeds 60/minute — a 10x margin under
 *    the relay's 600/minute limit, whatever the subscription
 *    count. More subscriptions means each channel is checked
 *    less often; that tradeoff is stated in the UI copy;
 *  - offline waits quietly: consecutive all-failed cycles back
 *    off 15s → 30s → 60s (capped), with no error spam and no
 *    toasts. Cursors persist, so reconnecting resumes without
 *    message loss inside the relay's 30-day retention;
 *  - a channel the relay refuses (not_a_member, channel_not_
 *    found) is skipped for 10 cycles, then retried — membership
 *    can land after subscribing (an invite, a member-add), and
 *    the retry is how the subscription starts working without
 *    the user toggling it. The UI names the blocker while the
 *    channel is refused;
 *  - a rate-limit refusal (429) pauses the whole poller for a
 *    minute: the schedule above should make it unreachable, but
 *    the backstop is here, not in a tight retry loop.
 */
public final class SubscriptionPolicy {
    private SubscriptionPolicy() {}

    /** Slowest spacing between two poll requests. */
    public static final int MIN_SPACING_MS = 1000;
    /** The tuned channel's poll cadence, and the per-channel
     *  revisit period while subscriptions are few. */
    public static final int BASE_PERIOD_MS = 4000;
    /** The relay's rate limit (PROTOCOL §9), per key. */
    public static final int RATE_LIMIT_PER_MIN = 600;
    /** Cycles a refused channel is skipped before a retry. */
    public static final int BLOCKED_SKIP_CYCLES = 10;
    /** Longest offline backoff between poll attempts. */
    public static final int MAX_OFFLINE_BACKOFF_MS = 60_000;
    /** Pause after a rate-limit refusal. */
    public static final int RATE_LIMIT_PAUSE_MS = 60_000;
    /** Slot numbers run 1..50. This mirrors Profile.BANK_SIZE
     *  (kept as a literal here so this class stays pure Java
     *  and harness-compilable without android.jar; the two
     *  must stay equal — the harness pins this value). */
    public static final int MAX_SLOT = 50;

    /** The per-channel state the subscription UI can name.
     *  OFFLINE means the last attempt failed on transport (the
     *  phone may simply be offline); UNKNOWN means no attempt
     *  has reported yet. */
    public enum ChannelState {
        OK,
        BLOCKED_NOT_MEMBER,
        BLOCKED_NOT_FOUND,
        RATE_LIMITED,
        OFFLINE,
        UNKNOWN
    }

    // ---- the subscription set ----

    /** Normalizes a subscription set: slot numbers only, in
     *  range 1..50, deduplicated, sorted. Null input and null
     *  members are safe; out-of-range numbers are dropped. */
    public static TreeSet<Integer> normalizeSet(Collection<Integer> raw) {
        TreeSet<Integer> out = new TreeSet<>();
        if (raw == null) return out;
        for (Integer n : raw) {
            if (n == null) continue;
            if (n >= 1 && n <= MAX_SLOT) out.add(n);
        }
        return out;
    }

    /** The set after toggling one slot: a new sorted set, the
     *  input untouched. Out-of-range numbers never enter. */
    public static TreeSet<Integer> toggle(Collection<Integer> current,
                                          int number, boolean on) {
        TreeSet<Integer> out = normalizeSet(current);
        if (number < 1 || number > MAX_SLOT) return out;
        if (on) out.add(number);
        else out.remove(number);
        return out;
    }

    // ---- the poll schedule ----

    /** Milliseconds between two poll requests for n
     *  subscriptions: max(1s, ceil(4s / n)). The ceiling
     *  division matters: with plain division, n=3 would give
     *  a 3999ms revisit period, under the 4s floor. Zero or
     *  negative means idle — the base period, so a caller
     *  that polls anyway still cannot hammer the relay. */
    public static long requestSpacingMs(int subscriptionCount) {
        if (subscriptionCount <= 0) return BASE_PERIOD_MS;
        long spacing = (BASE_PERIOD_MS + subscriptionCount - 1L) / subscriptionCount;
        return Math.max(MIN_SPACING_MS, spacing);
    }

    /** Requests per minute at the scheduled spacing — always
     *  at most 60, a 10x margin under the relay limit. */
    public static int requestsPerMinute(int subscriptionCount) {
        long spacing = requestSpacingMs(subscriptionCount);
        return (int) (60_000L / spacing);
    }

    /** How often one subscribed channel is revisited: the
     *  spacing times the number of channels in the rotation. */
    public static long channelPeriodMs(int subscriptionCount) {
        if (subscriptionCount <= 0) return BASE_PERIOD_MS;
        return requestSpacingMs(subscriptionCount) * subscriptionCount;
    }

    // ---- failure handling ----

    /** Wait before the next poll attempt after consecutive
     *  all-failed (offline) cycles: 15s, 30s, then 60s capped.
     *  Zero or negative failures means "no backoff" — the
     *  normal spacing applies. */
    public static long offlineBackoffMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0;
        if (consecutiveFailures == 1) return 15_000L;
        if (consecutiveFailures == 2) return 30_000L;
        return MAX_OFFLINE_BACKOFF_MS;
    }

    /** True when a refused channel has been skipped long
     *  enough to deserve a retry. */
    public static boolean shouldRetryBlocked(int cyclesSkipped) {
        return cyclesSkipped >= BLOCKED_SKIP_CYCLES;
    }

    /** Maps a relay error code (or a transport failure) to the
     *  channel state the UI names. Unknown codes and nulls are
     *  OFFLINE — the honest default, since most of them are
     *  transport failures, and none of them is a membership
     *  verdict. */
    public static ChannelState classifyError(String code) {
        if ("not_a_member".equals(code)) return ChannelState.BLOCKED_NOT_MEMBER;
        if ("channel_not_found".equals(code)) return ChannelState.BLOCKED_NOT_FOUND;
        if ("rate_limited".equals(code)) return ChannelState.RATE_LIMITED;
        return ChannelState.OFFLINE;
    }
}
