package app.control.launcher;

/**
 * Display mapping for hold points (v1.14). Pure Java — no Android
 * imports — so the JVM harness exercises the exact shipping code.
 *
 * {@code getevent} reports coordinates in the device's NATIVE
 * orientation (portrait on a phone), while the overlay window
 * lives in the CURRENT orientation. This maps normalized
 * native-orientation coordinates (0..1 per axis) to pixels in the
 * current orientation, given the display rotation and the current
 * display size.
 *
 * Rotation convention (Surface.ROTATION_* values): the rotation
 * is clockwise from natural orientation, so with natural size
 * W x H and current size w x h:
 *   ROTATION_0:   (x, y)         -> (nx*w, ny*h)
 *   ROTATION_90:  (H-y, x)       -> ((1-ny)*w, nx*h)
 *   ROTATION_180: (W-x, H-y)     -> ((1-nx)*w, (1-ny)*h)
 *   ROTATION_270: (y, W-x)       -> (ny*w, (1-nx)*h)
 * The 90/270 pair is the one a wrong convention would mirror;
 * the harness pins all four, and the on-device rotation test is
 * Adam's (see BUILD_NOTES).
 */
public final class HoldCoordinateMapper {

    public static final int ROTATION_0 = 0;
    public static final int ROTATION_90 = 1;
    public static final int ROTATION_180 = 2;
    public static final int ROTATION_270 = 3;

    private HoldCoordinateMapper() {}

    /**
     * @param nx/ny        normalized native-orientation coordinates.
     * @param rotation     Surface.ROTATION_* of the current display.
     * @param widthPx/heightPx the CURRENT display size in pixels
     *                     (swapped vs natural when rotated 90/270).
     * @return {x, y} in current-orientation display pixels.
     */
    public static float[] map(double nx, double ny, int rotation,
                              int widthPx, int heightPx) {
        switch (rotation) {
            case ROTATION_90:
                return new float[]{(float) ((1.0 - ny) * widthPx),
                        (float) (nx * heightPx)};
            case ROTATION_180:
                return new float[]{(float) ((1.0 - nx) * widthPx),
                        (float) ((1.0 - ny) * heightPx)};
            case ROTATION_270:
                return new float[]{(float) (ny * widthPx),
                        (float) ((1.0 - nx) * heightPx)};
            default:
                return new float[]{(float) (nx * widthPx),
                        (float) (ny * heightPx)};
        }
    }

    /**
     * The hold radius in normalized units for a dp tolerance on
     * this display: the tolerance in pixels over the shorter
     * display side, so the radius stays circular on screen.
     */
    public static double radiusNorm(float density, int widthPx,
                                    int heightPx, float radiusDp) {
        int min = Math.min(widthPx, heightPx);
        if (min <= 0 || density <= 0) return 0.03;
        return (radiusDp * density) / (double) min;
    }
}
