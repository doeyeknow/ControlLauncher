package app.control.launcher;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Default-home (ROLE_HOME) detection and request.
 *
 * On API 29+ the RoleManager is the sanctioned path: the system shows
 * its own picker with Control as the candidate. On older versions
 * (26–28) there is no role API, so detection resolves the HOME intent
 * and the request opens the system's home settings.
 */
public final class HomeRole {

    private HomeRole() {}

    public static boolean isHeld(Context c) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = (RoleManager) c.getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return rm.isRoleHeld(RoleManager.ROLE_HOME);
            }
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = c.getPackageManager().resolveActivity(home,
                    PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && c.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception e) {
            return false;
        }
    }

    /** Ask for the home role. Result (API 29+) arrives via
     *  onActivityResult with the caller's request code; older versions
     *  just open the home-settings screen. */
    public static void request(Activity a, int requestCode) {
        if (isHeld(a)) {
            Toast.makeText(a, "Control is already your home app.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = (RoleManager) a.getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) {
                a.startActivityForResult(
                        rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                return;
            }
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(a, "Open Settings → Apps → Default apps → Home app",
                    Toast.LENGTH_LONG).show();
        }
    }
}
