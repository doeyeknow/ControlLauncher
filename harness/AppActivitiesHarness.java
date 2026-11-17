package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Off-device verification for the v1.22 app-activities screen,
 * exercising the exact shipping rules (ActivityList):
 *
 * Legs:
 *   1. SHORT_NAME: the last class-name segment — dotted names,
 *      a bare name, an inner class keeping its "$" part, a
 *      trailing dot, null and empty (never throwing).
 *   2. DISPLAY_LABEL: the activity's own label wins when
 *      non-blank; null, empty, and whitespace-only labels fall
 *      back to the short class name.
 *   3. BUILD: null/empty component names produce no row, null
 *      entries and a null list are safe, aliases carry the
 *      target's short name, non-aliases carry "", and the input
 *      list is never modified.
 *   4. SORT: rows order by label case-insensitively, with the
 *      full class name breaking label ties.
 *   5. SUMMARY: the count line — singular/plural, zero openable,
 *      all openable, empty and null lists.
 *   6. REFUSAL_BODY (v1.38): the launch-refusal dialog body —
 *      the full component name first, then the bridge's
 *      complete error words, untruncated; a null or blank
 *      error says plainly that no reason was given.
 *
 * The Android wiring the harness cannot see (the PackageManager
 * query, the own-label rule, the explicit-intent launch and its
 * SecurityException toast) is verified by the build and by the
 * on-device checklist in BUILD_NOTES; this harness pins every
 * rule that wiring depends on.
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/AppActivitiesHarness.java
 *   java -cp harness/classes app.control.launcher.AppActivitiesHarness
 * No Android classes, no network.
 */
public final class AppActivitiesHarness {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        shortName();
        displayLabel();
        build();
        sort();
        summary();
        refusalBody();
        System.out.println("AppActivitiesHarness: " + (checks - failures)
                + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void shortName() {
        check("dotted -> last segment",
                "Bar".equals(ActivityList.shortName("com.foo.Bar")));
        check("deep package -> last segment",
                "MainActivity".equals(ActivityList.shortName("a.b.c.d.MainActivity")));
        check("bare name unchanged",
                "Bar".equals(ActivityList.shortName("Bar")));
        check("inner class keeps $ part",
                "Outer$Inner".equals(ActivityList.shortName("com.foo.Outer$Inner")));
        check("trailing dot returns as-is",
                "com.foo.".equals(ActivityList.shortName("com.foo.")));
        check("empty stays empty",
                "".equals(ActivityList.shortName("")));
        check("null -> empty, no throw",
                "".equals(ActivityList.shortName(null)));
    }

    private static void displayLabel() {
        check("own label wins",
                "Settings".equals(ActivityList.displayLabel("Settings", "com.foo.Bar")));
        check("null label -> short name",
                "Bar".equals(ActivityList.displayLabel(null, "com.foo.Bar")));
        check("empty label -> short name",
                "Bar".equals(ActivityList.displayLabel("", "com.foo.Bar")));
        check("blank label -> short name",
                "Bar".equals(ActivityList.displayLabel("   ", "com.foo.Bar")));
        check("label with spaces kept verbatim",
                "  My Screen  ".equals(ActivityList.displayLabel("  My Screen  ", "com.foo.Bar")));
    }

    private static void build() {
        List<ActivityList.Raw> raw = new ArrayList<>(Arrays.asList(
                new ActivityList.Raw("com.foo.Bar", "Bar Screen", true, null),
                new ActivityList.Raw("com.foo.Alias", null, true, "com.foo.Bar"),
                new ActivityList.Raw("com.foo.Hidden", null, false, null),
                new ActivityList.Raw("", "No name", true, null),
                new ActivityList.Raw(null, "Null name", true, null),
                null,
                new ActivityList.Raw("com.foo.Alias2", "Alias Two", false, "com.other.Target$Deep")));
        List<ActivityList.Row> rows = ActivityList.build(raw);
        check("nameless + null entries dropped", rows.size() == 4);
        check("input list unmodified", raw.size() == 7);

        ActivityList.Row alias = find(rows, "com.foo.Alias");
        check("alias row exists", alias != null);
        check("alias flagged", alias != null && alias.alias);
        check("alias target short name", alias != null && "Bar".equals(alias.targetShortName));
        check("alias label falls back to short name",
                alias != null && "Alias".equals(alias.label));

        ActivityList.Row plain = find(rows, "com.foo.Bar");
        check("plain row not an alias", plain != null && !plain.alias);
        check("plain row empty target", plain != null && "".equals(plain.targetShortName));
        check("plain row keeps own label",
                plain != null && "Bar Screen".equals(plain.label));
        check("exported flag carried", plain != null && plain.exported);

        ActivityList.Row hidden = find(rows, "com.foo.Hidden");
        check("non-exported flag carried", hidden != null && !hidden.exported);

        ActivityList.Row alias2 = find(rows, "com.foo.Alias2");
        check("alias target inner class short name",
                alias2 != null && "Target$Deep".equals(alias2.targetShortName));

        check("null input -> empty", ActivityList.build(null).isEmpty());
        check("empty input -> empty",
                ActivityList.build(new ArrayList<ActivityList.Raw>()).isEmpty());
    }

    private static void sort() {
        List<ActivityList.Raw> raw = Arrays.asList(
                new ActivityList.Raw("com.foo.zeta", "Zulu", true, null),
                new ActivityList.Raw("com.foo.alpha", "alpha", true, null),
                new ActivityList.Raw("com.foo.Bravo", "BRAVO", true, null),
                new ActivityList.Raw("com.foo.b2", "bravo", true, null),
                new ActivityList.Raw("com.foo.b1", "bravo", true, null));
        List<ActivityList.Row> rows = ActivityList.build(raw);
        check("sort size", rows.size() == 5);
        check("case-insensitive first",
                rows.get(0).className.equals("com.foo.alpha"));
        check("case-insensitive second",
                rows.get(1).className.equals("com.foo.Bravo"));
        check("tie broken by class name",
                rows.get(2).className.equals("com.foo.b1")
                && rows.get(3).className.equals("com.foo.b2"));
        check("case-insensitive last",
                rows.get(4).className.equals("com.foo.zeta"));
    }

    private static void summary() {
        check("empty -> 0 activities, 0 openable",
                "0 activities · 0 openable".equals(ActivityList.summary(
                        new ArrayList<ActivityList.Row>())));
        check("null -> 0 activities, 0 openable",
                "0 activities · 0 openable".equals(ActivityList.summary(null)));
        List<ActivityList.Row> one = ActivityList.build(Arrays.asList(
                new ActivityList.Raw("com.foo.A", null, true, null)));
        check("singular activity",
                "1 activity · 1 openable".equals(ActivityList.summary(one)));
        List<ActivityList.Row> mixed = ActivityList.build(Arrays.asList(
                new ActivityList.Raw("com.foo.A", null, true, null),
                new ActivityList.Raw("com.foo.B", null, false, null),
                new ActivityList.Raw("com.foo.C", null, false, null)));
        check("plural with some openable",
                "3 activities · 1 openable".equals(ActivityList.summary(mixed)));
        List<ActivityList.Row> none = ActivityList.build(Arrays.asList(
                new ActivityList.Raw("com.foo.A", null, false, null),
                new ActivityList.Raw("com.foo.B", null, false, null)));
        check("none openable",
                "2 activities · 0 openable".equals(ActivityList.summary(none)));
    }

    private static void refusalBody() {
        String error = "Android did not confirm the launch: Error: Activity "
                + "not started, unable to resolve Intent { cmp=com.foo/.Bar }";
        String body = ActivityList.refusalDialogBody("com.foo", "com.foo.Bar", error);
        check("refusal body names the full component",
                body.startsWith("com.foo/com.foo.Bar\n\n"));
        check("refusal body carries the complete error words",
                body.endsWith(error));
        check("refusal body does not truncate the error",
                body.length() == "com.foo/com.foo.Bar\n\n".length() + error.length());
        String blank = ActivityList.refusalDialogBody("com.foo", "com.foo.Bar", "   ");
        check("blank error says no reason was given",
                blank.endsWith("No reason was given."));
        String missing = ActivityList.refusalDialogBody("com.foo", "com.foo.Bar", null);
        check("null error says no reason was given",
                missing.endsWith("No reason was given."));
        check("null error still names the component",
                missing.startsWith("com.foo/com.foo.Bar\n\n"));
    }

    private static ActivityList.Row find(List<ActivityList.Row> rows, String className) {
        for (ActivityList.Row r : rows) {
            if (r.className.equals(className)) return r;
        }
        return null;
    }
}
