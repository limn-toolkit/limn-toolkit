package limn.backend.lwjgl.a11y;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;

import java.util.function.IntFunction;

/**
 * The order in which the three bridges' point lookups try the siblings over a point: the question
 * {@code ElementProviderFromPoint}, {@code GetAccessibleAtPoint} and {@code accessibilityHitTest:}
 * all ask, answered once.
 *
 * <p>Each bridge measures its own boxes, in its own coordinates, and keeps its own order among
 * siblings: Windows and AT-SPI try the later sibling first, because it is painted over the earlier
 * one. What they share is the one sibling that order gets wrong. A {@link Accessible.Role#SPLITTER
 * splitter}'s box is the band a pointer grabs, wider than the gutter it paints in, so it reaches
 * over the edges of the panes on both sides; the split pane tests it first for the pointer and paints
 * it last, but publishes it between the panes, where Tab and a reader meet it. Tried in tree order,
 * the band's half over the second pane answered that pane's content on Windows and AT-SPI, and the
 * half over the first pane answered its content on macOS, while the pointer there drags the
 * divider. A splitter is therefore tried before its siblings, on every platform.
 */
public final class PointLookup {

    private PointLookup() {
    }

    /**
     * Whether a node is hit before its siblings wherever its box overlaps theirs.
     *
     * @param node a sibling over the point
     * @return whether the pointer over the overlap is on this node
     */
    public static boolean isOverItsSiblings(AccessibleNode node) {
        return node.role() == Accessible.Role.SPLITTER;
    }

    /**
     * The indices of {@code count} siblings in the order a point lookup tries them: every one
     * {@linkplain #isOverItsSiblings over its siblings} first, then the rest, each group in the
     * platform's own order.
     *
     * @param count     how many siblings there are
     * @param node      sibling {@code i}'s node, or {@code null} for one the bridge has no node for
     * @param lastFirst whether the platform's own order runs from the last sibling to the first
     * @return the indices, each once
     */
    public static int[] tryOrder(int count, IntFunction<AccessibleNode> node, boolean lastFirst) {
        int[] order = new int[count];
        int front = 0;
        int back = count;
        // One walk in the platform's order: a sibling over the others is appended to the front
        // group, every other one is stacked into the back group and reversed afterwards, so both
        // groups keep the order they were met in.
        for (int k = 0; k < count; k++) {
            int i = lastFirst ? count - 1 - k : k;
            AccessibleNode sibling = node.apply(i);
            if (sibling != null && isOverItsSiblings(sibling)) {
                order[front++] = i;
            } else {
                order[--back] = i;
            }
        }
        for (int lo = front, hi = count - 1; lo < hi; lo++, hi--) {
            int swap = order[lo];
            order[lo] = order[hi];
            order[hi] = swap;
        }
        return order;
    }
}
