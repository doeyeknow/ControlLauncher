package app.control.launcher;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Shared UI factories + the app's design tokens.
 *
 * v1.3 theme: the tokens ARE the Control Launcher Space's phone
 * mockup palette, lifted from the space's stylesheet (see
 * BUILD_NOTES for the token-by-token mapping). The mockup's display
 * font (Archivo) maps to the system sans in bold; its data font
 * (IBM Plex Mono) maps to the system monospace — fonts are not
 * bundled with the app.
 *
 * v1.15 themes: the token VALUES live in {@link UiTheme} (dark
 * and light sets). The fields below are mutable copies of the
 * active set — every call site keeps reading {@code Ui.BG} and
 * friends, and {@link #refreshTheme} swaps the set underneath.
 * {@link ThemedActivity} applies the theme in every onCreate
 * and re-applies it on resume, so a theme change re-themes the
 * whole app as screens are (re)created. Views that draw with the
 * tokens at draw time (the ring, the tuner) pick up a new set
 * live; views that build once pick it up on their next build.
 */
public final class Ui {
    // ---- space phone tokens (active set; see UiTheme) ----
    public static int BG = UiTheme.DARK.bg;
    public static int PANEL = UiTheme.DARK.panel;
    public static int SURFACE = UiTheme.DARK.surface;
    public static int SURFACE2 = UiTheme.DARK.surface2;
    public static int TEXT = UiTheme.DARK.text;
    public static int DIM = UiTheme.DARK.dim;
    public static int ACCENT = UiTheme.DARK.accent;
    public static int ACCENT_INK = UiTheme.DARK.accentInk;
    public static int LINE = UiTheme.DARK.line;
    public static int SIGNAL = UiTheme.DARK.signal;
    public static int PINE = UiTheme.DARK.pine;
    public static int PINE_DEEP = UiTheme.DARK.pineDeep;
    public static int PTT_BG = UiTheme.DARK.pttBg;
    public static int PTT_LINE = UiTheme.DARK.pttLine;
    public static int DOT_LINE = UiTheme.DARK.dotLine;

    /** Which set is currently copied into the fields above.
     *  Null until the first refresh (process start). */
    private static Boolean appliedLight;

    private Ui() {}

    /** True when the light theme is the stored choice. Dark is
     *  the default: existing installs upgrading to v1.15 keep
     *  the dark look they already have. */
    public static boolean isLightTheme(Context c) {
        return Prefs.get(c).getBoolean(Prefs.KEY_THEME_LIGHT, false);
    }

    /** Copies the stored theme's token set into the fields.
     *  Returns true when the applied set actually changed, so a
     *  resumed activity knows whether to rebuild itself. */
    public static boolean refreshTheme(Context c) {
        boolean light = isLightTheme(c);
        UiTheme t = UiTheme.of(light);
        boolean changed = appliedLight == null || appliedLight != light;
        BG = t.bg;
        PANEL = t.panel;
        SURFACE = t.surface;
        SURFACE2 = t.surface2;
        TEXT = t.text;
        DIM = t.dim;
        ACCENT = t.accent;
        ACCENT_INK = t.accentInk;
        LINE = t.line;
        SIGNAL = t.signal;
        PINE = t.pine;
        PINE_DEEP = t.pineDeep;
        PTT_BG = t.pttBg;
        PTT_LINE = t.pttLine;
        DOT_LINE = t.dotLine;
        appliedLight = light;
        return changed;
    }

    /** Picks the window style (window background, status and
     *  navigation bar colors) for the stored theme. Must run
     *  before the activity's content is created; see
     *  {@link ThemedActivity}. */
    public static void applyThemeStyle(Activity a) {
        a.setTheme(isLightTheme(a) ? R.style.AppTheme_Light : R.style.AppTheme);
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    /** Rounded-rect background in the mockup's radius language:
     *  buttons 9dp, inputs ~12dp, cards 14–16dp, pills 999dp. */
    public static GradientDrawable rounded(Context c, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundedStroke(Context c, int fill, float radiusDp,
                                                 int strokeColor) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(Math.max(1, dp(c, 1)), strokeColor);
        return d;
    }

    /** Data/labels typeface (IBM Plex Mono role). */
    public static void mono(TextView t) {
        t.setTypeface(Typeface.MONOSPACE);
    }

    /** Display typeface (Archivo 800 role): system sans, bold,
     *  tight letterspacing for the clock and big numerals. */
    public static void display(TextView t) {
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setLetterSpacing(-0.02f);
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(BG);
        return l;
    }

    /** A mockup-style card: raised surface, 1px line, 14dp radius. */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(roundedStroke(c, SURFACE, 14, LINE));
        return l;
    }

    public static LinearLayout.LayoutParams wrap(int left, int top, int right, int bottom, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        return p;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Kicker/section label: mono, uppercase, wide tracking, accent. */
    public static TextView section(Context c, String s) {
        TextView t = text(c, s.toUpperCase(), 12, ACCENT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setLetterSpacing(0.16f);
        t.setPadding(0, dp(c, 22), 0, dp(c, 6));
        return t;
    }

    public static TextView body(Context c, String s) {
        TextView t = text(c, s, 15, DIM);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    public static Button button(Context c, String label, boolean accent) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setMinHeight(dp(c, 52));
        if (accent) {
            b.setBackground(rounded(c, ACCENT, 9));
            b.setTextColor(ACCENT_INK);
            b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        } else {
            b.setBackground(roundedStroke(c, SURFACE2, 9, LINE));
            b.setTextColor(TEXT);
        }
        return b;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1))));
        return v;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView sectionLabel(Context c, String s) {
        return section(c, s);
    }

    public static Button smallButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(c, 40));
        b.setBackground(roundedStroke(c, SURFACE, 9, LINE));
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(c, 4), dp(c, 6), dp(c, 4));
        b.setLayoutParams(p);
        return b;
    }

    public static android.widget.EditText input(Context c, String hint) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint);
        e.setTextSize(16);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setBackground(roundedStroke(c, SURFACE, 12, LINE));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setSingleLine(true);
        return e;
    }

    public static TextView screenTitle(Activity a, String s) {
        TextView t = text(a, s, 26, TEXT);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        t.setPadding(0, dp(a, 8), 0, dp(a, 4));
        t.setGravity(Gravity.START);
        return t;
    }
}
