package app.control.launcher;

/**
 * The Deep view hub's row structure (v1.39): which icon each
 * group row carries. Pure Java — the harness pins the table.
 * The hub mirrors the main Settings window's row pattern
 * (24dp {@link PageGlyphs} icon, title, chevron, 56dp rows in
 * a rounded card), and like Settings it reuses glyphs where
 * the set has no closer match — Settings reuses BELL, SHIELD,
 * ACTIVITY, CHAT, SETTINGS, and INFO the same way, so no new
 * icon assets were introduced.
 *
 * Indices are the addGroup build order in
 * DeepControlActivity, which is the hub order. An index
 * outside the table reads INFO — a row never loses its icon.
 */
public final class DeepHub {

    /** The hub lists exactly these groups, in this order. */
    public static final int GROUP_COUNT = 18;

    private static final int[] GLYPHS = {
            PageGlyphs.SHIELD,       // 0  Protected packages
            PageGlyphs.APPS,         // 1  Target app
            PageGlyphs.ACTIVITY,     // 2  Running processes
            PageGlyphs.LOCK,         // 3  Permissions
            PageGlyphs.INFO,         // 4  AppOps
            PageGlyphs.SETTINGS,     // 5  App state
            PageGlyphs.CLOCK,        // 6  Background policy
            PageGlyphs.CONNECTIONS,  // 7  Private DNS
            PageGlyphs.SETTINGS,     // 8  Launcher system setting
            PageGlyphs.APPS,         // 9  Apps
            PageGlyphs.IMAGE,        // 10 Display
            PageGlyphs.ACTIVITY,     // 11 Input
            PageGlyphs.CAMERA,       // 12 Capture
            PageGlyphs.CLOCK,        // 13 Power & scheduling
            PageGlyphs.SETTINGS,     // 14 System config
            PageGlyphs.INFO,         // 15 Inspect
            PageGlyphs.CLOCK,        // 16 Change history
            PageGlyphs.INFO,         // 17 Result
    };

    private DeepHub() {}

    /** The glyph for one hub row, by build-order index. */
    public static int glyphForGroup(int index) {
        if (index < 0 || index >= GLYPHS.length) return PageGlyphs.INFO;
        return GLYPHS[index];
    }
}
