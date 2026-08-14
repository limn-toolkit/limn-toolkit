package limn.backend.lwjgl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The crisp-hairline rules at the four first-class scales: 1.0, 1.25, 1.5 and
 * 2.0. A 1-logical-px stroke must always land on whole device pixels with its
 * centerline on the half-pixel grid (odd widths) or integer grid (even).
 */
class SnappingTest {

    @Test
    void strokeWidthNeverDropsBelowOneDevicePixel() {
        assertEquals(1, Snapping.strokeWidthDev(1f, 1.0f));
        assertEquals(1, Snapping.strokeWidthDev(1f, 1.25f)); // 1.25 → 1, not 0 or blur
        assertEquals(2, Snapping.strokeWidthDev(1f, 1.5f));  // 1.5 rounds up
        assertEquals(2, Snapping.strokeWidthDev(1f, 2.0f));
        assertEquals(1, Snapping.strokeWidthDev(0.5f, 1.0f)); // sub-pixel still visible
        assertEquals(3, Snapping.strokeWidthDev(2f, 1.5f));
        assertEquals(5, Snapping.strokeWidthDev(2.5f, 2.0f));
    }

    @Test
    void oddWidthsCenterOnHalfPixels() {
        assertEquals(12.5f, Snapping.snapCenter(12.5f, 1));
        assertEquals(12.5f, Snapping.snapCenter(12.2f, 1));
        assertEquals(12.5f, Snapping.snapCenter(12.9f, 1));
        assertEquals(100.5f, Snapping.snapCenter(100.0f, 3));
    }

    @Test
    void evenWidthsCenterOnIntegers() {
        assertEquals(12f, Snapping.snapCenter(12.4f, 2));
        assertEquals(13f, Snapping.snapCenter(12.6f, 2));
        assertEquals(13f, Snapping.snapCenter(12.5f, 4));
    }

    @Test
    void snappedStrokeCoversWholePixelsAtEveryFirstClassScale() {
        // For each scale, a 1-logical-px edge at any position must produce a
        // stroke whose device-space span [center-w/2, center+w/2] hits pixel
        // boundaries exactly: the definition of "crisp".
        float[] scales = {1.0f, 1.25f, 1.5f, 2.0f};
        float[] positions = {0f, 10f, 10.3f, 127.77f};
        for (float scale : scales) {
            for (float pos : positions) {
                int widthDev = Snapping.strokeWidthDev(1f, scale);
                float center = Snapping.snapCenter(pos * scale, widthDev);
                float lo = center - widthDev / 2f;
                float hi = center + widthDev / 2f;
                assertEquals(Math.round(lo), lo, 1e-4,
                        "edge not on pixel boundary at scale " + scale + ", pos " + pos);
                assertEquals(Math.round(hi), hi, 1e-4,
                        "edge not on pixel boundary at scale " + scale + ", pos " + pos);
            }
        }
    }
}
