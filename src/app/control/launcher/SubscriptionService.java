package app.control.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Background channel listening (v1.19): a foreground service
 * that keeps polling every subscribed walkie channel over the
 * internet relay — even when the phone is tuned elsewhere, or
 * the launcher is in the background — so a message sent on a
 * subscribed channel is received any time it is sent.
 *
 * WHY A FOREGROUND SERVICE: Android does not let a background
 * app poll every few seconds — WorkManager's minimum periodic
 * interval is 15 minutes, and an exact alarm every few seconds
 * is neither allowed nor honest about battery. A foreground
 * service with a persistent notification is the lightest
 * mechanism that actually keeps polling, and it says so
 * plainly in the notification shade. The cost is stated in
 * the subscription UI copy: each subscribed channel is
 * checked every few seconds (less often as subscriptions
 * grow), which uses a little mobile data and battery, and the
 * service stops itself the moment the last subscription is
 * removed. No wakelocks are held between polls; the worker
 * thread simply waits. Samsung battery management may still
 * kill the service — the walkie page's own loop is the
 * backstop (it resumes polling the tuned channel itself when
 * the service is down), and opening the walkie page
 * reconciles the service back to life.
 *
 * SINGLE-POLLER RULE: this service owns polling for every
 * subscribed channel, INCLUDING the tuned one. The walkie
 * page's foreground loop polls the tuned channel only while
 * it is NOT subscribed, or while this service is not alive
 * (the backstop). Both pollers share the persisted
 * per-channel cursors, and both check the store-backed cmid
 * dedup (RelayIngest.alreadyStored) before appending — so a
 * message pulled twice, by two pollers or across a restart,
 * is stored exactly once.
 *
 * WHAT ARRIVES GOES THROUGH THE SAME PIPELINE as a
 * tuned-channel receipt: entries are built by the shared
 * RelayIngest code, appended to the channel's WalkieStore
 * thread (unplayed), broadcast so an open walkie page
 * refreshes, read aloud by the same LowDataSpeaker /
 * SpeakGate path (the read-aloud switch, QUIET absolute, the
 * sender's voice profile), and moved to History when the
 * utterance finishes (the same HistoryRule mark). Relay
 * bursts land in the thread unplayed, as they do on the
 * tuned-channel path — they are played by tapping, never
 * auto-played from the background.
 *
 * PRIME STEP: a channel polled for the first time (cursor 0,
 * never polled before) is fast-forwarded to live: the service
 * pulls through its retained history WITHOUT ingesting, so
 * subscribing listens from now on instead of flooding the
 * thread — and the speaker — with up to 30 days of old
 * messages. The fast-forward is bounded (at most 5 pulls of
 * 500). A channel with an existing cursor resumes from the
 * cursor, so nothing posted while the phone was away is
 * lost inside the relay's retention.
 *
 * Membership: subscribing to a private channel runs the same
 * member-add sync the tuned-channel path runs
 * (RelayMembership). A channel the relay refuses
 * (not_a_member, channel_not_found) is skipped for 10 cycles
 * and retried, and its state is visible to the walkie page's
 * subscription section, which names the blocker honestly.
 * Offline waits quietly with backoff — no toasts, no spam.
 *
 * Gating: the service runs only while at least one channel is
 * subscribed. reconcile() enforces it; kick() wakes the
 * worker for an immediate cycle (a subscribe toggle, the
 * walkie page coming forward, boot). BootReceiver reconciles
 * after reboot, so listening resumes with no user action.
 */
public class SubscriptionService extends Service {

    /** Broadcast when a subscribed channel receives a message:
     *  extra EXTRA_IDENTITY (slot identity) + EXTRA_NUMBER.
     *  Sent app-scoped (setPackage); the walkie page listens
     *  while visible and reloads the tuned thread. */
    public static final String ACTION_MESSAGE =
            "app.control.launcher.SUBSCRIPTION_MESSAGE";
    /** Broadcast when a channel's poll state changes (a
     *  refusal lands or clears): the walkie page refreshes
     *  its subscription section line. */
    public static final String ACTION_STATE =
            "app.control.launcher.SUBSCRIPTION_STATE";
    public static final String EXTRA_IDENTITY = "identity";
    public static final String EXTRA_NUMBER = "number";
    public static final String EXTRA_CHANNEL_ID = "channel_id";

    private static final String CHANNEL_ID = "channel-subscriptions";
    private static final int NOTIF_ID = 7;
    /** Pull page size for normal polls (the relay default is
     *  200; 500 is the max — one poll per channel per cycle
     *  keeps up with any realistic burst rate). */
    private static final int PULL_LIMIT = 500;
    /** Bounded fast-forward: at most this many pulls of
     *  PULL_LIMIT while priming a never-polled channel. */
    private static final int PRIME_MAX_PULLS = 5;

    /** True while the service is alive. Read by the walkie
     *  page's loop: when false, the page polls the tuned
     *  channel itself (the backstop). */
    public static volatile boolean serviceAlive;

    /** Last poll state per relay channel id. Absent means
     *  UNKNOWN (no attempt has reported yet). */
    private static final Map<String, SubscriptionPolicy.ChannelState> STATES =
            new ConcurrentHashMap<>();

    /** Wake-up for the worker thread: kicks notify on this
     *  lock. Static because kick() may run before the service
     *  instance exists. */
    private static final Object WAKE = new Object();
    private static volatile boolean kickPending;

    private volatile boolean workerStop;
    private Thread worker;
    private LowDataSpeaker speaker;

    // ---- static gating API ----

    /** True when at least one channel is subscribed. */
    public static boolean hasSubscriptions(Context c) {
        return !ChannelSubscriptions.subscribed(c).isEmpty();
    }

    /** Last known poll state for a relay channel id. */
    public static SubscriptionPolicy.ChannelState channelState(String channelId) {
        if (channelId == null) return SubscriptionPolicy.ChannelState.UNKNOWN;
        SubscriptionPolicy.ChannelState s = STATES.get(channelId);
        return s != null ? s : SubscriptionPolicy.ChannelState.UNKNOWN;
    }

    /** Enforce the gating: start the service when a channel
     *  is subscribed, stop it otherwise. Safe to call often. */
    public static void reconcile(Context context) {
        final Context app = context.getApplicationContext();
        Intent intent = new Intent(app, SubscriptionService.class);
        if (hasSubscriptions(app)) {
            try {
                app.startForegroundService(intent);
            } catch (Exception ignored) {
                // Refused in this state; the next kick (the
                // walkie page coming forward, a toggle, a
                // reboot) retries, and the page loop is the
                // backstop for the tuned channel meanwhile.
            }
        } else {
            try {
                app.stopService(intent);
            } catch (Exception ignored) {
            }
        }
    }

    /** Wake the worker for an immediate poll cycle, starting
     *  the service when a channel is subscribed. Does nothing
     *  when nothing is subscribed. */
    public static void kick(Context context) {
        final Context app = context.getApplicationContext();
        if (!hasSubscriptions(app)) return;
        synchronized (WAKE) {
            kickPending = true;
            WAKE.notifyAll();
        }
        try {
            app.startForegroundService(
                    new Intent(app, SubscriptionService.class));
        } catch (Exception ignored) {
            // Same as reconcile: the next foreground moment
            // retries.
        }
    }

    // ---- service lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
        startForeground(NOTIF_ID, buildNotification());
        speaker = new LowDataSpeaker(this);
        worker = new Thread(this::workerLoop, "channel-subscriptions");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Refresh the notification's channel count — the set
        // may have changed since the service started.
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        workerStop = true;
        synchronized (WAKE) {
            WAKE.notifyAll();
        }
        if (worker != null) worker.interrupt();
        if (speaker != null) speaker.shutdown();
        serviceAlive = false;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ---- the poller ----

    /** One subscribed channel, resolved fresh each cycle from
     *  the current slot config (a reconfigured slot keeps its
     *  subscription under its new relay id). */
    private static final class Target {
        final int number;
        final String identity;
        final String channelId;
        final byte[] keyBytes;

        Target(int number, String identity, String channelId, byte[] keyBytes) {
            this.number = number;
            this.identity = identity;
            this.channelId = channelId;
            this.keyBytes = keyBytes;
        }
    }

    private List<Target> buildTargets() {
        List<Target> out = new ArrayList<>();
        for (int n : ChannelSubscriptions.subscribed(this)) {
            JSONObject slot = Profile.slot(this, n);
            String channelId = RelayIds.walkieChannelId(
                    RelayIds.wireNumber(n, slot.optString("wireId", "")),
                    Profile.isPrivate(slot), slot.optString("key", ""));
            // A private slot with no valid key has no relay id
            // and nothing to poll; the UI cannot create one,
            // but a hand-edited profile could — skip it.
            if (channelId == null) continue;
            byte[] keyBytes = Profile.isPrivate(slot)
                    ? RelayIds.decodeKey(slot.optString("key", "")) : null;
            out.add(new Target(n, Profile.slotIdentity(slot), channelId, keyBytes));
        }
        return out;
    }

    private void workerLoop() {
        int roundRobin = 0;
        int offlineFailures = 0;
        // Channels fast-forwarded to live this service
        // lifetime (the prime step runs once per channel per
        // start; the persisted cursor carries it across
        // restarts, since a primed channel's cursor is > 0).
        Set<String> primed = new HashSet<>();
        // Refused channels: channel id -> cycles skipped.
        Map<String, Integer> blockedSkips = new HashMap<>();
        // In-memory seen ids per channel, seeded lazily from
        // the store on first poll — the store check is the
        // backstop, this is the fast path.
        Map<String, Set<String>> seenByChannel = new HashMap<>();

        while (!workerStop) {
            List<Target> targets = buildTargets();
            if (targets.isEmpty()) {
                // Last subscription removed while we waited:
                // stop. reconcile() restarts us on the next
                // subscribe.
                stopSelf();
                break;
            }
            long spacing = SubscriptionPolicy.requestSpacingMs(targets.size());
            long backoff = SubscriptionPolicy.offlineBackoffMs(offlineFailures);
            long waitMs = Math.max(spacing, backoff);
            synchronized (WAKE) {
                if (!kickPending && !workerStop) {
                    try {
                        WAKE.wait(waitMs);
                    } catch (InterruptedException ignored) {
                    }
                }
                kickPending = false;
            }
            if (workerStop) break;

            Target t = targets.get(roundRobin++ % targets.size());
            Integer skipped = blockedSkips.get(t.channelId);
            if (skipped != null
                    && !SubscriptionPolicy.shouldRetryBlocked(skipped)) {
                blockedSkips.put(t.channelId, skipped + 1);
                continue;
            }
            boolean ok = pollTarget(t, primed, seenByChannel);
            if (ok) {
                offlineFailures = 0;
                blockedSkips.remove(t.channelId);
            } else {
                // pollTarget already classified the failure
                // into STATES. A refused channel starts (or
                // restarts) its 10-cycle skip counter — the
                // retry is how a subscription starts working
                // when membership lands later. Only transport
                // failures feed the offline backoff.
                SubscriptionPolicy.ChannelState s = channelState(t.channelId);
                if (s == SubscriptionPolicy.ChannelState.BLOCKED_NOT_MEMBER
                        || s == SubscriptionPolicy.ChannelState.BLOCKED_NOT_FOUND) {
                    blockedSkips.put(t.channelId, 0);
                } else if (s == SubscriptionPolicy.ChannelState.OFFLINE) {
                    offlineFailures++;
                }
            }
        }
    }

    /**
     * Polls one channel once. Returns true when the pull
     * succeeded (even with zero new messages). On failure the
     * channel's state is recorded for the UI and a state
     * broadcast is sent when the state changed.
     */
    private boolean pollTarget(Target t, Set<String> primed,
                               Map<String, Set<String>> seenByChannel) {
        RelayCore core;
        try {
            core = RelayClient.core(this);
        } catch (Exception e) {
            setState(t.channelId, SubscriptionPolicy.ChannelState.OFFLINE);
            return false;
        }
        long cursor = RelayClient.cursor(this, t.channelId);
        boolean prime = cursor == 0 && !primed.contains(t.channelId);
        try {
            RelayCore.PullResult r = core.pullMessages(t.channelId, cursor, PULL_LIMIT);
            if (prime) {
                // Fast-forward to live without ingesting: pull
                // through retained history (bounded) so the
                // cursor lands on the newest message.
                int rounds = 1;
                while (r.messages.size() == PULL_LIMIT && rounds < PRIME_MAX_PULLS) {
                    RelayClient.setCursor(this, t.channelId, r.nextAfter);
                    r = core.pullMessages(t.channelId, r.nextAfter, PULL_LIMIT);
                    rounds++;
                }
                RelayClient.setCursor(this, t.channelId, r.nextAfter);
                primed.add(t.channelId);
            } else {
                RelayClient.setCursor(this, t.channelId, r.nextAfter);
                ingest(t, r, seenByChannel);
            }
            setState(t.channelId, SubscriptionPolicy.ChannelState.OK);
            return true;
        } catch (RelayCore.RelayException e) {
            if ("unregistered_device".equals(e.code)) {
                // Re-register (throttled) and retry next
                // cycle; the state stays OFFLINE meanwhile —
                // the user sees "waiting", not an error.
                RelayClient.ensureRegistered(this);
                setState(t.channelId, SubscriptionPolicy.ChannelState.OFFLINE);
                return false;
            }
            SubscriptionPolicy.ChannelState s =
                    SubscriptionPolicy.classifyError(e.code);
            setState(t.channelId, s);
            if (s == SubscriptionPolicy.ChannelState.RATE_LIMITED) {
                // The schedule should make this unreachable;
                // the backstop is a minute's pause, not a
                // tight retry loop.
                try {
                    Thread.sleep(SubscriptionPolicy.RATE_LIMIT_PAUSE_MS);
                } catch (InterruptedException ignored) {
                }
            }
            return false;
        } catch (Exception e) {
            // Transport failure (offline, DNS, timeout): quiet.
            setState(t.channelId, SubscriptionPolicy.ChannelState.OFFLINE);
            return false;
        }
    }

    private void setState(String channelId, SubscriptionPolicy.ChannelState s) {
        SubscriptionPolicy.ChannelState prev = STATES.put(channelId, s);
        if (prev != s) {
            Intent b = new Intent(ACTION_STATE);
            b.setPackage(getPackageName());
            b.putExtra(EXTRA_CHANNEL_ID, channelId);
            sendBroadcast(b);
        }
    }

    // ---- ingest: the same pipeline as a tuned-channel receipt ----

    private void ingest(Target t, RelayCore.PullResult r,
                        Map<String, Set<String>> seenByChannel) {
        Set<String> seen = seenByChannel.get(t.channelId);
        if (seen == null) {
            seen = new HashSet<>();
            for (JSONObject e : WalkieStore.thread(this, t.identity)) {
                String cmid = e.optString("cmid", "");
                if (!cmid.isEmpty()) seen.add(cmid);
            }
            seenByChannel.put(t.channelId, seen);
        }
        String myPub = RelayClient.myPub(this);
        for (RelayCore.Msg m : r.messages) {
            if (!m.clientMsgId.isEmpty() && !seen.add(m.clientMsgId)) continue;
            if (WalkieStore.hasCmid(this, t.identity, m.clientMsgId)) continue;
            RelayIngest.Decoded d = RelayIngest.decode(t.keyBytes, m.ciphertext);
            if (d == null) continue;
            boolean mine = myPub.equals(m.sender);
            String from = mine ? myName() : ContactCard.senderName(this, m.sender);
            JSONObject entry = WalkieStore.entry(
                    d.isText ? "text" : "voice", from,
                    d.isText ? d.text : "", "", d.samples,
                    "Internet", mine, "sent");
            RelayIngest.stampEntry(entry, m, d);
            WalkieStore.append(this, t.identity, entry);
            broadcastMessage(t);
            if (d.isText && !mine) speakEntry(t, entry, from, m.sender, d.text);
        }
    }

    /** Reads a received text aloud in the sender's voice
     *  profile, gated exactly as the tuned-channel path
     *  (SpeakGate: the read-aloud switch, QUIET absolute).
     *  When the utterance FINISHES, the entry moves to
     *  History (the same HistoryRule mark the page path
     *  makes) — a gated, failed, or interrupted utterance
     *  never marks, so the message stays unplayed. */
    private void speakEntry(Target t, JSONObject entry,
                            String fromName, String senderPub, String text) {
        if (speaker == null) return;
        VoiceProfile profile = Profile.voiceForSenderKey(this, senderPub);
        speaker.speak(this, text, profile, false, () -> {
            if (!HistoryRule.markPlayed(entry)) return;
            WalkieStore.saveNow(this);
            broadcastMessage(t);
        });
    }

    private String myName() {
        return Profile.displayName(this);
    }

    private void broadcastMessage(Target t) {
        Intent b = new Intent(ACTION_MESSAGE);
        b.setPackage(getPackageName());
        b.putExtra(EXTRA_IDENTITY, t.identity);
        b.putExtra(EXTRA_NUMBER, t.number);
        sendBroadcast(b);
    }

    // ---- notification ----

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Channel subscriptions",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra(MainActivity.EXTRA_PAGE, MainActivity.PAGE_WALKIE);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int n = ChannelSubscriptions.subscribed(this).size();
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Listening to " + n
                        + (n == 1 ? " channel" : " channels"))
                .setContentText("Messages on subscribed channels arrive "
                        + "and are read aloud. Quiet silences read-aloud.")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
