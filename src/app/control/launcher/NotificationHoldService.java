package app.control.launcher;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Set;

/**
 * Holds notifications from user-muted apps: when one posts, we record it
 * in the held list and cancel it out of the shade. Nothing is deleted —
 * the held list in the app is the record, and unmuting restores normal
 * delivery for future notifications.
 */
public class NotificationHoldService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        OwnLog.log(this, "Notifications", "Notification listener connected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (pkg == null) return;

        Notification n = sbn.getNotification();
        String title = "";
        String text = "";
        if (n != null && n.extras != null) {
            Bundle e = n.extras;
            CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence x = e.getCharSequence(Notification.EXTRA_TEXT);
            if (t == null) t = e.getCharSequence(Notification.EXTRA_TITLE_BIG);
            if (x == null) x = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (x == null) {
                CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) x = lines[0];
            }
            title = t == null ? "" : t.toString();
            text = x == null ? "" : x.toString();
        }

        boolean own = pkg.equals(getPackageName());
        boolean quietAll = Prefs.get(this).getBoolean(Prefs.KEY_QUIET_ALL, false);
        Set<String> muted = Prefs.getSet(this, Prefs.KEY_MUTED_APPS);
        boolean held = !own && (quietAll || muted.contains(pkg));

        // Module 9: every notification the listener can see gets a
        // timeline entry, clearly marked SHOWN or HELD. The content is
        // only what Android put in the notification extras.
        NotificationFeedStore.record(this, sbn.getKey(), sbn.getPostTime(),
                pkg, title, text, held);

        if (!held) return;
        HeldStore.add(this, pkg, title, text);
        OwnLog.log(this, "Notifications", "Held a notification from " + pkg);
        cancelNotification(sbn.getKey());
    }
}
