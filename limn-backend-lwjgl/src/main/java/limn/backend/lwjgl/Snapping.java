package limn.backend.lwjgl;

/**
 * Pixel-grid alignment rules for crisp strokes at fractional HiDPI scales.
 * A stroke is snapped by (1) rounding its width to whole device pixels (never
 * below 1) and (2) placing its centerline on a half-pixel when the device
 * width is odd, or on an integer pixel when even (the classic crisp-line
 * rule). Without this, 1-logical-pixel borders blur or vanish at 125%/150%.
 */
final class Snapping {

    private Snapping() {
    }

    /** @return the stroke width in whole device pixels (at least 1) */
    static int strokeWidthDev(float strokeWidthLogical, float scale) {
        return Math.max(1, Math.round(strokeWidthLogical * scale));
    }

    /**
     * Snaps a stroke centerline coordinate (device px) for a stroke of
     * {@code widthDev} device pixels: odd widths sit on half-pixels, even
     * widths on integers.
     */
    static float snapCenter(float deviceCoord, int widthDev) {
        if ((widthDev & 1) == 1) {
            return (float) Math.floor(deviceCoord) + 0.5f;
        }
        return Math.round(deviceCoord);
    }
}
