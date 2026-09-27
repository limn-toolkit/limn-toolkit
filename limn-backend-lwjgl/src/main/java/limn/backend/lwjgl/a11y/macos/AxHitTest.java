package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.backend.lwjgl.a11y.PointLookup;

import java.util.function.IntFunction;

/**
 * Which child of an element a point is over, for {@code accessibilityHitTest:}: the choice that
 * method makes at every level, apart from the frames AppKit answers, so that it can be tested
 * without AppKit.
 *
 * <p>The same choice the Windows and AT-SPI lookups make: the later sibling first, because it is
 * painted over the earlier one, a splitter before them all (see {@link PointLookup}), and a child
 * that is not showing never. The walk used to run first to last, so where two siblings overlapped,
 * a popup's content over the rows beneath it, VoiceOver was told about the one underneath.
 */
final class AxHitTest {

    private AxHitTest() {
    }

    /**
     * The child whose frame holds the point.
     *
     * @param count   how many children there are
     * @param nodeAt  child {@code i}'s node, or {@code null} for one the bridge has no node for
     * @param frameAt child {@code i}'s frame, {@code {x, y, width, height}}, in the point's space
     * @param x       the point
     * @param y       the point
     * @return the child's index, or {@code -1} when the point is over none of them
     */
    static int childAt(int count, IntFunction<AccessibleNode> nodeAt, IntFunction<double[]> frameAt,
                       double x, double y) {
        for (int i : PointLookup.tryOrder(count, nodeAt)) {
            AccessibleNode node = nodeAt.apply(i);
            if (node != null && !node.has(Accessible.State.SHOWING)) {
                continue;
            }
            double[] frame = frameAt.apply(i);
            if (x >= frame[0] && x < frame[0] + frame[2] && y >= frame[1] && y < frame[1] + frame[3]) {
                return i;
            }
        }
        return -1;
    }
}
