package app.control.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Off-device verification for the v1.9 persisted app list,
 * exercising the exact shipping pure-Java code (AppListCodec):
 *
 * Legs:
 *   1. ROUND-TRIP: serialize/parse identity, including labels
 *      with quotes, backslashes, newlines, tabs, unicode, and
 *      control characters; empty lists; key order and unknown
 *      keys; duplicate packages.
 *   2. CORRUPTION: every malformed shape throws instead of
 *      yielding a half-read list — the store rescans on any of
 *      these, so a corrupt cache can never show wrong data.
 *   3. DIFF: added / removed / relabeled detection between a
 *      cached list and a fresh scan, order-insensitivity, and
 *      the empty-to-full / full-to-empty edges.
 *
 * What this harness cannot prove: the PackageManager scan
 * itself, the file write/read on a real device, or the
 * package-change receiver firing. Those need Adam's phone.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac --release 17 -encoding UTF-8 -sourcepath src -d harness/classes \
 *     harness/AppListCodecHarness.java
 *   java -cp harness/classes app.control.launcher.AppListCodecHarness
 */
public final class AppListCodecHarness {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  -- " + detail);
        }
    }

    private static AppListCodec.Entry e(String pkg, String label) {
        return new AppListCodec.Entry(pkg, label);
    }

    private static boolean parses(String text) {
        try {
            AppListCodec.parse(text);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public static void main(String[] args) {
        // ---- 1. Round-trip ----
        List<AppListCodec.Entry> apps = Arrays.asList(
                e("com.example.mail", "Mail"),
                e("com.example.photos", "Photos"),
                e("org.example", "Weird \"quoted\" \\ label\nwith\ttabs"));
        String json = AppListCodec.serialize(apps);
        check("codec: round-trip preserves entries",
                AppListCodec.parse(json).equals(apps), json);
        check("codec: empty list round-trips",
                AppListCodec.serialize(new ArrayList<>()).equals("[]")
                        && AppListCodec.parse("[]").isEmpty(), "");
        check("codec: unicode + control characters round-trip",
                AppListCodec.parse(AppListCodec.serialize(
                        Arrays.asList(e("com.x", "caf\u00e9 \u0001\u001f"))))
                        .equals(Arrays.asList(e("com.x", "caf\u00e9 \u0001\u001f"))), "");
        check("codec: whitespace tolerated",
                AppListCodec.parse("  [  { \"p\" : \"a.b\" , \"l\" : \"B\" } ]  ")
                        .equals(Arrays.asList(e("a.b", "B"))), "");
        check("codec: key order swapped",
                AppListCodec.parse("[{\"l\":\"B\",\"p\":\"a.b\"}]")
                        .equals(Arrays.asList(e("a.b", "B"))), "");
        check("codec: unknown keys ignored (scalar, object, array)",
                AppListCodec.parse("[{\"p\":\"a.b\",\"l\":\"B\",\"v\":3,"
                        + "\"o\":{\"x\":[1,2]},\"a\":[true,null]}]")
                        .equals(Arrays.asList(e("a.b", "B"))), "");
        check("codec: duplicate package keeps the first label",
                AppListCodec.parse("[{\"p\":\"a.b\",\"l\":\"First\"},"
                        + "{\"p\":\"a.b\",\"l\":\"Second\"}]")
                        .equals(Arrays.asList(e("a.b", "First"))), "");
        check("codec: escaped slash and unicode escape parse",
                AppListCodec.parse("[{\"p\":\"a\\/b\",\"l\":\"\\u0041\"}]")
                        .equals(Arrays.asList(e("a/b", "A"))), "");
        check("codec: null label falls back to the package",
                e("com.x", null).label.equals("com.x"), "");
        boolean emptyPkgRejected = false;
        try {
            e("", "X");
        } catch (IllegalArgumentException ex) {
            emptyPkgRejected = true;
        }
        check("codec: empty package rejected", emptyPkgRejected, "");

        // ---- 2. Corruption ----
        String[] corrupt = {
            "",
            "not json",
            "[}",
            "[{\"p\":\"a\"}]",
            "[{\"l\":\"B\"}]",
            "[{\"p\":\"\",\"l\":\"B\"}]",
            "[{\"p\":1,\"l\":\"B\"}]",
            "[{\"p\":\"a\",\"l\":null}]",
            "[] trailing",
            "[{\"p\":\"a\",\"l\":\"unterminated}]",
            "[{\"p\":\"a\",\"l\":\"bad \\q escape\"}]",
            "[{\"p\":\"a\",\"l\":\"bad \\u12 escape\"}]",
            "[{\"p\":\"a\",\"l\":\"raw\nnewline\"}]",
            "[{\"p\":\"a\",\"l\":\"B\"},]",
            "{\"p\":\"a\",\"l\":\"B\"}",
            "[{\"p\":\"a\",\"l\":\"B\"}",
            "[{\"p\":\"a\",\"l\":\"B\"},]",
            "[{\"p\":\"a\",\"l\":\"B\"}]]",
            "[,]",
            "[{\"p\":\"a\" \"l\":\"B\"}]",
        };
        boolean allRejected = true;
        String firstAccepted = null;
        for (String c : corrupt) {
            if (parses(c)) {
                allRejected = false;
                firstAccepted = c;
                break;
            }
        }
        check("corrupt: all " + corrupt.length + " malformed shapes rejected",
                allRejected, "accepted: " + firstAccepted);
        check("corrupt: null input rejected", !parses(null), "");

        // ---- 3. Diff ----
        List<AppListCodec.Entry> oldList = Arrays.asList(
                e("com.a", "A"), e("com.b", "B"), e("com.c", "C"));
        AppListCodec.Diff same = AppListCodec.diff(oldList,
                Arrays.asList(e("com.c", "C"), e("com.a", "A"), e("com.b", "B")));
        check("diff: reorder is not a change", same.isEmpty(), "");
        check("diff: same() agrees with an empty diff",
                AppListCodec.same(oldList, new ArrayList<>(oldList)), "");
        List<AppListCodec.Entry> fresh = Arrays.asList(
                e("com.a", "A"), e("com.b", "Bee"), e("com.d", "D"));
        AppListCodec.Diff d = AppListCodec.diff(oldList, fresh);
        check("diff: one added",
                d.added.equals(Arrays.asList(e("com.d", "D"))), d.added.toString());
        check("diff: one removed",
                d.removed.equals(Arrays.asList(e("com.c", "C"))), d.removed.toString());
        check("diff: one relabeled (fresh label kept)",
                d.relabeled.equals(Arrays.asList(e("com.b", "Bee"))), d.relabeled.toString());
        check("diff: changed lists are not same()",
                !AppListCodec.same(oldList, fresh), "");
        AppListCodec.Diff fromEmpty = AppListCodec.diff(new ArrayList<>(), fresh);
        check("diff: empty cache yields all-added",
                fromEmpty.added.size() == 3 && fromEmpty.removed.isEmpty()
                        && fromEmpty.relabeled.isEmpty(), "");
        AppListCodec.Diff toEmpty = AppListCodec.diff(oldList, new ArrayList<>());
        check("diff: empty scan yields all-removed",
                toEmpty.removed.size() == 3 && toEmpty.added.isEmpty()
                        && toEmpty.relabeled.isEmpty(), "");
        AppListCodec.Diff bothEmpty = AppListCodec.diff(
                new ArrayList<>(), new ArrayList<>());
        check("diff: empty vs empty is unchanged", bothEmpty.isEmpty(), "");
        List<AppListCodec.Entry> onlyRelabel = Arrays.asList(
                e("com.a", "A"), e("com.b", "B2"), e("com.c", "C"));
        AppListCodec.Diff rel = AppListCodec.diff(oldList, onlyRelabel);
        check("diff: relabel alone changes nothing else",
                rel.added.isEmpty() && rel.removed.isEmpty()
                        && rel.relabeled.size() == 1, "");

        System.out.println("----");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
