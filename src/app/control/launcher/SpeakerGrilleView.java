package app.control.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The radio face's speaker grille (v1.23): rows of inset slots,
 * drawn, never an image. Pure decoration — no touch handling,
 * no state. The slots use the theme's panel tone against the
 * radio body's surface tone, so they read as inset holes on
 * both the dark and light sets, and the tokens are read at
 * draw time, so a theme change re-colors the grille live.
 *
 * The view stretches: on tall screens the grille area absorbs
 * the slack (see WalkieLayout), and the slot group stays
 * centered in the view instead of stretching with it, so the
 * radio keeps its proportions on any height.
 */
public class SpeakerGrilleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Rows of slots in the group, and slots per row. */
    private static final int ROWS = 5;
    private static final int COLUMNS = 3;

    public SpeakerGrilleView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float slotH = Ui.dp(getContext(), 5);
        float gapX = Ui.dp(getContext(), 14);
        float gapY = Ui.dp(getContext(), 9);
        float marginX = Ui.dp(getContext(), 28);
        float slotW = (w - 2 * marginX - (COLUMNS - 1) * gapX) / COLUMNS;
        if (slotW <= 0) return;
        float groupH = ROWS * slotH + (ROWS - 1) * gapY;
        float top = (h - groupH) / 2f;
        paint.setColor(Ui.PANEL);
        paint.setStyle(Paint.Style.FILL);
        float radius = slotH / 2f;
        for (int row = 0; row < ROWS; row++) {
            float y = top + row * (slotH + gapY);
            for (int col = 0; col < COLUMNS; col++) {
                float x = marginX + col * (slotW + gapX);
                canvas.drawRoundRect(x, y, x + slotW, y + slotH,
                        radius, radius, paint);
            }
        }
    }
}
