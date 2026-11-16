package app.control.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Background (v1.2): pick the launcher's backdrop — a solid color,
 * a built-in dark gradient, or a photo — plus a dim (scrim) slider
 * so text stays readable. The preview pane at the top renders the
 * real background layer with a clock and a favorites row over it,
 * live as you adjust.
 *
 * Colors and gradients travel with the profile. A photo does NOT:
 * it is copied into app-private storage on this device only, and
 * the profile records just the choice + dim (the device-local rule;
 * the identity key is the one thing that always travels). Imported
 * on a phone with no photo, it falls back to the default color.
 */
public class BackgroundActivity extends ThemedActivity {

    @Override
    protected boolean recreateOnThemeChange() {
        // Theme change: a rebuild would lose the in-progress photo pick; the new theme
        // applies the next time this screen opens.
        return false;
    }

    private static final int REQ_PICK_PHOTO = 7401;

    private BackgroundView preview;
    private TextView previewClock;
    private SeekBar scrimBar;
    private TextView scrimLabel;
    private LinearLayout colorGrid;
    private LinearLayout gradientGrid;
    private TextView photoState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(Ui.screenTitle(this, "Background"));

        // ---- live preview: the real layer + clock + favorite row ----
        FrameLayout frame = new FrameLayout(this);
        preview = new BackgroundView(this);
        frame.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout overlay = Ui.column(this);
        overlay.setPadding(pad, Ui.dp(this, 18), pad, Ui.dp(this, 14));
        previewClock = Ui.text(this, "", 40, Ui.TEXT);
        overlay.addView(previewClock);
        overlay.addView(Ui.text(this,
                new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(new Date()),
                14, Ui.DIM));
        LinearLayout favRow = Ui.row(this);
        int[] glyphs = {PageGlyphs.HOME, PageGlyphs.BROWSER, PageGlyphs.WALKIE, PageGlyphs.APPS};
        String[] names = {"Home", "Browser", "Walkie", "Apps"};
        for (int i = 0; i < glyphs.length; i++) {
            LinearLayout cell = Ui.column(this);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(0x33171C24);
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(glyphBitmap(glyphs[i]));
            int sz = Ui.dp(this, 26);
            cell.addView(iv, new LinearLayout.LayoutParams(sz, sz));
            TextView lab = Ui.text(this, names[i], 10, Ui.TEXT);
            lab.setGravity(Gravity.CENTER);
            cell.addView(lab);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(this, 3), Ui.dp(this, 10), Ui.dp(this, 3), 0);
            favRow.addView(cell, lp);
        }
        overlay.addView(favRow);
        frame.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190)));

        // ---- dim slider ----
        root.addView(Ui.sectionLabel(this, "Dim (keeps text readable)"));
        scrimLabel = Ui.body(this, "");
        root.addView(scrimLabel);
        scrimBar = new SeekBar(this);
        scrimBar.setMax(70);
        scrimBar.setProgress(Backgrounds.scrim(this));
        scrimBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                Backgrounds.saveScrim(BackgroundActivity.this, progress);
                updateScrimLabel();
                preview.refresh();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}

            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(scrimBar);
        updateScrimLabel();

        // ---- solid colors ----
        root.addView(Ui.sectionLabel(this, "Solid color"));
        colorGrid = Ui.column(this);
        root.addView(colorGrid);

        // ---- gradients ----
        root.addView(Ui.sectionLabel(this, "Gradients"));
        gradientGrid = Ui.column(this);
        root.addView(gradientGrid);

        // ---- photo ----
        root.addView(Ui.sectionLabel(this, "Photo"));
        photoState = Ui.body(this, "");
        root.addView(photoState);
        Button pick = Ui.button(this, "Pick a photo", false);
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            startActivityForResult(i, REQ_PICK_PHOTO);
        });
        root.addView(pick, Ui.wrap(0, 8, 0, 0, this));
        Button remove = Ui.button(this, "Remove photo", false);
        remove.setOnClickListener(v -> {
            try {
                Backgrounds.photoFile(this).delete();
            } catch (Exception ignored) {
            }
            if (Backgrounds.TYPE_PHOTO.equals(Backgrounds.type(this))) {
                Backgrounds.save(this, Backgrounds.TYPE_COLOR, Backgrounds.DEFAULT_COLOR);
            }
            refreshAll();
        });
        root.addView(remove, Ui.wrap(0, 8, 0, 0, this));
        root.addView(Ui.body(this,
                "Colors and gradients travel with your profile. A photo "
                + "stays on this device — the profile remembers the choice "
                + "and the dim, not the image. On a new phone, a photo "
                + "choice falls back to the default color until you pick "
                + "one there."), Ui.wrap(0, 10, 0, 0, this));

        refreshAll();
    }

    private Bitmap glyphBitmap(int glyph) {
        int sz = Ui.dp(this, 26);
        Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setColor(Ui.TEXT);
        paint.setStrokeWidth(sz * 0.09f);
        PageGlyphs.draw(canvas, glyph, sz / 2f, sz / 2f, sz * 0.92f, paint);
        return bmp;
    }

    private void updateScrimLabel() {
        scrimLabel.setText("Dim: " + Backgrounds.scrim(this) + "%");
    }

    private void refreshAll() {
        previewClock.setText(
                new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date()));
        buildColorGrid();
        buildGradientGrid();
        photoState.setText(Backgrounds.photoPresent(this)
                ? "A photo is set on this device."
                : "No photo on this device.");
        preview.refresh();
    }

    private void buildColorGrid() {
        colorGrid.removeAllViews();
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        String current = Backgrounds.TYPE_COLOR.equals(Backgrounds.type(this))
                ? Backgrounds.value(this) : "";
        for (String[] row : Backgrounds.COLORS) {
            grid.addView(swatchCell(
                    circleDrawable(Backgrounds.colorOf(row[1], Ui.BG),
                            row[1].equalsIgnoreCase(current)),
                    row[0],
                    () -> {
                        Backgrounds.save(this, Backgrounds.TYPE_COLOR, row[1]);
                        refreshAll();
                    }));
        }
        colorGrid.addView(grid);
    }

    private void buildGradientGrid() {
        gradientGrid.removeAllViews();
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        String current = Backgrounds.TYPE_GRADIENT.equals(Backgrounds.type(this))
                ? Backgrounds.value(this) : "";
        for (String[] row : Backgrounds.GRADIENTS) {
            GradientDrawable d = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Backgrounds.colorOf(row[1], Ui.BG),
                            Backgrounds.colorOf(row[2], Ui.BG)});
            d.setCornerRadius(Ui.dp(this, 8));
            if (row[0].equals(current)) {
                d.setStroke(Ui.dp(this, 2), Ui.ACCENT);
            }
            grid.addView(swatchCell(d, row[0], () -> {
                Backgrounds.save(this, Backgrounds.TYPE_GRADIENT, row[0]);
                refreshAll();
            }));
        }
        gradientGrid.addView(grid);
    }

    private GradientDrawable circleDrawable(int color, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Ui.dp(this, selected ? 3 : 1),
                selected ? Ui.ACCENT : Ui.LINE);
        return d;
    }

    private View swatchCell(android.graphics.drawable.Drawable swatch,
                            String name, Runnable onPick) {
        LinearLayout cell = Ui.column(this);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        View v = new View(this);
        v.setBackground(swatch);
        int sz = Ui.dp(this, 44);
        cell.addView(v, new LinearLayout.LayoutParams(sz, sz));
        TextView lab = Ui.text(this, name, 10, Ui.DIM);
        lab.setGravity(Gravity.CENTER);
        cell.addView(lab);
        cell.setOnClickListener(x -> onPick.run());
        return cell;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_PHOTO || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "Copying photo…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            boolean ok = copyPhoto(uri);
            runOnUiThread(() -> {
                if (ok) {
                    Backgrounds.save(this, Backgrounds.TYPE_PHOTO, "");
                    Toast.makeText(this, "Photo set.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "That image couldn't be read.",
                            Toast.LENGTH_LONG).show();
                }
                refreshAll();
            });
        }, "bg-photo").start();
    }

    /** Copy the picked image into app-private storage, downscaled to
     *  at most 1600px on the long edge, as a JPEG. */
    private boolean copyPhoto(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return false;
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) return false;
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            if (max > 1600) {
                float scale = 1600f / max;
                bmp = Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * scale),
                        Math.round(bmp.getHeight() * scale), true);
            }
            try (FileOutputStream out = new FileOutputStream(
                    Backgrounds.photoFile(this))) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
