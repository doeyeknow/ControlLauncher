package app.control.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Package-change invalidation for the cached app list (v1.9):
 * an install, removal, update, or enable/disable drops the
 * changed package's cached icon and refreshes the persisted
 * list, so the Apps sheet and the Apps sub-ring never show a
 * stale entry. The refresh itself runs on AppListStore's
 * background thread; this receiver only kicks it.
 *
 * Registered in the manifest for ACTION_PACKAGE_ADDED /
 * REMOVED / CHANGED / REPLACED with the "package" data scheme.
 * The REMOVED half of a replace (EXTRA_REPLACING) is skipped —
 * the REPLACED broadcast that follows it does the refresh.
 */
public class PackageChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            return;
        }
        Uri data = intent.getData();
        if (data != null) {
            InstalledApps.evictIcon(data.getSchemeSpecificPart());
        }
        AppListStore.notePackageChanged(context.getApplicationContext());
    }
}
