package app.control.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android-side relay access: builds a {@link RelayCore} from the
 * profile identity, runs calls on a background pool, and delivers
 * results on the main thread (the pattern the old Matrix client
 * established).
 * Also owns the per-channel pull cursors (persisted in prefs as
 * "relay_seq:<channelId>") and the register-once-per-start bookkeeping.
 */
public final class RelayClient {

    public interface Cb<T> {
        void ok(T value);
        /** code = the protocol error code ("not_a_member", …) or
         *  "network" for a transport failure. */
        void err(String code, String message);
    }

    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private RelayClient() {}

    public static RelayCore core(Context c) {
        return new RelayCore(Profile.relaySeedBytes(c));
    }

    public static String myPub(Context c) {
        return Profile.relayPub(c);
    }

    // ---- cursors ----

    public static long cursor(Context c, String channelId) {
        return Prefs.get(c).getLong("relay_seq:" + channelId, 0);
    }

    public static void setCursor(Context c, String channelId, long seq) {
        Prefs.get(c).edit().putLong("relay_seq:" + channelId, seq).apply();
    }

    // ---- registration ----

    /** Idempotent (§4) and throttled: safe to call on every screen. */
    public static void ensureRegistered(final Context c) {
        long last = Prefs.get(c).getLong(Prefs.KEY_RELAY_REG_ATTEMPT, 0);
        boolean done = Prefs.get(c).getBoolean(Prefs.KEY_RELAY_REGISTERED, false);
        if (done || System.currentTimeMillis() - last < 60_000) return;
        register(c, new Cb<Boolean>() {
            @Override public void ok(Boolean created) { }
            @Override public void err(String code, String message) { }
        });
    }

    public static void register(final Context c, final Cb<Boolean> cb) {
        Prefs.get(c).edit()
                .putLong(Prefs.KEY_RELAY_REG_ATTEMPT, System.currentTimeMillis())
                .apply();
        POOL.execute(() -> {
            try {
                boolean created = core(c).register();
                Prefs.get(c).edit()
                        .putBoolean(Prefs.KEY_RELAY_REGISTERED, true).apply();
                deliver(() -> cb.ok(created));
            } catch (Exception e) {
                deliverErr(cb, e);
            }
        });
    }

    // ---- speed probe (v1.23) ----

    /** The walkie mode probe: one timed round trip to the
     *  relay, delivered on the main thread as elapsed
     *  milliseconds. A transport failure arrives as err
     *  ("network") — the caller reads it as unreachable and
     *  keeps the last mode decision. */
    public static void probe(final Context c, final Cb<Long> cb) {
        POOL.execute(() -> {
            try {
                long ms = core(c).probeRoundTrip();
                deliver(() -> cb.ok(ms));
            } catch (Exception e) {
                deliverErr(cb, e);
            }
        });
    }

    // ---- channel calls ----

    public static void listChannels(final Context c,
                                    final Cb<List<RelayCore.ChannelInfo>> cb) {
        POOL.execute(() -> {
            try {
                List<RelayCore.ChannelInfo> r = core(c).listChannels();
                deliver(() -> cb.ok(r));
            } catch (Exception e) {
                deliverErr(cb, e);
            }
        });
    }

    public static void addMember(final Context c, final String channelId,
                                 final String memberPubB64, final Cb<Boolean> cb) {
        POOL.execute(() -> {
            try {
                boolean added = core(c).addMember(channelId, memberPubB64);
                deliver(() -> cb.ok(added));
            } catch (Exception e) {
                deliverErr(cb, e);
            }
        });
    }

    public static void post(final Context c, final String channelId,
                            final String clientMsgId, final String ciphertextB64,
                            final Cb<RelayCore.PostResult> cb) {
        POOL.execute(() -> {
            try {
                RelayCore.PostResult r = core(c).postMessage(
                        channelId, clientMsgId, ciphertextB64);
                deliver(() -> cb.ok(r));
            } catch (Exception e) {
                deliverErr(cb, e);
            }
        });
    }

    public static void pull(final Context c, final String channelId,
                            final long after, final int limit,
                            final Cb<RelayCore.PullResult> cb) {
        POOL.execute(() -> {
            try {
                RelayCore.PullResult r = core(c).pullMessages(channelId, after, limit);
                deliver(() -> cb.ok(r));
            } catch (Exception e) {
                deliverErr(cb, e);
            }
        });
    }

    /** Fire-and-forget member sync (walkie private channels, DM opens):
     *  errors are expected in normal life (offline, not yet a member)
     *  and are deliberately silent — the chat/walkie status lines say
     *  what the user needs to know. */
    public static void addMemberQuiet(final Context c, final String channelId,
                                      final String memberPubB64) {
        POOL.execute(() -> {
            try {
                core(c).addMember(channelId, memberPubB64);
            } catch (Exception ignored) {
            }
        });
    }

    // ---- plumbing ----

    private static void deliver(Runnable r) {
        MAIN.post(r);
    }

    private static void deliverErr(Cb<?> cb, Exception e) {
        if (e instanceof RelayCore.RelayException) {
            RelayCore.RelayException re = (RelayCore.RelayException) e;
            MAIN.post(() -> cb.err(re.code, re.getMessage()));
        } else {
            String m = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            MAIN.post(() -> cb.err("network", m));
        }
    }
}
