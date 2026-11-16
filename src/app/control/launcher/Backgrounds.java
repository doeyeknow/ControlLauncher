package app.control.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import org.json.JSONObject;

import java.io.File;

/**
 * The launcher background (v1.2): a solid color, a built-in dark
 * gradient (rendered in code — no image assets), or a photo the
 * user picked.
 *
 * Storage follows the profile rules: the choice {type, value,
 * scrim} lives in profile.json and travels with it for colors and
 * gradients. A photo is DEVICE-LOCAL (the opposite rule from the
 * identity key, which must travel): the profile records type
 * "photo" + scrim only, the image itself sits in app-private
 * storage and never enters the export.
 * A profile imported on a device with no photo set falls back to
 * the default color, and {@link #summary} says so.
 */
public final class Backgrounds {

    public static final String TYPE_COLOR = "color";
    public static final String TYPE_GRADIENT = "gradient";
    public static final String TYPE_PHOTO = "photo";

    public static final String DEFAULT_COLOR = "#0E1116"; // == Ui.BG

    private static final String PHOTO_NAME = "background-photo.jpg";

    /** {name, hex} — the first entry is the current default. */
    public static final String[][] COLORS = {
            {"Default", "#0E1116"},
            {"Midnight", "#0B1220"},
            {"Slate", "#141C26"},
            {"Deep pine", "#0C1F1A"},
            {"Plum", "#1B1030"},
            {"Wine", "#220F18"},
            {"Espresso", "#201612"},
            {"Charcoal", "#17181C"},
    };

    /** {name, top hex, bottom hex} — dark, tasteful, code-rendered. */
    public static final String[][] GRADIENTS = {
            {"Dusk", "#0B1220", "#33224D"},
            {"Pine shade", "#071510", "#14532D"},
            {"Ember", "#160B06", "#4A1F0E"},
            {"Deep water", "#06121C", "#0E3A4D"},
            {"Plum wine", "#150818", "#3D1230"},
            {"Desert night", "#141005", "#4A3418"},
    };

    private Backgrounds() {}

    public static String type(Context c) {
        return Profile.background(c).optString("type", TYPE_COLOR);
    }

    public static String value(Context c) {
        return Profile.background(c).optString("value", DEFAULT_COLOR);
    }

    /** Scrim percent, 0–70. */
    public static int scrim(Context c) {
        return Math.max(0, Math.min(70,
                Profile.background(c).optInt("scrim", 0)));
    }

    public static void save(Context c, String type, String value) {
        Profile.saveBackground(c, type, value, scrim(c));
    }

    public static void saveScrim(Context c, int scrim) {
        Profile.saveBackground(c, type(c), value(c), scrim);
    }

    public static File photoFile(Context c) {
        return new File(c.getFilesDir(), PHOTO_NAME);
    }

    public static boolean photoPresent(Context c) {
        File f = photoFile(c);
        return f.exists() && f.length() > 0;
    }

    /** What actually renders: an imported "photo" choice with no
     *  local photo falls back to the default color. */
    public static String effectiveType(Context c) {
        String t = type(c);
        if (TYPE_PHOTO.equals(t) && !photoPresent(c)) return TYPE_COLOR;
        return t;
    }

    public static int colorOf(String hex, int fallback) {
        try {
            return Color.parseColor(hex);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String colorName(String hex) {
        for (String[] row : COLORS) {
            if (row[1].equalsIgnoreCase(hex)) return row[0];
        }
        return hex;
    }

    /** @return {top, bottom} colors for a gradient name, or null. */
    public static int[] gradientColors(String name) {
        for (String[] row : GRADIENTS) {
            if (row[0].equals(name)) {
                return new int[]{colorOf(row[1], Ui.BG), colorOf(row[2], Ui.BG)};
            }
        }
        return null;
    }

    /** One-line summary for the Settings rows. */
    public static String summary(Context c) {
        String t = type(c);
        if (TYPE_GRADIENT.equals(t)) return "Gradient · " + value(c);
        if (TYPE_PHOTO.equals(t)) {
            return photoPresent(c)
                    ? "Photo" + scrimNote(c)
                    : "Photo — not on this device (showing default color)";
        }
        return "Solid · " + colorName(value(c)) + scrimNote(c);
    }

    private static String scrimNote(Context c) {
        int s = scrim(c);
        return s > 0 ? " · dimmed " + s + "%" : "";
    }

    /** Decode the stored photo sampled to at most ~1.5x the target. */
    public static Bitmap loadPhoto(Context c, int targetW, int targetH) {
        File f = photoFile(c);
        if (!f.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > targetW * 2
                    || bounds.outHeight / sample > targetH * 2) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    /** The raw profile object (for the Background screen's state). */
    public static JSONObject raw(Context c) {
        return Profile.background(c);
    }
}
