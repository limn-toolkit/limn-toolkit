package limn.a11y.macos;

import limn.graphics.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The coordinate conversion, with the numbers the phase 7 probe run measured on the guest.
 *
 * <p>These are not invented cases. The probe published this tree and an out-of-process client read
 * the screen rectangles back, so the expectations here are what AppKit itself computed from the
 * parent-space boxes it was handed.
 */
class AxFramesTest {

    private static final double EPSILON = 1e-9;

    /** The scene the probe published: 480 x 320 points, the size of the content view. */
    private static final Rect SCENE = new Rect(0, 0, 480, 320);

    @Test
    void aChildOfTheRootIsMeasuredAgainstTheWholeScene() {
        // The probe's 'form' group: 200x200 at scene (20, 60) from the top, which AppKit placed
        // 60 points up from the content view's bottom edge.
        assertArrayEquals(new double[] { 20, 60, 200, 200 },
                AxFrames.inParentSpace(new Rect(20, 60, 200, 200), SCENE), EPSILON);
    }

    @Test
    void aGrandchildIsMeasuredAgainstItsOwnParentAndNotTheScene() {
        Rect form = new Rect(20, 60, 200, 200);
        // 'nested', a 140x44 group whose bottom edge sits 20 points above the group's own bottom.
        // Against the scene it would come out at 40 -- the wrong answer that an earlier reading of
        // §2.2 would have produced, and the one a walk cannot see is wrong.
        assertArrayEquals(new double[] { 20, 20, 140, 44 },
                AxFrames.inParentSpace(new Rect(40, 196, 140, 44), form), EPSILON);
    }

    @Test
    void theOffsetsAddUpThroughThreeLevels() {
        Rect form = new Rect(20, 60, 200, 200);
        Rect nested = new Rect(40, 196, 140, 44);
        // 'deep', 10 points in and 10 points up from the nested group's bottom-left corner.
        assertArrayEquals(new double[] { 10, 10, 100, 24 },
                AxFrames.inParentSpace(new Rect(50, 206, 100, 24), nested), EPSILON);
    }

    @Test
    void aBoxFlushWithItsParentsBottomEdgeIsAtZero() {
        Rect parent = new Rect(0, 0, 100, 100);
        assertArrayEquals(new double[] { 0, 0, 100, 10 },
                AxFrames.inParentSpace(new Rect(0, 90, 100, 10), parent), EPSILON);
    }

    @Test
    void aBoxOutsideItsParentGetsANegativeCoordinateRatherThanBeingClamped() {
        // Clamping would move a node to somewhere it is not painted, which is worse than reporting
        // where it actually is: §12.1's bounds invariant is the place to catch an escaping box, and
        // it cannot catch one this class has already hidden.
        Rect parent = new Rect(0, 0, 100, 100);
        assertArrayEquals(new double[] { -10, 110, 50, 20 },
                AxFrames.inParentSpace(new Rect(-10, -30, 50, 20), parent), EPSILON);
    }
}
