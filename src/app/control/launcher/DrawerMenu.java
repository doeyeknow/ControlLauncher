package app.control.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * The app-drawer (Apps sheet) long-press menu (v1.41). Pure Java —
 * no Android classes — so the harness exercises the exact shipping
 * menu: which items appear, in what order, with what labels, and
 * the one exclusion.
 *
 * Adam's order (2026-11-17): "I want to be able to uninstall an
 * app from the app drawer by long pressing" + "Add uninstall to
 * that menu." The menu already existed (v1.22, the Connections
 * pattern): a favorite toggle plus "View activities". Uninstall
 * joins it as the last item.
 *
 * Uninstall is the standard system flow — the sheet fires an
 * ACTION_DELETE intent and Android itself confirms and removes.
 * This class decides only whether the item appears:
 *
 *   - Every package gets it EXCEPT Control itself
 *     ({@link ProtectedOverride#isOwnPackage}): the launcher
 *     must not offer to remove itself from its own drawer —
 *     the same lockout reasoning as the bridge refuse-list.
 *     System apps DO get the item: Android's own uninstaller
 *     says what it will and will not do, and the app never
 *     claims otherwise.
 *   - A null or empty package gets no Uninstall item: fail
 *     closed. (Unreachable in production — drawer entries
 *     always carry a package — it is a safety net.)
 *
 * The favorites label follows the starred state, exactly as the
 * sheet computed it before this class existed; the order is
 * favorites, activities, uninstall.
 */
public final class DrawerMenu {

    /** Action keys the sheet dispatches on. */
    public static final String ACTION_FAVORITE = "favorite";
    public static final String ACTION_ACTIVITIES = "activities";
    public static final String ACTION_UNINSTALL = "uninstall";

    /** One menu row: the label shown and the action key. */
    public static final class Item {
        public final String label;
        public final String action;

        public Item(String label, String action) {
            this.label = label;
            this.action = action;
        }
    }

    private DrawerMenu() {}

    /** The menu for one drawer cell, in display order. */
    public static List<Item> items(String pkg, boolean starred) {
        List<Item> out = new ArrayList<>();
        out.add(new Item(starred ? "Remove from favorites" : "Add to favorites",
                ACTION_FAVORITE));
        out.add(new Item("View activities", ACTION_ACTIVITIES));
        if (offersUninstall(pkg)) {
            out.add(new Item("Uninstall", ACTION_UNINSTALL));
        }
        return out;
    }

    /** Whether the Uninstall item appears for this package. */
    public static boolean offersUninstall(String pkg) {
        return pkg != null && !pkg.isEmpty()
                && !ProtectedOverride.isOwnPackage(pkg);
    }
}
