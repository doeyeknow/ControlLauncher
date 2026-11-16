package app.control.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Bridge auto-restart watchdog (v1.16): a foreground service
 * that keeps the Deep-view bridge up by itself. Pair once, and
 * from then on the bridge comes back after it dies — including
 * after a reboot, while wireless debugging stays on — using
 * the stored ADB pairing keys. No user interaction, no prompt
 * spam.
 *
 * What it cannot do, plainly: it cannot BE the bridge. A
 * background process runs as the app UID and can never hold
 * shell powers; the bridge must be launched through wireless
 * debugging. This service only watches (a socket ping every
 * five minutes) and re-runs the existing in-app restart flows
 * (AdbStarter.restartBridge for a dead bridge,
 * AdbStarter.restartStaleBridge for a stale one) when the ping
 * says the bridge is down or too old. If wireless debugging is
 * off or the pairing is gone, the restart fails, the service
 * backs off, and the Settings row names the blocker — it never
 * pretends the bridge is up.
 *
 * Gating: the service runs only while Deep view is enabled
 * (Prefs.KEY_DEEP_VIEW_ON — set when a bridge start succeeds
 * or Deep view finds the bridge up, cleared by "Turn off deep
 * control"). It never starts the bridge for someone who never
 * enabled Deep view. Turning Deep view off stops the service
 * through {@link #reconcile}.
 *
 * Triggers: the five-minute ping, screen unlock
 * (ACTION_USER_PRESENT), Deep view / Settings coming to the
 * foreground (both call {@link #kick}), and boot (see
 * BootReceiver). Failed restarts back off per WatchdogPolicy
 * and then wait for a trigger. No wakelocks are held between
 * pings; the worker thread simply waits. Samsung battery
 * management may still kill the service — the unlock and
 * foreground triggers are the backstop that brings the watch
 * back, and the Settings row says so when the watch is down.
 *
 * Every restart attempt and its outcome is written to the
 * audit log (OwnLog, area "Bridge watchdog") with the real
 * reason on failure. AdbStarter logs its own stages
 * separately; this service adds one line per attempt, not a
 * duplicate of the stage log.
 */
public class BridgeWatchdogService extends Service {

    private static final String CHANNEL_ID = "bridge-watchdog";
    private static final int NOTIF_ID = 6;

    /** Longest the worker waits for a restart callback before
     *  treating the restart as failed. A restart can take
     *  ~15s of discovery + ~25s of shell + ~10s of polling,
     *  plus one re-discovery retry, so 120s is a safety net,
     *  not the expected path — the callback wakes the worker
     *  as soon as the restart reports. */
    private static final long RESTART_WAIT_TIMEOUT_MS = 120_000L;

    /** True while the service is alive. Read by the Settings
     *  summary line, alongside the last fresh ping. */
    public static volatile boolean serviceAlive;

    /** Last bridge state from a fresh ping. UNKNOWN until the
     *  first ping completes. */
    public static volatile BridgeGate.State lastBridgeState = BridgeGate.State.UNKNOWN;

    /** Last bridge protocol version from a fresh ping. 0 when
     *  no ping has completed or the bridge reported none. */
    public static volatile int lastBridgeVersion;

    /** Why the last restart failed, for the Settings row.
     *  Reset on the next successful ping of a CURRENT bridge. */
    public static volatile WatchdogPolicy.Blocked lastBlocked = WatchdogPolicy.Blocked.NONE;

    /** First line of the last restart failure's summary, for
     *  the Settings row. Empty when there is nothing to show. */
    public static volatile String lastBlockerText = "";

    /** Consecutive failed restarts. Reset on success. Drives
     *  the backoff schedule in WatchdogPolicy. */
    public static volatile int consecutiveFailures;

    /** Wake-up for the worker thread: kicks and restart
     *  callbacks notify on this lock. Static because kick()
     *  may run before the service instance exists. */
    private static final Object WAKE = new Object();
    private static volatile boolean kickPending;

    private volatile boolean workerStop;
    private Thread worker;

    private volatile boolean restartInFlight;
    private volatile boolean restartDone;
    private volatile boolean restartOutcome;
    private volatile String restartSummary = "";

    private BroadcastReceiver unlockReceiver;

    // ---- static gating API ----

    /** Deep view is enabled: a bridge start has succeeded, or
     *  Deep view has found the bridge up, and the user has not
     *  turned deep control off since. Default false — a fresh
     *  install never auto-restarts anything. */
    public static boolean deepViewOn(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_DEEP_VIEW_ON, false);
    }

    /** The Settings / Deep view summary line: one honest
     *  sentence about the watch. Synchronous — it reads prefs,
     *  the last fresh ping, and the last restart outcome, never
     *  the network or a socket. */
    public static String stateLine(Context c) {
        return WatchdogPolicy.stateLine(deepViewOn(c), serviceAlive,
                lastBridgeState, lastBlocked, consecutiveFailures,
                lastBlockerText);
    }

    /**
     * Enforce the gating: start the service when Deep view is
     * on, stop it otherwise. Safe to call often — starting an
     * already-running service is a no-op, and stopping a
     * stopped one is too.
     */
    public static void reconcile(Context context) {
        final Context app = context.getApplicationContext();
        Intent intent = new Intent(app, BridgeWatchdogService.class);
        if (deepViewOn(app)) {
            try {
                app.startForegroundService(intent);
            } catch (Exception ignored) {
                // Foreground starts from the background are
                // restricted in some states; the next kick
                // (unlock, Deep view, Settings) retries.
            }
        } else {
            try {
                app.stopService(intent);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Wake the watch for an immediate check (unlock, Deep view
     * / Settings foreground, boot, a bridge start). Starts the
     * service when Deep view is on; does nothing when it is
     * off — a kick must never start the bridge for someone
     * who never enabled Deep view.
     */
    public static void kick(Context context) {
        final Context app = context.getApplicationContext();
        if (!deepViewOn(app)) return;
        synchronized (WAKE) {
            kickPending = true;
            WAKE.notifyAll();
        }
        try {
            app.startForegroundService(
                    new Intent(app, BridgeWatchdogService.class));
        } catch (Exception ignored) {
            // Same as reconcile: the next foreground moment
            // retries. Nothing is lost — Deep view's own
            // restart button always works.
        }
    }

    // ---- service lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
        startForeground(NOTIF_ID, buildNotification());
        unlockReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // Screen unlock: check now, even if the
                // backoff schedule is exhausted — triggers
                // are the only retry left then.
                synchronized (WAKE) {
                    kickPending = true;
                    WAKE.notifyAll();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        // Same API-33 guard as ConnectionsActivity: the 2-arg
        // call throws for non-exempt filters on API 33+.
        // RECEIVER_NOT_EXPORTED is correct — USER_PRESENT is
        // a system broadcast, and no other app needs to reach
        // this receiver.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(unlockReceiver, filter);
        }
        worker = new Thread(this::workerLoop, "bridge-watchdog");
        worker.setDaemon(true);
        worker.start();
        OwnLog.log(this, "Bridge watchdog",
                "Watch started — the bridge restarts itself if it stops.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (Exception ignored) {
            }
            unlockReceiver = null;
        }
        serviceAlive = false;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ---- the watch ----

    private void workerLoop() {
        long nextDelay = 0L; // check immediately on start
        while (!workerStop) {
            // Wait for the delay, a kick, or the restart
            // callback. A negative delay means "triggers
            // only": wait until a kick wakes us.
            synchronized (WAKE) {
                if (!kickPending && !workerStop) {
                    try {
                        if (nextDelay < 0) {
                            WAKE.wait();
                        } else if (nextDelay > 0) {
                            WAKE.wait(nextDelay);
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
                kickPending = false;
            }
            if (workerStop) break;
            if (!deepViewOn(this)) {
                // Deep view was turned off while we waited:
                // stop. The bridge stays stopped per the
                // existing stop flow; the watchdog never
                // restarts it behind the user's back.
                stopSelf();
                break;
            }
            if (restartInFlight) {
                // A restart is running (its callback wakes us
                // when it reports). Do not start a second one;
                // re-check after the normal interval.
                nextDelay = WatchdogPolicy.PING_INTERVAL_MS;
                continue;
            }
            ShellBridgeClient.Response ping = ShellBridgeClient.ping(this);
            lastBridgeVersion = ping.bridgeVersion;
            lastBridgeState = BridgeGate.classify(ping.ok, ping.bridgeVersion);
            WatchdogPolicy.Action action = WatchdogPolicy.decide(
                    true, lastBridgeState, false);
            if (action == WatchdogPolicy.Action.NONE) {
                if (lastBridgeState == BridgeGate.State.CURRENT) {
                    consecutiveFailures = 0;
                    lastBlocked = WatchdogPolicy.Blocked.NONE;
                    lastBlockerText = "";
                }
                nextDelay = WatchdogPolicy.PING_INTERVAL_MS;
                continue;
            }
            runRestart(action);
            if (restartOutcome) {
                consecutiveFailures = 0;
                lastBlocked = WatchdogPolicy.Blocked.NONE;
                lastBlockerText = "";
                // Refresh the bridge state for the Settings
                // row now, instead of waiting five minutes
                // to prove the restart worked.
                ShellBridgeClient.Response after =
                        ShellBridgeClient.ping(this);
                lastBridgeVersion = after.bridgeVersion;
                lastBridgeState = BridgeGate.classify(
                        after.ok, after.bridgeVersion);
                nextDelay = WatchdogPolicy.PING_INTERVAL_MS;
            } else {
                consecutiveFailures++;
                lastBlocked = WatchdogPolicy.classifyFailure(restartSummary);
                lastBlockerText = WatchdogPolicy.firstLine(restartSummary);
                nextDelay = WatchdogPolicy.backoffDelayMs(consecutiveFailures);
            }
        }
    }

    /**
     * Run one restart and block until it reports (or the
     * safety timeout fires). The restart itself runs on
     * AdbStarter's thread; this method only waits for the
     * outcome. Single-flight: the worker never calls this
     * while a restart is in flight.
     */
    private void runRestart(WatchdogPolicy.Action action) {
        restartInFlight = true;
        restartDone = false;
        restartOutcome = false;
        restartSummary = "";
        OwnLog.log(this, "Bridge watchdog",
                action == WatchdogPolicy.Action.RESTART_STALE
                        ? "Bridge is running old code ("
                        + BridgeGate.versionName(lastBridgeVersion)
                        + ") — restarting it for the current version."
                        : "Bridge is down — restarting it.");
        AdbStarter.Callback cb = new AdbStarter.Callback() {
            @Override public void onStatus(String line) {
                // Stage lines are already audit-logged by
                // AdbStarter; the watchdog logs one line per
                // attempt, not a duplicate of the stage log.
            }

            @Override public void onDone(boolean up, String summary) {
                synchronized (WAKE) {
                    restartOutcome = up;
                    restartSummary = summary == null ? "" : summary;
                    restartDone = true;
                    restartInFlight = false;
                    WAKE.notifyAll();
                }
            }
        };
        if (action == WatchdogPolicy.Action.RESTART_STALE) {
            AdbStarter.restartStaleBridge(this, cb);
        } else {
            AdbStarter.restartBridge(this, cb);
        }
        long deadline = SystemClock.elapsedRealtime() + RESTART_WAIT_TIMEOUT_MS;
        synchronized (WAKE) {
            while (!restartDone && !workerStop) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                try {
                    WAKE.wait(remaining);
                } catch (InterruptedException ignored) {
                }
            }
        }
        restartInFlight = false;
        if (!restartDone) {
            restartOutcome = false;
            restartSummary = "The restart did not report back.";
        }
        OwnLog.log(this, "Bridge watchdog",
                restartOutcome
                        ? "Bridge restarted itself."
                        : "Restart failed: " + restartSummary);
    }

    // ---- notification ----

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Deep view auto-restart",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DeepViewActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Deep view auto-restart is on")
                .setContentText("Control restarts the bridge if it stops.")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
