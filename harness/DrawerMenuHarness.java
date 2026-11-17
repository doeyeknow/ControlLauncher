package app.control.launcher;

import java.util.List;

/**
 * Off-device verification for the v1.41 app-drawer long-press
 * menu, exercising the exact shipping rules (DrawerMenu):
 *
 * Legs:
 *   1. NORMAL_UNSTARRED: a regular app that is not a favorite
 *      gets all three items, in order, with the exact labels
 *      and action keys — "Add to favorites", "View activities",
 *      "Uninstall".
 *   2. NORMAL_STARRED: a favorited app gets "Remove from
 *      favorites" first; activities and Uninstall unchanged.
 *   3. OWN_PACKAGE: Control itself — starred or not — gets
 *      only the favorite toggle and "View activities". No
 *      Uninstall item, ever. The decision is wired to
 *      ProtectedOverride's own-package constant, not a second
 *      copy of the string.
 *   4. OFFERS: offersUninstall fails closed on null and empty,
 *      refuses Control itself, and offers for everything else
 *      — including system packages, whose fate Android's own
 *      uninstaller decides.
 *   5. FRESH_LIST: every call returns a new list; mutating one
 *      result never affects the next.
 *
 * The Android wiring the harness cannot see (the PopupMenu, the
 * ACTION_DELETE intent, the PackageChangeReceiver refresh) is
 * verified by the build and by the on-device checklist in
 * BUILD_NOTES; this harness pins every rule that wiring depends
 * on.
 *
 * Run:
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/DrawerMenuHarness.java
 *   java -cp harness/classes app.control.launcher.DrawerMenuHarness
 * No Android classes, no network.
 */
public final class DrawerMenuHarness {

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        normalUnstarred();
        normalStarred();
        ownPackage();
        offers();
        freshList();
        System.out.println("DrawerMenuHarness: " + (checks - failures)
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

    private static void normalUnstarred() {
        List<DrawerMenu.Item> items = DrawerMenu.items("com.example.app", false);
        check("unstarred: three items", items.size() == 3);
        check("unstarred: first label",
                "Add to favorites".equals(items.get(0).label));
        check("unstarred: first action",
                DrawerMenu.ACTION_FAVORITE.equals(items.get(0).action));
        check("unstarred: second label",
                "View activities".equals(items.get(1).label));
        check("unstarred: second action",
                DrawerMenu.ACTION_ACTIVITIES.equals(items.get(1).action));
        check("unstarred: third label",
                "Uninstall".equals(items.get(2).label));
        check("unstarred: third action",
                DrawerMenu.ACTION_UNINSTALL.equals(items.get(2).action));
    }

    private static void normalStarred() {
        List<DrawerMenu.Item> items = DrawerMenu.items("com.example.app", true);
        check("starred: three items", items.size() == 3);
        check("starred: first label",
                "Remove from favorites".equals(items.get(0).label));
        check("starred: first action still favorite",
                DrawerMenu.ACTION_FAVORITE.equals(items.get(0).action));
        check("starred: activities unchanged",
                "View activities".equals(items.get(1).label)
                        && DrawerMenu.ACTION_ACTIVITIES.equals(items.get(1).action));
        check("starred: uninstall still offered",
                "Uninstall".equals(items.get(2).label)
                        && DrawerMenu.ACTION_UNINSTALL.equals(items.get(2).action));
    }

    private static void ownPackage() {
        List<DrawerMenu.Item> unstarred =
                DrawerMenu.items("app.control.launcher", false);
        check("own unstarred: two items", unstarred.size() == 2);
        check("own unstarred: no uninstall",
                unstarred.stream().noneMatch(
                        it -> DrawerMenu.ACTION_UNINSTALL.equals(it.action)));
        check("own unstarred: favorites + activities kept",
                "Add to favorites".equals(unstarred.get(0).label)
                        && "View activities".equals(unstarred.get(1).label));
        List<DrawerMenu.Item> starred =
                DrawerMenu.items(ProtectedOverride.OWN_PACKAGE, true);
        check("own starred: two items", starred.size() == 2);
        check("own starred: no uninstall",
                starred.stream().noneMatch(
                        it -> DrawerMenu.ACTION_UNINSTALL.equals(it.action)));
        check("own starred: remove-favorites label kept",
                "Remove from favorites".equals(starred.get(0).label));
    }

    private static void offers() {
        check("null -> no uninstall", !DrawerMenu.offersUninstall(null));
        check("empty -> no uninstall", !DrawerMenu.offersUninstall(""));
        check("own package -> no uninstall",
                !DrawerMenu.offersUninstall("app.control.launcher"));
        check("own constant -> no uninstall",
                !DrawerMenu.offersUninstall(ProtectedOverride.OWN_PACKAGE));
        check("regular app -> uninstall",
                DrawerMenu.offersUninstall("com.example.app"));
        check("system app -> uninstall (Android decides)",
                DrawerMenu.offersUninstall("com.android.settings"));
        check("package installer -> uninstall (Android decides)",
                DrawerMenu.offersUninstall("com.google.android.packageinstaller"));
    }

    private static void freshList() {
        List<DrawerMenu.Item> first = DrawerMenu.items("com.example.app", false);
        first.clear();
        List<DrawerMenu.Item> second = DrawerMenu.items("com.example.app", false);
        check("mutating one result leaves the next intact", second.size() == 3);
    }
}
