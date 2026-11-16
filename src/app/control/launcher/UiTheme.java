package app.control.launcher;

/**
 * The app's design tokens, in two sets: dark (the v1.3 Space-like
 * palette, unchanged) and light (new in v1.15, mirroring the dark
 * set token for token — same names, same structure, light values,
 * radii untouched).
 *
 * Pure Java: no Android imports, so the setup harness can assert
 * both sets define every token. {@link Ui} copies the active set
 * into its (mutable) statics; every screen reads the tokens
 * through Ui, so switching sets re-themes the whole app.
 *
 * Light-set values were picked for contrast on light grounds:
 * the accent is a deeper green (white ink on it), the dim text
 * is a darker green-gray, and the amber signal is darkened, so
 * every token keeps its dark-set job on a light ground.
 */
public final class UiTheme {

    /** Token names, in the order {@link #all()} returns them. */
    public static final String[] NAMES = {
        "BG", "PANEL", "SURFACE", "SURFACE2", "TEXT", "DIM",
        "ACCENT", "ACCENT_INK", "LINE", "SIGNAL", "PINE", "PINE_DEEP",
        "PTT_BG", "PTT_LINE", "DOT_LINE"
    };

    public final int bg;
    public final int panel;
    public final int surface;
    public final int surface2;
    public final int text;
    public final int dim;
    public final int accent;
    public final int accentInk;
    public final int line;
    public final int signal;
    public final int pine;
    public final int pineDeep;
    public final int pttBg;
    public final int pttLine;
    public final int dotLine;

    private UiTheme(int bg, int panel, int surface, int surface2,
                    int text, int dim, int accent, int accentInk,
                    int line, int signal, int pine, int pineDeep,
                    int pttBg, int pttLine, int dotLine) {
        this.bg = bg;
        this.panel = panel;
        this.surface = surface;
        this.surface2 = surface2;
        this.text = text;
        this.dim = dim;
        this.accent = accent;
        this.accentInk = accentInk;
        this.line = line;
        this.signal = signal;
        this.pine = pine;
        this.pineDeep = pineDeep;
        this.pttBg = pttBg;
        this.pttLine = pttLine;
        this.dotLine = dotLine;
    }

    /** The v1.3 dark set, byte-identical to the old Ui constants. */
    public static final UiTheme DARK = new UiTheme(
            0xFF101613, 0xFF141A16, 0xFF171E1A, 0xFF1E2A21,
            0xFFE8EDE9, 0xFF93A399, 0xFF4ADE80, 0xFF0C120E,
            0xFF2A332D, 0xFFFBBF24, 0xFF14532D, 0xFF0C3520,
            0xFF1A2B20, 0xFF31463A, 0xFF33503F);

    /** The v1.15 light set: same tokens, light grounds. */
    public static final UiTheme LIGHT = new UiTheme(
            0xFFF2F5F1, 0xFFE9EFE8, 0xFFFFFFFF, 0xFFE3EAE2,
            0xFF101613, 0xFF5A6B5E, 0xFF1E7A45, 0xFFFFFFFF,
            0xFFC9D4C9, 0xFFB45309, 0xFF14532D, 0xFF0C3520,
            0xFFDCE9DC, 0xFF9DB8A4, 0xFF8FA795);

    public static UiTheme of(boolean light) {
        return light ? LIGHT : DARK;
    }

    /** Every token value, in NAMES order. */
    public int[] all() {
        return new int[]{bg, panel, surface, surface2, text, dim,
                accent, accentInk, line, signal, pine, pineDeep,
                pttBg, pttLine, dotLine};
    }
}
