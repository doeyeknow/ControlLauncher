package app.control.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The single source for the installed-app list (v1.9): one
 * PackageManager scan, persisted to a private JSON file, shared
 * by the Apps sheet, the Apps sub-ring, the pickers, and Deep
 * controls. Nobody scans twice.
 *
 *   - {@link #cached(Context)} returns the list synchronously:
 *     the in-memory snapshot when present, else the cache file,
 *     else one scan (first run, or a corrupt/missing file — the
 *     file is rewritten either way).
 *   - {@link #refreshAsync(Context, Runnable)} re-scans on a
 *     background thread and, only when the scan differs from the
 *     cached list (added, removed, or relabeled), rewrites the
 *     file, swaps the snapshot, and notifies listeners on the
 *     main thread. Refreshes are single-flight: concurrent calls
 *     coalesce into one scan.
 *   - {@link #notePackageChanged(Context)} is the package-change
 *     receiver's entry point — an install, removal, update, or
 *     enable/disable refreshes the cache, so uninstalled apps
 *     disappear and new ones appear without a stale entry left
 *     showing.
 *
 * The cache holds packages + labels only. Icons are never
 * persisted (size + staleness); see InstalledApps.iconFor.
 */
public final class AppListStore {

    static final String FILE_NAME = "app-list-cache.json";

    /** Notified on the main thread when the list's contents change. */
    public interface Listener {
        void onAppListChanged();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    /** In-memory snapshot, or null until first loaded. Guarded by LOCK. */
    private static List<InstalledApps.Entry> snapshot;

    private AppListStore() {}

    public static void addListener(Listener l) {
        if (l != null) LISTENERS.addIfAbsent(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    /** The cached list, synchronously. Scans once when there is no
     *  usable cache (first run or corrupt file) and writes it. */
    public static List<InstalledApps.Entry> cached(Context c) {
        synchronized (LOCK) {
            if (snapshot != null) return new ArrayList<>(snapshot);
            List<InstalledApps.Entry> fromFile = readFile(c);
            if (fromFile != null) {
                snapshot = fromFile;
                return new ArrayList<>(snapshot);
            }
            List<InstalledApps.Entry> scanned = scan(c);
            snapshot = scanned;
            writeFile(c, scanned);
            return new ArrayList<>(snapshot);
        }
    }

    /** Background refresh; {@code onChanged} runs on the main
     *  thread only when the contents actually changed. */
    public static void refreshAsync(Context c, Runnable onChanged) {
        Context app = c.getApplicationContext();
        if (!REFRESHING.compareAndSet(false, true)) return;
        new Thread(() -> {
            boolean changed = false;
            try {
                List<InstalledApps.Entry> fresh = scan(app);
                synchronized (LOCK) {
                    List<InstalledApps.Entry> current = snapshot != null
                            ? snapshot : readFile(app);
                    if (current == null
                            || !AppListCodec.same(toCodec(current), toCodec(fresh))) {
                        snapshot = fresh;
                        writeFile(app, fresh);
                        changed = true;
                    }
                }
            } catch (Exception ignored) {
                // A failed refresh leaves the cache as it was; the
                // next open retries. Never surface a scan failure
                // as an empty list.
            } finally {
                REFRESHING.set(false);
            }
            if (changed) {
                MAIN.post(() -> {
                    for (Listener l : LISTENERS) {
                        try {
                            l.onAppListChanged();
                        } catch (Exception ignored) {
                        }
                    }
                    if (onChanged != null) onChanged.run();
                });
            }
        }, "app-list-refresh").start();
    }

    /** Package install/remove/update/enable/disable: refresh the
     *  cache. Visible UI hears about it through the listeners. */
    public static void notePackageChanged(Context c) {
        refreshAsync(c, null);
    }

    /** The one PackageManager scan in the app. Package + label
     *  only — no icon loading (see InstalledApps.iconFor). */
    static List<InstalledApps.Entry> scan(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
        List<InstalledApps.Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName())) continue; // hide ourselves from the drawer
            if (!seen.add(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            out.add(new InstalledApps.Entry(pkg,
                    label != null ? label.toString() : pkg));
        }
        Collator col = Collator.getInstance();
        Collections.sort(out, (a, b) -> col.compare(a.label, b.label));
        return out;
    }

    private static List<AppListCodec.Entry> toCodec(List<InstalledApps.Entry> in) {
        List<AppListCodec.Entry> out = new ArrayList<>(in.size());
        for (InstalledApps.Entry e : in) out.add(new AppListCodec.Entry(e.pkg, e.label));
        return out;
    }

    private static List<InstalledApps.Entry> readFile(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            if (!f.exists()) return null;
            byte[] raw = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < raw.length) {
                    int n = in.read(raw, off, raw.length - off);
                    if (n < 0) break;
                    off += n;
                }
            }
            List<AppListCodec.Entry> parsed = AppListCodec.parse(
                    new String(raw, StandardCharsets.UTF_8));
            List<InstalledApps.Entry> out = new ArrayList<>(parsed.size());
            for (AppListCodec.Entry e : parsed) {
                out.add(new InstalledApps.Entry(e.pkg, e.label));
            }
            return out;
        } catch (Exception e) {
            return null; // missing or corrupt: the caller scans fresh
        }
    }

    private static void writeFile(Context c, List<InstalledApps.Entry> entries) {
        try {
            byte[] raw = AppListCodec.serialize(toCodec(entries))
                    .getBytes(StandardCharsets.UTF_8);
            File dir = c.getFilesDir();
            File tmp = new File(dir, FILE_NAME + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(raw);
            }
            File dst = new File(dir, FILE_NAME);
            if (!tmp.renameTo(dst)) {
                // Rename failed (shouldn't on the same directory):
                // fall back to a direct overwrite.
                try (FileOutputStream out = new FileOutputStream(dst)) {
                    out.write(raw);
                }
                tmp.delete();
            }
        } catch (Exception ignored) {
            // A cache that can't be written is not fatal — the
            // in-memory snapshot still serves this run, and the
            // next run scans again.
        }
    }
}
