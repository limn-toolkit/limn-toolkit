package limn.backend.lwjgl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clip model a canvas state carries, without a device: which clips narrow the rectangle and
 * which one the rounded corners belong to. What the shader makes of the two is
 * {@code RoundedClipGeometryTest}'s; this pins what it is handed.
 */
class DeviceClipTest {

    private final DeviceClip clip = new DeviceClip();

    @BeforeEach
    void freshFrame() {
        clip.reset(200, 100);
    }

    @Test
    void aFreshFrameClipsToTheTargetWithNoRoundedClip() {
        assertArrayEquals(new float[]{0, 0, 200, 100}, rect());
        // The neutral rounded clip: the whole target, square, which contains every clip rectangle
        // and so leaves the shader's intersection of the two exactly the rectangle's.
        assertArrayEquals(new float[]{0, 0, 200, 100}, round());
        assertEquals(0, clip.radius);
        assertFalse(clip.isEmpty());
    }

    @Test
    void aPassBeforeARoundedClipIsNotWhereItsCornersGo() {
        // The partial repaint's order: the pass clips first, then a widget inside it clips its
        // rounded surface. The rounded clip is the surface's own rectangle, not the part of it the
        // pass left.
        clip.intersectRect(22, 0, 200, 100);
        clip.intersectRoundRect(16, 10, 90, 60, 12);

        assertArrayEquals(new float[]{22, 10, 90, 60}, rect(), "every clip narrows the rectangle");
        assertArrayEquals(new float[]{16, 10, 90, 60}, round(), "the rounded clip keeps its own");
        assertEquals(12, clip.radius);
    }

    @Test
    void aRectangleAfterARoundedClipLeavesItWhereItWas() {
        clip.intersectRoundRect(16, 10, 90, 60, 12);
        clip.intersectRect(30, 20, 60, 40);

        assertArrayEquals(new float[]{30, 20, 60, 40}, rect());
        assertArrayEquals(new float[]{16, 10, 90, 60}, round());
        assertEquals(12, clip.radius);
    }

    @Test
    void aSecondRoundedClipIntersectsTheRectanglesAndRoundsThemByItsOwnRadius() {
        clip.intersectRoundRect(16, 10, 90, 60, 12);
        clip.intersectRoundRect(40, 0, 150, 50, 4);

        assertArrayEquals(new float[]{40, 10, 90, 50}, rect());
        assertArrayEquals(new float[]{40, 10, 90, 50}, round());
        assertEquals(4, clip.radius, "the most recent radius");
    }

    @Test
    void aRoundedClipInsideAnotherIsExactlyItself() {
        clip.intersectRoundRect(16, 10, 90, 60, 12);
        clip.intersectRoundRect(30, 20, 60, 40, 3);

        assertArrayEquals(new float[]{30, 20, 60, 40}, round());
        assertEquals(3, clip.radius);
    }

    @Test
    void aSquareCorneredRoundedClipIsARectangleAndKeepsTheEarlierCorners() {
        clip.intersectRoundRect(16, 10, 90, 60, 12);
        clip.intersectRoundRect(0, 0, 50, 50, 0);

        assertArrayEquals(new float[]{16, 10, 50, 50}, rect());
        assertArrayEquals(new float[]{16, 10, 90, 60}, round(), "the earlier rounded clip stays");
        assertEquals(12, clip.radius);
    }

    @Test
    void theFirstRoundedClipIsNotCutToTheTarget() {
        // A surface hanging off the window's left edge: its corners are off-screen, and the window's
        // edge must not become a rounded corner of its own.
        clip.intersectRoundRect(-40, 10, 90, 60, 12);

        assertArrayEquals(new float[]{0, 10, 90, 60}, rect());
        assertArrayEquals(new float[]{-40, 10, 90, 60}, round());
    }

    @Test
    void disjointClipsLeaveNothing() {
        clip.intersectRoundRect(16, 10, 60, 40, 8);
        clip.intersectRoundRect(100, 50, 150, 90, 8);

        assertTrue(clip.isEmpty());
        float[] round = round();
        assertTrue(round[2] >= round[0] && round[3] >= round[1], "never an inverted rectangle");
    }

    @Test
    void aCopyIsIndependentOfItsSource() {
        clip.intersectRoundRect(16, 10, 90, 60, 12);
        DeviceClip saved = new DeviceClip();
        saved.copyFrom(clip);
        clip.intersectRect(30, 20, 60, 40);
        clip.intersectRoundRect(0, 0, 10, 10, 2);

        assertArrayEquals(new float[]{16, 10, 90, 60},
                new float[]{saved.x0, saved.y0, saved.x1, saved.y1});
        assertArrayEquals(new float[]{16, 10, 90, 60},
                new float[]{saved.roundX0, saved.roundY0, saved.roundX1, saved.roundY1});
        assertEquals(12, saved.radius);
    }

    private float[] rect() {
        return new float[]{clip.x0, clip.y0, clip.x1, clip.y1};
    }

    private float[] round() {
        return new float[]{clip.roundX0, clip.roundY0, clip.roundX1, clip.roundY1};
    }
}
