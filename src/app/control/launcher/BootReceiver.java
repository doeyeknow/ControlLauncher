package app.control.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Boot hook for the bridge auto-restart watchdog (v1.16): after
 * a reboot, the bridge is always down (a shell process never
 * survives one), so this starts the watchdog, which restarts
 * the bridge by itself using the stored ADB pairing keys —
 * while wireless debugging stays on, with no user interaction.
 *
 * Gated on Deep view being enabled (Prefs.KEY_DEEP_VIEW_ON):
 * a phone that never ran Deep view gets no service, no
 * restart, nothing. The receiver does nothing but start the
 * service; all work happens there, on its worker thread.
 *
 * If the foreground-service start is refused in this state
 * (background-start restrictions vary by device and Android
 * version), the failure is silent by design — the unlock and
 * foreground kicks are the backstop, and Deep view's own
 * restart button always works. No toast, no prompt.
 *
 * Since v1.19 this receiver also reconciles the channel
 * subscription service: subscribed channels resume listening
 * after a reboot with no user action (reconcile is a no-op
 * when nothing is subscribed, and the walkie page's loop is
 * the backstop for the tuned channel if the start is
 * refused).
 *
 * Registered in the manifest for BOOT_COMPLETED, exported:
 * the broadcast comes from the system. This receiver is not
 * direct-boot aware, so it runs after the user unlocks and
 * credential-encrypted storage (prefs, keys) is available.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        if (BridgeWatchdogService.deepViewOn(app)) {
            try {
                app.startForegroundService(
                        new Intent(app, BridgeWatchdogService.class));
            } catch (Exception ignored) {
                // Refused in this state; the next kick retries.
            }
        }
        // Channel subscriptions resume on their own after a
        // reboot (a no-op when nothing is subscribed).
        SubscriptionService.reconcile(app);
        // The hold-anywhere ring, too (v1.43): the bridge is
        // down at boot, so this reconcile only records that —
        // but if the bridge is somehow already up, the ring
        // service starts without waiting for a Settings visit.
        // The watchdog's post-restart reconcile is the path
        // that normally brings it back.
        OverlayRingService.reconcile(app);
    }
}
