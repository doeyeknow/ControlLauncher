package app.control.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queries the apps that can appear in a launcher (MAIN + LAUNCHER).
 *
 * The list itself comes from {@link AppListStore} (v1.9): one
 * PackageManager scan persisted to a private JSON cache, shared
 * by every consumer — the sheet, the ring, the pickers, Deep
 * controls. Icons are separate: {@link #iconFor} loads them
 * lazily, one app at a time, through a small in-memory cache, so
 * neither the scan nor the cache file ever touches artwork (icon
 * bitmaps are never persisted — size + staleness).
 */
public final class InstalledApps {

    public static final class Entry {
        public final String pkg;
        public final String label;

        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** In-memory icon cache: package -> the drawable PM returned.
     *  Callers get a fresh drawable from the shared constant state
     *  (same bitmap, independent bounds), so one icon can sit in
     *  a row and on the ring at once without fighting over bounds.
     *  Bounded: eldest entries drop past MAX_ICONS. */
    private static final int MAX_ICONS = 128;
    private static final Map<String, Drawable> ICONS =
            new LinkedHashMap<String, Drawable>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, Drawable> eldest) {
                    return size() > MAX_ICONS;
                }
            };

    private InstalledApps() {}

    /** The cached installed-app list (see AppListStore). */
    public static List<Entry> all(Context c) {
        return AppListStore.cached(c);
    }

    public static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    public static Entry one(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return new Entry(pkg, label != null ? label.toString() : pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * An app's icon, lazily loaded and cached in memory. Never
     * null: an unknown package falls back to the default activity
     * icon, so a row never shows a hole where artwork belongs.
     */
    public static Drawable iconFor(Context c, String pkg) {
        Drawable cached;
        synchronized (ICONS) {
            cached = ICONS.get(pkg);
        }
        if (cached == null) {
            try {
                cached = c.getPackageManager().getApplicationIcon(pkg);
            } catch (Exception e) {
                cached = null;
            }
            if (cached == null) {
                cached = c.getPackageManager().getDefaultActivityIcon();
            }
            synchronized (ICONS) {
                ICONS.put(pkg, cached);
            }
        }
        Drawable.ConstantState state = cached.getConstantState();
        return state != null ? state.newDrawable() : cached;
    }

    /** Drops one package's cached icon (it changed or went away). */
    public static void evictIcon(String pkg) {
        if (pkg == null) return;
        synchronized (ICONS) {
            ICONS.remove(pkg);
        }
    }

    public static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }
}
