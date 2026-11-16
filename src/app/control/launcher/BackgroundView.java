package app.control.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * The shared background layer (v1.2): sits behind the pager in
 * MainActivity and paints the profile's background choice — solid
 * color, code-rendered gradient, or the device-local photo
 * (center-cropped) — with the scrim (a black dim, 0–70%) over it so
 * page text stays readable. The pages themselves are transparent
 * over this layer; the WebView keeps its own surface.
 */
public class BackgroundView extends View {

    private final Paint paint = new Paint();
    private Bitmap photo;
    private String loadedFor = "";

    public BackgroundView(Context c) {
        super(c);
    }

    /** Re-read the profile choice (call on resume / after edits). */
    public void refresh() {
        String key = Backgrounds.type(getContext()) + "|"
                + Backgrounds.value(getContext()) + "|"
                + (Backgrounds.photoPresent(getContext())
                    ? Backgrounds.photoFile(getContext()).lastModified() : 0);
        if (!key.equals(loadedFor)) {
            loadedFor = key;
            photo = null;
            if (Backgrounds.TYPE_PHOTO.equals(Backgrounds.effectiveType(getContext()))) {
                int w = Math.max(1, getWidth());
                int h = Math.max(1, getHeight());
                photo = Backgrounds.loadPhoto(getContext(), w, h);
            }
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // A photo decoded for a different size may need a re-decode.
        loadedFor = "";
        refresh();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        Context c = getContext();
        String type = Backgrounds.effectiveType(c);
        if (Backgrounds.TYPE_PHOTO.equals(type)) {
            if (photo == null) {
                photo = Backgrounds.loadPhoto(c, w, h);
            }
            if (photo != null) {
                // center-crop: scale to cover, draw centered
                float scale = Math.max((float) w / photo.getWidth(),
                        (float) h / photo.getHeight());
                float dw = photo.getWidth() * scale;
                float dh = photo.getHeight() * scale;
                paint.setShader(null);
                canvas.save();
                canvas.translate((w - dw) / 2f, (h - dh) / 2f);
                canvas.scale(scale, scale);
                canvas.drawBitmap(photo, 0, 0, paint);
                canvas.restore();
            } else {
                paint.setShader(null);
                paint.setColor(Ui.BG);
                canvas.drawRect(0, 0, w, h, paint);
            }
        } else if (Backgrounds.TYPE_GRADIENT.equals(type)) {
            int[] g = Backgrounds.gradientColors(Backgrounds.value(c));
            if (g != null) {
                paint.setShader(new LinearGradient(
                        0, 0, 0, h, g[0], g[1], Shader.TileMode.CLAMP));
            } else {
                paint.setShader(null);
                paint.setColor(Ui.BG);
            }
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        } else {
            paint.setShader(null);
            paint.setColor(Backgrounds.colorOf(
                    Backgrounds.value(c), Ui.BG));
            canvas.drawRect(0, 0, w, h, paint);
        }
        int scrim = Backgrounds.scrim(c);
        if (scrim > 0) {
            paint.setShader(null);
            paint.setColor((int) (scrim * 255 / 100.0f) << 24);
            canvas.drawRect(0, 0, w, h, paint);
        }
    }
}
