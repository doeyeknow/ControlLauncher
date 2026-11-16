package app.control.launcher;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Device-admin powers (v1.1), kept deliberately small:
 *
 *  - lockNow() — a real device lock (screen off, phone lock engages),
 *    used by the SMS LOCK command and the Settings Lock button when
 *    admin is active. Without admin, LOCK falls back to Control's own
 *    UI lock, and the copy says so.
 *  - wipeData() — full device erase, reachable ONLY through the
 *    two-step SMS WIPE DEVICE command (arm, then CONFIRM WIPE DEVICE
 *    from the same controller number). There is no in-app wipe button.
 *
 * Device admin is not root and has nothing to do with the shell
 * bridge; the UI copy says exactly that. Every lock/wipe attempt and
 * its outcome is written to the shared audit log with its origin.
 */
public final class AdminControl {

    public static final String EXPLANATION =
            "Device admin lets Control lock this phone when you text LOCK, "
            + "and erase the phone only if you send the two-step WIPE DEVICE "
            + "text command (arm, then confirm). It is not root, it cannot "
            + "read your messages or files, and it does not change Deep view "
            + "or the shell bridge. You can turn it off in Settings at any time.";

    private AdminControl() {}

    public static ComponentName component(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    private static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public static boolean isActive(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            return d != null && d.isAdminActive(component(c));
        } catch (Exception e) {
            return false;
        }
    }

    public static Intent activationIntent(Context c) {
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, EXPLANATION);
        return i;
    }

    public static void deactivate(Context c) {
        try {
            DevicePolicyManager d = dpm(c);
            if (d != null) d.removeActiveAdmin(component(c));
        } catch (Exception ignored) {
        }
    }

    /** Real device lock via admin. Attempts and outcomes are audited. */
    public static boolean lockNow(Context c, String origin, String sender) {
        if (!isActive(c)) {
            SmsControl.logDetailed(c, origin, sender, "LOCK",
                    "Device admin", "", "Refused — device admin is not active");
            return false;
        }
        try {
            dpm(c).lockNow();
            SmsControl.logDetailed(c, origin, sender, "LOCK",
                    "Device admin", "", "Device locked (lockNow)");
            OwnLog.log(c, "Device admin", "Phone locked via lockNow, origin " + origin);
            return true;
        } catch (Exception e) {
            SmsControl.logDetailed(c, origin, sender, "LOCK",
                    "Device admin", "", "lockNow failed: " + e.getMessage());
            return false;
        }
    }

    /** Full device erase via admin. Only the armed, confirmed SMS
     *  WIPE DEVICE path calls this. Attempts are audited BEFORE the
     *  call — a successful wipe erases the log along with everything
     *  else, which is expected and stated in the UI copy. */
    public static boolean wipeDevice(Context c, String origin, String sender) {
        if (!isActive(c)) {
            SmsControl.logDetailed(c, origin, sender, "WIPE DEVICE",
                    "Device admin", "", "Refused — device admin is not active");
            return false;
        }
        SmsControl.logDetailed(c, origin, sender, "WIPE DEVICE",
                "Device admin", "", "wipeData called — device erase started");
        OwnLog.log(c, "Device admin", "WIPE DEVICE executed, origin " + origin);
        try {
            dpm(c).wipeData(DevicePolicyManager.WIPE_EXTERNAL_STORAGE);
            return true; // practically unreachable — the device wipes
        } catch (Exception e) {
            SmsControl.logDetailed(c, origin, sender, "WIPE DEVICE",
                    "Device admin", "", "wipeData failed: " + e.getMessage());
            return false;
        }
    }
}
