package app.control.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Off-device verification for the v1.15 first-run setup core,
 * exercising the exact shipping pure-Java code:
 *
 * Legs:
 *   1. TOKENS: both UiTheme sets define every token (NAMES and
 *      all() agree, 15 each), every value is opaque, the dark
 *      set is byte-identical to the v1.3 constants it replaces,
 *      the light set's grounds are light and its ink is dark,
 *      and each set's accent ink contrasts with its accent.
 *   2. FLOW: setup shows exactly when the completed flag is
 *      unset; steps advance 0-1-2-3 and never past the last;
 *      only the theme step completes setup; Back never goes
 *      below step 0; restored steps clamp into range.
 *   3. ORDER: the import-supersedes-name rule — an imported
 *      profile's name wins over the typed name; without an
 *      import the typed name stands; nulls never throw.
 *
 * Run: compile UiTheme.java + SetupFlow.java + this file with
 * javac and run the main method. No Android classes, no network.
 */
public class SetupHarness {

    private static int checks;
    private static int failures;
    private static final List<String> failed = new ArrayList<>();

    public static void main(String[] args) {
        tokens();
        flow();
        order();
        System.out.println("SetupHarness: " + checks + " checks, "
                + failures + " failures");
        for (String f : failed) System.out.println("  FAIL: " + f);
        if (failures > 0) System.exit(1);
    }

    // ---- helpers ----

    private static void check(boolean cond, String name) {
        checks++;
        if (!cond) {
            failures++;
            failed.add(name);
        }
    }

    private static void eq(int a, int b, String name) {
        check(a == b, name + " (got " + a + ", want " + b + ")");
    }

    private static void eq(String a, String b, String name) {
        check(a != null && a.equals(b),
                name + " (got \"" + a + "\", want \"" + b + "\")");
    }

    /** Relative luminance, 0 (black) to 1 (white). */
    private static double luminance(int argb) {
        double r = ((argb >> 16) & 0xFF) / 255.0;
        double g = ((argb >> 8) & 0xFF) / 255.0;
        double b = (argb & 0xFF) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    // ---- legs ----

    private static void tokens() {
        eq(UiTheme.NAMES.length, 15, "token name count");
        int[] dark = UiTheme.DARK.all();
        int[] light = UiTheme.LIGHT.all();
        eq(dark.length, UiTheme.NAMES.length, "dark set defines every token");
        eq(light.length, UiTheme.NAMES.length, "light set defines every token");
        for (int i = 0; i < UiTheme.NAMES.length; i++) {
            String n = UiTheme.NAMES[i];
            check(((dark[i] >> 24) & 0xFF) == 0xFF, "dark " + n + " opaque");
            check(((light[i] >> 24) & 0xFF) == 0xFF, "light " + n + " opaque");
        }
        check(UiTheme.of(false) == UiTheme.DARK, "of(false) is DARK");
        check(UiTheme.of(true) == UiTheme.LIGHT, "of(true) is LIGHT");

        // The dark set is byte-identical to the v1.3 Ui constants.
        eq(UiTheme.DARK.bg, 0xFF101613, "dark BG == v1.3");
        eq(UiTheme.DARK.panel, 0xFF141A16, "dark PANEL == v1.3");
        eq(UiTheme.DARK.surface, 0xFF171E1A, "dark SURFACE == v1.3");
        eq(UiTheme.DARK.surface2, 0xFF1E2A21, "dark SURFACE2 == v1.3");
        eq(UiTheme.DARK.text, 0xFFE8EDE9, "dark TEXT == v1.3");
        eq(UiTheme.DARK.dim, 0xFF93A399, "dark DIM == v1.3");
        eq(UiTheme.DARK.accent, 0xFF4ADE80, "dark ACCENT == v1.3");
        eq(UiTheme.DARK.accentInk, 0xFF0C120E, "dark ACCENT_INK == v1.3");
        eq(UiTheme.DARK.line, 0xFF2A332D, "dark LINE == v1.3");
        eq(UiTheme.DARK.signal, 0xFFFBBF24, "dark SIGNAL == v1.3");
        eq(UiTheme.DARK.pine, 0xFF14532D, "dark PINE == v1.3");
        eq(UiTheme.DARK.pineDeep, 0xFF0C3520, "dark PINE_DEEP == v1.3");
        eq(UiTheme.DARK.pttBg, 0xFF1A2B20, "dark PTT_BG == v1.3");
        eq(UiTheme.DARK.pttLine, 0xFF31463A, "dark PTT_LINE == v1.3");
        eq(UiTheme.DARK.dotLine, 0xFF33503F, "dark DOT_LINE == v1.3");

        // The light set inverts the grounds and keeps the jobs.
        check(luminance(UiTheme.LIGHT.bg) > 0.85, "light BG is light");
        check(luminance(UiTheme.DARK.bg) < 0.15, "dark BG is dark");
        check(luminance(UiTheme.LIGHT.text) < 0.15, "light TEXT is dark ink");
        check(luminance(UiTheme.DARK.text) > 0.85, "dark TEXT is light ink");
        check(luminance(UiTheme.LIGHT.surface) > luminance(UiTheme.LIGHT.bg),
                "light SURFACE sits above BG");
        check(luminance(UiTheme.LIGHT.dim) > luminance(UiTheme.LIGHT.text),
                "light DIM is softer than TEXT");
        check(luminance(UiTheme.DARK.dim) < luminance(UiTheme.DARK.text),
                "dark DIM is softer than TEXT");
        check(Math.abs(luminance(UiTheme.LIGHT.accent)
                - luminance(UiTheme.LIGHT.accentInk)) > 0.5,
                "light accent ink contrasts with accent");
        check(Math.abs(luminance(UiTheme.DARK.accent)
                - luminance(UiTheme.DARK.accentInk)) > 0.5,
                "dark accent ink contrasts with accent");
        eq(UiTheme.LIGHT.pine, UiTheme.DARK.pine, "PINE shared across sets");
        eq(UiTheme.LIGHT.pineDeep, UiTheme.DARK.pineDeep, "PINE_DEEP shared");
        check(UiTheme.LIGHT.accent != UiTheme.DARK.accent,
                "light accent is its own deeper green");
    }

    private static void flow() {
        check(SetupFlow.shouldShowSetup(false), "fresh install shows setup");
        check(!SetupFlow.shouldShowSetup(true), "completed flag suppresses setup");
        eq(SetupFlow.STEP_COUNT, 4, "four steps");
        eq(SetupFlow.nextStep(SetupFlow.STEP_NAME), SetupFlow.STEP_PERMISSIONS,
                "name -> permissions");
        eq(SetupFlow.nextStep(SetupFlow.STEP_PERMISSIONS), SetupFlow.STEP_IMPORT,
                "permissions -> import");
        eq(SetupFlow.nextStep(SetupFlow.STEP_IMPORT), SetupFlow.STEP_THEME,
                "import -> theme");
        eq(SetupFlow.nextStep(SetupFlow.STEP_THEME), SetupFlow.STEP_THEME,
                "theme never advances past itself");
        eq(SetupFlow.nextStep(-1), SetupFlow.STEP_NAME, "nextStep clamps low");
        check(!SetupFlow.completesSetup(SetupFlow.STEP_NAME), "name doesn't complete");
        check(!SetupFlow.completesSetup(SetupFlow.STEP_IMPORT), "import doesn't complete");
        check(SetupFlow.completesSetup(SetupFlow.STEP_THEME), "theme completes setup");
        eq(SetupFlow.prevStep(SetupFlow.STEP_THEME), SetupFlow.STEP_IMPORT,
                "back from theme -> import");
        eq(SetupFlow.prevStep(SetupFlow.STEP_NAME), SetupFlow.STEP_NAME,
                "back from name stays (caller leaves)");
        eq(SetupFlow.clampStep(-5), SetupFlow.STEP_NAME, "clamp low");
        eq(SetupFlow.clampStep(99), SetupFlow.STEP_THEME, "clamp high");
        eq(SetupFlow.clampStep(2), 2, "clamp keeps in-range steps");
    }

    private static void order() {
        eq(SetupFlow.effectiveDisplayName("Ada", "Bob", true), "Bob",
                "imported name wins over typed name");
        eq(SetupFlow.effectiveDisplayName("Ada", "Bob", false), "Ada",
                "typed name stands without import");
        eq(SetupFlow.effectiveDisplayName("", "Bob", true), "Bob",
                "imported name wins over empty typed name");
        eq(SetupFlow.effectiveDisplayName("Ada", "", true), "",
                "imported empty name still supersedes");
        eq(SetupFlow.effectiveDisplayName("", "", false), "",
                "no name anywhere stays empty");
        eq(SetupFlow.effectiveDisplayName(null, null, true), "",
                "null imported name is safe");
        eq(SetupFlow.effectiveDisplayName(null, "Bob", false), "",
                "null typed name is safe");
    }
}
