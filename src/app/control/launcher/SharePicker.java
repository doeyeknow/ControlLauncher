package app.control.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The shaping rules for the app-share picker (v1.25): turning
 * the installed apps into the sorted rows the share screen
 * shows. Pure Java — no Android imports — so the harness
 * proves the exact code the activity runs.
 *
 * Only launchable apps appear: an app with no launchable
 * activity has no APK worth sharing (a system component's
 * package helps nobody on the other phone), and an app whose
 * APK file cannot be read is dropped rather than shown as a
 * row that fails when tapped. Control itself comes first —
 * sharing the launcher is the common case — then the rest
 * alphabetically by label, case-insensitively, with the
 * package name breaking label ties. A blank label falls back
 * to the package name so a row never shows empty.
 */
public final class SharePicker {

    /** One installed app, as the Android query hands it over. */
    public static final class Raw {
        public final String pkg;
        public final String label;
        public final boolean launchable;
        public final long apkSizeBytes;

        public Raw(String pkg, String label, boolean launchable, long apkSizeBytes) {
            this.pkg = pkg;
            this.label = label;
            this.launchable = launchable;
            this.apkSizeBytes = apkSizeBytes;
        }
    }

    /** One picker row: package, display label, APK size. */
    public static final class Row {
        public final String pkg;
        public final String label;
        public final long apkSizeBytes;

        Row(String pkg, String label, long apkSizeBytes) {
            this.pkg = pkg;
            this.label = label;
            this.apkSizeBytes = apkSizeBytes;
        }
    }

    private SharePicker() {}

    /**
     * Prepends Control itself to the raw feed (v1.26.1). The
     * cached app list deliberately excludes the app's own
     * package — {@link AppListStore#scan} hides Control from
     * the drawer — so the share feed never receives it, and
     * the launcher-first sort in {@link #shape} had nothing
     * to put first. The share screen builds the self entry
     * from its own ApplicationInfo (the same readable-file
     * size rule as every other row) and runs it through this
     * seam, so the harness proves the exact rule the activity
     * runs. The self entry is launchable; {@link #shape}
     * applies its usual rules to it — an unreadable own APK
     * (size <= 0) drops like any other row, and a duplicate
     * own entry in the feed collapses to this first one. The
     * input list is never modified; a null input yields a
     * list holding only the self entry.
     */
    public static List<Raw> prependSelf(List<Raw> in,
            String ownPackage, String ownLabel, long ownSize) {
        List<Raw> out = new ArrayList<>();
        out.add(new Raw(ownPackage, ownLabel, true, ownSize));
        if (in != null) out.addAll(in);
        return out;
    }

    /**
     * Shapes raw query results into picker rows. Null entries,
     * blank packages, non-launchable apps, and unreadable APKs
     * (size <= 0) are dropped; duplicate packages keep the
     * first entry. The input list is never modified.
     */
    public static List<Row> shape(List<Raw> in, String ownPackage) {
        List<Row> rows = new ArrayList<>();
        if (in == null) return rows;
        Set<String> seen = new HashSet<>();
        for (Raw r : in) {
            if (r == null) continue;
            if (r.pkg == null || r.pkg.trim().isEmpty()) continue;
            if (!r.launchable) continue;
            if (r.apkSizeBytes <= 0) continue;
            if (!seen.add(r.pkg)) continue;
            String label = (r.label == null || r.label.trim().isEmpty())
                    ? r.pkg : r.label;
            rows.add(new Row(r.pkg, label, r.apkSizeBytes));
        }
        final String own = ownPackage != null ? ownPackage : "";
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                boolean aOwn = own.equals(a.pkg);
                boolean bOwn = own.equals(b.pkg);
                if (aOwn != bOwn) return aOwn ? -1 : 1;
                int byLabel = a.label.compareToIgnoreCase(b.label);
                if (byLabel != 0) return byLabel;
                return a.pkg.compareTo(b.pkg);
            }
        });
        return rows;
    }
}
