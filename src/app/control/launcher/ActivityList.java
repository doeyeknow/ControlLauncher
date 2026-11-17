package app.control.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The shaping rules for the app-activities screen (v1.22): turning
 * one app's declared activities into the sorted, labeled rows the
 * screen shows. Pure Java — no Android imports — so the harness
 * proves the exact code the activity runs.
 *
 * One declared activity becomes one row. The row's label is the
 * activity's OWN label when it declares one; otherwise the short
 * class name (the part after the last dot), because the platform
 * label lookup falls back to the application's label, which would
 * print the app's name on every row of an app whose activities
 * declare no labels of their own. An activity-alias is a row too:
 * it launches by its own (alias) component name, and the row names
 * the activity it points at. Rows sort by label, case-insensitively,
 * with the full class name breaking ties, so an app with many
 * activities reads alphabetically instead of in manifest order.
 */
public final class ActivityList {

    /** One declared activity, as the Android query hands it over:
     *  the full component name, the activity's own label (null when
     *  it declares none), whether the manifest exports it, and —
     *  for an activity-alias — the target activity's class name. */
    public static final class Raw {
        public final String name;
        public final String ownLabel;
        public final boolean exported;
        public final String targetActivity;

        public Raw(String name, String ownLabel, boolean exported,
                   String targetActivity) {
            this.name = name;
            this.ownLabel = ownLabel;
            this.exported = exported;
            this.targetActivity = targetActivity;
        }
    }

    /** One screen row: the full component name (what a launch
     *  targets), the display label, the exported flag, and — for
     *  aliases — the target's short name ("" otherwise). */
    public static final class Row {
        public final String className;
        public final String label;
        public final boolean exported;
        public final boolean alias;
        public final String targetShortName;

        Row(String className, String label, boolean exported,
            boolean alias, String targetShortName) {
            this.className = className;
            this.label = label;
            this.exported = exported;
            this.alias = alias;
            this.targetShortName = targetShortName;
        }
    }

    private ActivityList() {}

    /** The class name's last segment: "com.foo.Bar" -> "Bar".
     *  Inner classes keep their "$" part ("Outer$Inner"). A name
     *  with no dot, or a null/empty name, comes back as-is ("" for
     *  null), never throwing. */
    public static String shortName(String className) {
        if (className == null) return "";
        int dot = className.lastIndexOf('.');
        if (dot < 0 || dot == className.length() - 1) return className;
        return className.substring(dot + 1);
    }

    /** The row label: the activity's own label when it has a
     *  non-blank one, else the short class name. */
    public static String displayLabel(String ownLabel, String className) {
        if (ownLabel != null && ownLabel.trim().length() > 0) return ownLabel;
        return shortName(className);
    }

    /** Shape + sort one app's declared activities into rows. A null
     *  input or a null/empty component name yields no row for that
     *  entry — the screen never shows a row it cannot launch by
     *  name. The input list is never modified. */
    public static List<Row> build(List<Raw> raw) {
        List<Row> rows = new ArrayList<>();
        if (raw == null) return rows;
        for (Raw r : raw) {
            if (r == null || r.name == null || r.name.isEmpty()) continue;
            boolean alias = r.targetActivity != null && !r.targetActivity.isEmpty();
            rows.add(new Row(r.name, displayLabel(r.ownLabel, r.name),
                    r.exported, alias,
                    alias ? shortName(r.targetActivity) : ""));
        }
        Collections.sort(rows, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                int byLabel = String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
                if (byLabel != 0) return byLabel;
                return a.className.compareTo(b.className);
            }
        });
        return rows;
    }

    /** The count line under the header: "3 activities · 2 openable".
     *  Singular when the count is one; an empty list reports itself
     *  as no activities, which the screen shows as its empty state
     *  instead of this line. */
    public static String summary(List<Row> rows) {
        int total = rows == null ? 0 : rows.size();
        int openable = 0;
        if (rows != null) {
            for (Row r : rows) if (r.exported) openable++;
        }
        return total + (total == 1 ? " activity · " : " activities · ")
                + openable + " openable";
    }

    /** The bridge-launch refusal dialog body (v1.38): the full
     *  component name first, then the bridge's complete error
     *  words, untruncated — the old result toast cut long
     *  reasons off mid-sentence and disappeared, so the actual
     *  refusal was unreadable. A null or blank error says
     *  plainly that no reason was given, never a blank body. */
    public static String refusalDialogBody(String pkg, String className,
                                           String error) {
        String component = (pkg == null ? "" : pkg) + "/"
                + (className == null ? "" : className);
        String reason = error == null ? "" : error.trim();
        if (reason.isEmpty()) reason = "No reason was given.";
        return component + "\n\n" + reason;
    }
}
