package app.control.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * The launcher's tiny line glyphs, drawn in one consistent style
 * (round-cap strokes, no fills except the Connections dot). Used at
 * ~12dp by the page-icon indicator and at ring size by the circle
 * menu, so both read as the same icon set.
 *
 * Coordinates are drawn in a unit box centered on (cx, cy) with the
 * given size: unit (0,0) is top-left, (1,1) bottom-right.
 */
final class PageGlyphs {

    static final int HOME = 0;
    static final int BROWSER = 1;
    static final int WALKIE = 2;
    static final int APPS = 3;
    static final int CHAT = 4;
    static final int ACTIVITY = 5;
    static final int SETTINGS = 6;
    static final int CONNECTIONS = 7;
    static final int IMAGE = 8;
    static final int BELL = 9;
    static final int SHIELD = 10;
    static final int LOCK = 11;
    static final int CLOCK = 12;
    static final int INFO = 13;
    static final int WALLET = 14;

    private PageGlyphs() {}

    static void draw(Canvas canvas, int glyph, float cx, float cy,
                     float size, Paint paint) {
        float l = cx - size / 2f;
        float t = cy - size / 2f;
        Path p = new Path();
        switch (glyph) {
            case HOME: {
                p.moveTo(x(l, size, .50f), y(t, size, .10f));
                p.lineTo(x(l, size, .90f), y(t, size, .46f));
                p.lineTo(x(l, size, .78f), y(t, size, .46f));
                p.lineTo(x(l, size, .78f), y(t, size, .88f));
                p.lineTo(x(l, size, .22f), y(t, size, .88f));
                p.lineTo(x(l, size, .22f), y(t, size, .46f));
                p.lineTo(x(l, size, .10f), y(t, size, .46f));
                p.close();
                canvas.drawPath(p, paint);
                break;
            }
            case BROWSER: {
                float r = size * .36f;
                canvas.drawCircle(cx, cy, r, paint);
                RectF oval = new RectF(cx - r * .45f, cy - r, cx + r * .45f, cy + r);
                canvas.drawOval(oval, paint);
                canvas.drawLine(cx - r, cy, cx + r, cy, paint);
                break;
            }
            case WALKIE: {
                canvas.drawLine(x(l, size, .40f), y(t, size, .34f),
                        x(l, size, .22f), y(t, size, .06f), paint);
                RectF body = new RectF(x(l, size, .28f), y(t, size, .32f),
                        x(l, size, .74f), y(t, size, .92f));
                canvas.drawRoundRect(body, size * .06f, size * .06f, paint);
                canvas.drawCircle(x(l, size, .51f), y(t, size, .52f), size * .09f, paint);
                canvas.drawLine(x(l, size, .38f), y(t, size, .74f),
                        x(l, size, .64f), y(t, size, .74f), paint);
                canvas.drawLine(x(l, size, .38f), y(t, size, .83f),
                        x(l, size, .64f), y(t, size, .83f), paint);
                break;
            }
            case APPS: {
                float cell = size * .34f;
                float gap = size * .10f;
                float x0 = cx - cell - gap / 2f;
                float y0 = cy - cell - gap / 2f;
                canvas.drawRoundRect(new RectF(x0, y0, x0 + cell, y0 + cell),
                        size * .05f, size * .05f, paint);
                canvas.drawRoundRect(new RectF(x0 + cell + gap, y0,
                        x0 + 2 * cell + gap, y0 + cell), size * .05f, size * .05f, paint);
                canvas.drawRoundRect(new RectF(x0, y0 + cell + gap, x0 + cell,
                        y0 + 2 * cell + gap), size * .05f, size * .05f, paint);
                canvas.drawRoundRect(new RectF(x0 + cell + gap, y0 + cell + gap,
                        x0 + 2 * cell + gap, y0 + 2 * cell + gap),
                        size * .05f, size * .05f, paint);
                break;
            }
            case CHAT: {
                RectF bubble = new RectF(x(l, size, .12f), y(t, size, .16f),
                        x(l, size, .88f), y(t, size, .64f));
                canvas.drawRoundRect(bubble, size * .10f, size * .10f, paint);
                p.moveTo(x(l, size, .30f), y(t, size, .64f));
                p.lineTo(x(l, size, .30f), y(t, size, .88f));
                p.lineTo(x(l, size, .52f), y(t, size, .64f));
                canvas.drawPath(p, paint);
                break;
            }
            case ACTIVITY: {
                p.moveTo(x(l, size, .06f), y(t, size, .56f));
                p.lineTo(x(l, size, .30f), y(t, size, .56f));
                p.lineTo(x(l, size, .42f), y(t, size, .28f));
                p.lineTo(x(l, size, .57f), y(t, size, .80f));
                p.lineTo(x(l, size, .67f), y(t, size, .56f));
                p.lineTo(x(l, size, .94f), y(t, size, .56f));
                canvas.drawPath(p, paint);
                break;
            }
            case SETTINGS: {
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4.0;
                    float c = (float) Math.cos(a);
                    float s = (float) Math.sin(a);
                    canvas.drawLine(cx + c * size * .24f, cy + s * size * .24f,
                            cx + c * size * .40f, cy + s * size * .40f, paint);
                }
                canvas.drawCircle(cx, cy, size * .17f, paint);
                break;
            }
            case CONNECTIONS: {
                // radiating arcs over a source dot
                float dotY = y(t, size, .80f);
                Paint.Style old = paint.getStyle();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, dotY, size * .06f, paint);
                paint.setStyle(old);
                for (int i = 1; i <= 3; i++) {
                    float r = size * (.16f + .13f * i);
                    RectF arc = new RectF(cx - r, dotY - r, cx + r, dotY + r);
                    canvas.drawArc(arc, 205, 130, false, paint);
                }
                break;
            }
            case IMAGE: {
                RectF frame = new RectF(x(l, size, .10f), y(t, size, .14f),
                        x(l, size, .90f), y(t, size, .86f));
                canvas.drawRoundRect(frame, size * .06f, size * .06f, paint);
                canvas.drawCircle(x(l, size, .32f), y(t, size, .36f), size * .06f, paint);
                p.moveTo(x(l, size, .12f), y(t, size, .82f));
                p.lineTo(x(l, size, .42f), y(t, size, .52f));
                p.lineTo(x(l, size, .60f), y(t, size, .68f));
                p.lineTo(x(l, size, .72f), y(t, size, .56f));
                p.lineTo(x(l, size, .88f), y(t, size, .82f));
                canvas.drawPath(p, paint);
                break;
            }
            case BELL: {
                p.moveTo(x(l, size, .50f), y(t, size, .10f));
                p.lineTo(x(l, size, .50f), y(t, size, .18f));
                p.moveTo(x(l, size, .30f), y(t, size, .66f));
                p.cubicTo(x(l, size, .30f), y(t, size, .38f),
                        x(l, size, .34f), y(t, size, .20f),
                        x(l, size, .50f), y(t, size, .20f));
                p.cubicTo(x(l, size, .66f), y(t, size, .20f),
                        x(l, size, .70f), y(t, size, .38f),
                        x(l, size, .70f), y(t, size, .66f));
                p.lineTo(x(l, size, .24f), y(t, size, .66f));
                p.lineTo(x(l, size, .76f), y(t, size, .66f));
                canvas.drawPath(p, paint);
                canvas.drawArc(new RectF(x(l, size, .40f), y(t, size, .70f),
                        x(l, size, .60f), y(t, size, .90f)), 0, 180, false, paint);
                break;
            }
            case SHIELD: {
                p.moveTo(x(l, size, .50f), y(t, size, .08f));
                p.lineTo(x(l, size, .84f), y(t, size, .20f));
                p.lineTo(x(l, size, .84f), y(t, size, .48f));
                p.cubicTo(x(l, size, .84f), y(t, size, .72f),
                        x(l, size, .68f), y(t, size, .84f),
                        x(l, size, .50f), y(t, size, .92f));
                p.cubicTo(x(l, size, .32f), y(t, size, .84f),
                        x(l, size, .16f), y(t, size, .72f),
                        x(l, size, .16f), y(t, size, .48f));
                p.lineTo(x(l, size, .16f), y(t, size, .20f));
                p.close();
                canvas.drawPath(p, paint);
                break;
            }
            case LOCK: {
                RectF body = new RectF(x(l, size, .22f), y(t, size, .44f),
                        x(l, size, .78f), y(t, size, .88f));
                canvas.drawRoundRect(body, size * .06f, size * .06f, paint);
                canvas.drawArc(new RectF(x(l, size, .32f), y(t, size, .12f),
                        x(l, size, .68f), y(t, size, .58f)), 180, 180, false, paint);
                canvas.drawCircle(cx, y(t, size, .64f), size * .05f, paint);
                break;
            }
            case CLOCK: {
                canvas.drawCircle(cx, cy, size * .38f, paint);
                canvas.drawLine(cx, cy, cx, y(t, size, .28f), paint);
                canvas.drawLine(cx, cy, x(l, size, .66f), cy, paint);
                break;
            }
            case INFO: {
                canvas.drawCircle(cx, cy, size * .38f, paint);
                canvas.drawLine(cx, y(t, size, .42f), cx, y(t, size, .72f), paint);
                Paint.Style old = paint.getStyle();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, y(t, size, .30f), size * .045f, paint);
                paint.setStyle(old);
                break;
            }
            case WALLET: {
                // billfold: rounded body, flap line, clasp dot
                RectF body = new RectF(x(l, size, .10f), y(t, size, .22f),
                        x(l, size, .90f), y(t, size, .82f));
                canvas.drawRoundRect(body, size * .08f, size * .08f, paint);
                canvas.drawLine(x(l, size, .10f), y(t, size, .38f),
                        x(l, size, .90f), y(t, size, .38f), paint);
                Paint.Style old = paint.getStyle();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x(l, size, .72f), y(t, size, .60f), size * .05f, paint);
                paint.setStyle(old);
                break;
            }
            default:
                break;
        }
    }

    private static float x(float l, float size, float u) {
        return l + size * u;
    }

    private static float y(float t, float size, float u) {
        return t + size * u;
    }
}
