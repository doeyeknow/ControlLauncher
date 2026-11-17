package app.control.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The overlay ring (v1.14): a foreground service that watches for
 * a stationary hold anywhere on screen (via the Deep-view bridge's
 * HOLD_WATCH stream) and opens the circle menu — the SAME
 * CircleMenuView the launcher uses, with the same sub-rings,
 * dwell, rotational paging, and release-to-open — as a
 * TYPE_APPLICATION_OVERLAY window centered on the hold point.
 *
 * The overlay is ADDITIVE, never a replacement: the in-launcher
 * long-press menu stays fully intact and works with Deep view
 * off and no overlay permission (see BUILD_NOTES). When the
 * in-launcher menu is already open, hold events are ignored —
 * the built-in menu owns that gesture. The overlay fires over
 * other apps, and over launcher surfaces the pager yields (the
 * WebView, the tuner), where the built-in menu never opens.
 *
 * Gating: the service runs only while the feature switch is on,
 * the overlay permission is granted, AND the bridge answers at
 * the required protocol version (v1.15.1: a stale bridge — one
 * that survived an APK upgrade running old code — answers PING
 * but has no HOLD_WATCH op, so starting the service against it
 * would just die again on every reconcile). {@link #reconcile}
 * enforces that from a background thread (bridge pings are
 * socket IO); it is called from Settings, from Deep view after
 * bridge start/stop, and after the overlay permission returns.
 * If the bridge dies, the watch ends and the service stops
 * itself — no crash, no prompt spam, and the Settings row says
 * plainly which gate failed (switch, permission, bridge not
 * running, or bridge too old) from the last fresh ping, never
 * from the service's liveness alone.
 *
 * The window is sized to the ring (plus a small margin) so
 * touches outside it pass through to the app underneath, with
 * FLAG_WATCH_OUTSIDE_TOUCH so a tap anywhere outside dismisses
 * the ring. Back dismisses too (the window takes focus while
 * the ring is up, and the wrapper consumes the key). The app
 * underneath may also fire its own long-press for the opening
 * hold — accepted by Adam. The opening gesture itself belongs
 * to the app underneath (its DOWN predates the window), so the
 * ring is driven by the next touch: lift, then tap or drag.
 *
 * A persistent notification is required for a foreground
 * service; it says the menu is watching and opens the launcher.
 */
public class OverlayRingService extends Service {

    private static final String CHANNEL_ID = "overlay-ring";
    private static final int NOTIF_ID = 5;

    /** True while the service (and its watch) is alive. Read by
     *  the Settings summary line. */
    public static volatile boolean serviceAlive;

    /** Last bridge state from a fresh ping (v1.15.1), set by
     *  reconcile(). UNKNOWN until the first ping completes.
     *  The Settings row reads this — never serviceAlive alone —
     *  so a stale-but-running bridge is reported as stale, not
     *  as "Deep view is not running". */
    public static volatile BridgeGate.State lastBridgeState = BridgeGate.State.UNKNOWN;

    /** Last bridge protocol version from a fresh ping (v1.15.1).
     *  0 when no ping has completed or the bridge reported none. */
    public static volatile int lastBridgeVersion;

    /** Watch-stage diagnostics (v1.43), read by {@link #statusLine2}:
     *  when the watch last ended and why, when the last hold
     *  arrived, and the last overlay-window failure. Never
     *  coordinates. These exist because the ring's failure was
     *  invisible: the gates could all pass while no hold ever
     *  arrived or the window add silently failed. */
    public static volatile String lastWatchEndReason = "";
    public static volatile long lastWatchEndAtMs;
    public static volatile long lastHoldAtMs;
    public static volatile String lastShowError = "";

    /** Last bridge-side hold-watch diagnostics (v1.44), fetched
     *  by reconcile() whenever the bridge is CURRENT. Null when
     *  no fetch has succeeded (an old bridge, a failed fetch, or
     *  the switch off) — the Settings line then falls back to
     *  the v1.43 app-side-only wording. */
    public static volatile HoldWatchStatus lastHoldStatus;

    private HoldWatchClient watcher;
    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private CircleMenuView menu;
    private OverlayRingSource source;

    // ---- static gating API ----

    /** The feature switch (Settings). Default ON: the gates
     *  below keep the feature inert until everything it needs
     *  is ready, and granting the overlay permission is the
     *  effective opt-in. */
    public static boolean switchOn(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_OVERLAY_RING_ON, true);
    }

    public static boolean overlayGranted(Context c) {
        try {
            return Settings.canDrawOverlays(c);
        } catch (Exception e) {
            return false;
        }
    }

    /** The Settings summary line: one honest sentence about why
     *  the menu is or is not watching. Synchronous — it reads
     *  the switch, the permission, the last fresh ping's bridge
     *  state, and the service's liveness, never the network or
     *  a socket. The bridge state comes from reconcile()'s ping
     *  (UNKNOWN until the first one completes), so a stale
     *  bridge is named as stale instead of misreported as
     *  "Deep view is not running". */
    public static String stateLine(Context c) {
        return BridgeGate.stateLine(switchOn(c), overlayGranted(c),
                lastBridgeState, serviceAlive);
    }

    /**
     * Second Settings line (v1.43, extended v1.44): which stage
     * of the hold-anywhere path last failed, if any. Empty when
     * the switch is off (the stages are stale noise then). With
     * bridge-side diagnostics (v10 bridge), the line names the
     * picked device and the frame/hold counts, so a dead stream,
     * a parser that never fires, and a dropped hold tell apart;
     * without them it keeps the v1.43 app-side wording. The
     * composition lives in {@link HoldWatchStatus} (pure Java,
     * harness-pinned). No coordinates, ever.
     */
    public static String statusLine2(Context c) {
        return HoldWatchStatus.formatLine(switchOn(c), serviceAlive,
                lastWatchEndReason, lastWatchEndAtMs, lastHoldAtMs,
                lastShowError, lastHoldStatus,
                android.os.SystemClock.elapsedRealtime());
    }

    /**
     * Enforce the gating: start the service when the switch is
     * on, the overlay permission is granted, and the bridge
     * answers at the required version; stop it otherwise. Runs
     * on a background thread (the bridge ping is socket IO) and
     * is safe to call often — starting an already-running
     * service is a no-op, and stopping a stopped one is too.
     * The optional callback runs on the main thread after the
     * ping, so a visible screen can re-render its summary from
     * the fresh bridge state.
     */
    public static void reconcile(Context context) {
        reconcile(context, null);
    }

    public static void reconcile(Context context, Runnable onPingDone) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            // One fresh ping every reconcile, even when the
            // switch/permission gates already fail: the row
            // must name the bridge state honestly (stale vs
            // not running) from a fresh check, not from the
            // service's liveness alone.
            ShellBridgeClient.Response ping = ShellBridgeClient.ping(app);
            int version = ping.bridgeVersion;
            BridgeGate.State bridge = BridgeGate.classify(ping.ok, version);
            lastBridgeState = bridge;
            lastBridgeVersion = version;
            // Bridge-side hold-watch diagnostics (v1.44): one
            // more local-socket round trip, only when the
            // switch is on and the bridge is CURRENT (v10+),
            // which is exactly when the op exists. A v9 bridge
            // would refuse it, so it is never asked. The fetch
            // runs before the ping-done callback below, so a
            // visible screen re-renders from fresh data.
            if (switchOn(app) && bridge == BridgeGate.State.CURRENT) {
                try {
                    ShellBridgeClient.Response st =
                            ShellBridgeClient.holdWatchStatus(app);
                    lastHoldStatus = st.ok
                            ? HoldWatchStatus.parse(st.text) : null;
                } catch (Exception ignored) {
                    lastHoldStatus = null;
                }
            } else {
                lastHoldStatus = null;
            }
            boolean want = BridgeGate.wantService(
                    switchOn(app), overlayGranted(app), bridge);
            Intent intent = new Intent(app, OverlayRingService.class);
            if (want) {
                try {
                    app.startForegroundService(intent);
                } catch (Exception ignored) {
                    // Foreground starts from the background are
                    // restricted in some states; the next
                    // reconcile (Settings resume, Deep view)
                    // retries. Nothing is lost: the in-launcher
                    // menu always works.
                }
            } else {
                try {
                    app.stopService(intent);
                } catch (Exception ignored) {
                }
            }
            if (onPingDone != null) {
                new android.os.Handler(
                        android.os.Looper.getMainLooper()).post(onPingDone);
            }
        }, "overlay-ring-reconcile").start();
    }

    /** Deep-link to the overlay-permission screen for Control. */
    public static Intent overlaySettingsIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + c.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    // ---- service lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        serviceAlive = true;
        startForeground(NOTIF_ID, buildNotification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        source = new OverlayRingSource(this);
        watcher = new HoldWatchClient(this, new HoldWatchClient.Callback() {
            @Override public void onHold(float xPx, float yPx) {
                onHoldDetected(xPx, yPx);
            }

            @Override public void onEnded(String reason) {
                // The bridge died, refused, or errored: the
                // feature simply stops. The Settings row reports
                // it; the next reconcile restarts the watch when
                // the bridge is back.
                lastWatchEndReason = reason == null ? "" : reason;
                lastWatchEndAtMs = android.os.SystemClock.elapsedRealtime();
                OwnLog.log(OverlayRingService.this, "Hold-anywhere menu",
                        "Watch ended: " + reason);
                stopSelf();
            }
        });
        watcher.start();
        OwnLog.log(this, "Hold-anywhere menu", "Watch started (Deep view bridge)");
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
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
        hideOverlay();
        serviceAlive = false;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ---- the overlay ----

    private void onHoldDetected(float xPx, float yPx) {
        // No coordinates are logged or stored — only the fact
        // that a hold arrived, and when.
        lastHoldAtMs = android.os.SystemClock.elapsedRealtime();
        if (overlayRoot != null) return; // one ring at a time
        if (CircleMenuView.isAnyMenuOpen()) return; // the built-in menu owns it
        showOverlay(xPx, yPx);
    }

    private void showOverlay(float xPx, float yPx) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            // The window is the ring plus a margin: big enough
            // that a tap just outside the ring lands in the
            // window (and dismisses), small enough that the rest
            // of the screen stays the app underneath's.
            int reach = Ui.dp(this, RingMath.RING_RADIUS_DP + RingMath.ITEM_RADIUS_DP + 8);
            int half = reach + Ui.dp(this, 12);
            int size = half * 2;
            int left = (int) Math.max(0,
                    Math.min(Math.max(0, screenW - size), xPx - half));
            int top = (int) Math.max(0, Math.min(Math.max(0, screenH - size), yPx - half));
            final float centerX = xPx - left;
            final float centerY = yPx - top;

            OverlayRoot root = new OverlayRoot(this);
            root.setLayoutParams(new ViewGroup.LayoutParams(size, size));
            menu = new CircleMenuView(this);
            menu.setListener(new CircleMenuView.Listener() {
                @Override public void onChoose(int item) {
                    hideOverlay();
                    OverlayRingSource.openMainDestination(
                            OverlayRingService.this, item);
                }

                @Override public void onDismiss() {
                    hideOverlay();
                }
            });
            menu.setSource(source);
            root.addView(menu, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlayRoot = root;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.x = left;
            params.y = top;
            windowManager.addView(root, params);
            lastShowError = ""; // a good add clears a stale failure
            root.requestFocus();
            // After layout the view's own bounds are real, so the
            // ring clamps against the window — never the 0x0
            // first-open trap (see CircleMenuView.showAt).
            root.post(() -> {
                if (menu != null) menu.showAt(centerX, centerY);
            });
        } catch (Exception e) {
            // Overlay permission revoked mid-run, or the window
            // was rejected: drop the ring, keep watching. The
            // next reconcile stops the service if the permission
            // is really gone. The failure is logged and recorded
            // (v1.43) — a silently rejected window was
            // indistinguishable from a dead watch.
            String err = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (err.length() > 120) err = err.substring(0, 120);
            lastShowError = err;
            OwnLog.log(this, "Hold-anywhere menu",
                    "Overlay window failed: " + err);
            hideOverlay();
        }
    }

    private void hideOverlay() {
        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {
            }
        }
        if (menu != null) menu.close();
        overlayRoot = null;
        menu = null;
    }

    /** The overlay's root view: consumes Back (up one ring
     *  level, then dismiss) and dismisses on any touch outside
     *  the window (which still reaches the app underneath). */
    private final class OverlayRoot extends FrameLayout {
        OverlayRoot(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && menu != null) {
                    if (menu.goBack()) return true;
                    hideOverlay();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                hideOverlay();
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // ---- notification ----

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Hold-anywhere menu",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Hold-anywhere menu is on")
                .setContentText("Hold anywhere for a second to open the ring.")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
