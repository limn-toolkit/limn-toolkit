package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

/**
 * What {@code IRawElementProviderFragment} and its root answer, computed from the snapshot alone.
 *
 * <p>Navigation, the runtime id, the screen rectangle, the focused element and the element under a
 * point: five questions a client asks constantly, and every one of them is answerable from an
 * immutable tree with no widget touched and no thread of ours entered. That is the whole shape ADR
 * 039 is built around, and it is why these can be asserted here rather than only in a guest.
 *
 * <p><b>The element under a point is not {@code Widget#hitTest}.</b> That method answers
 * {@code null} for a disabled subtree at every level, because it exists to route input and input
 * does not reach a disabled control. UI Automation asks a different question — it expects to find
 * the disabled button under the pointer and to tell the user it is disabled — so this walks the
 * snapshot's own bounds instead, and a client hovering a greyed-out control hears what it is rather
 * than hearing the panel behind it.
 */
final class UiaFragment {

    private UiaFragment() {
    }

    /**
     * @param tree      the published tree
     * @param index     the index of the node being navigated from
     * @param direction one of {@link UiaIds}' {@code NAVIGATE_DIRECTION_*} values
     * @return the index of the node in that direction, or {@link AccessibleNode#NONE}
     */
    static int navigate(AccessibleTree tree, int index, int direction) {
        AccessibleNode node = tree.node(index);
        return switch (direction) {
            case UiaIds.NAVIGATE_DIRECTION_PARENT -> node.parent();
            case UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD -> node.firstChild();
            case UiaIds.NAVIGATE_DIRECTION_LAST_CHILD -> node.lastChild();
            case UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING -> node.nextSibling();
            case UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING -> node.previousSibling();
            default -> AccessibleNode.NONE;
        };
    }

    /**
     * The runtime id, in the form UI Automation completes for us.
     *
     * <p>Three integers: the marker, then the node identifier split in half. UI Automation replaces
     * the marker with the host window's own runtime id, which is what makes the result unique
     * across processes without this bridge knowing anything about other processes. The identifier
     * is 64 bits and the array is of 32-bit integers, so it takes two slots — and it is split
     * rather than truncated, because §1.3 mints identifiers from a counter that will outrun 32 bits
     * in a long-lived application and two nodes sharing a runtime id are one element to a client.
     *
     * @param nodeId the node's identifier
     * @return {@code {APPEND_RUNTIME_ID, high 32 bits, low 32 bits}}
     */
    static int[] runtimeId(long nodeId) {
        return new int[] {UiaIds.APPEND_RUNTIME_ID, (int) (nodeId >>> 32), (int) nodeId};
    }

    /**
     * The node's box in native screen coordinates, which is what {@code UiaRect} is measured in.
     *
     * <p>A node's own bounds are logical and window-relative, so this is the one place the window's
     * origin and its scale factor are applied. Both come from the snapshot rather than from the
     * window, for §3.4's reason: the window's position is user-interface-thread-confined and this
     * is asked on an RPC thread, so the frame that published the tree is what stamped them onto it.
     *
     * @param tree the tree the node came from, for the origin and the factor it was stamped with
     * @param node the node
     * @return {@code {x, y, width, height}} in screen coordinates
     */
    static double[] boundingRectangle(AccessibleTree tree, AccessibleNode node) {
        float factor = tree.logicalToScreenFactor();
        return new double[] {
                tree.screenX() + node.x() * factor,
                tree.screenY() + node.y() * factor,
                node.width() * factor,
                node.height() * factor
        };
    }

    /**
     * @param tree the published tree
     * @return the index of the node holding the keyboard focus, or {@link AccessibleNode#NONE}
     *         when nothing in this window does
     */
    static int focus(AccessibleTree tree) {
        long focused = tree.focused();
        return focused == 0 ? AccessibleNode.NONE : tree.indexOf(focused);
    }

    /**
     * The deepest node whose box contains a screen point.
     *
     * <p>Deepest, and among siblings the last one, because later siblings paint over earlier ones
     * and what a pointer is over is what a user can see. A node that is not {@code SHOWING} is
     * skipped: it is not on the glass, so nothing can be over it — which is also what keeps a
     * scrolled-away row from answering for a point inside its viewport.
     *
     * @param tree the published tree
     * @param x    a screen x
     * @param y    a screen y
     * @return the index of the node under the point, or {@link AccessibleNode#NONE} when the point
     *         is outside this window entirely
     */
    static int elementFromPoint(AccessibleTree tree, double x, double y) {
        if (tree.nodeCount() == 0) {
            return AccessibleNode.NONE;
        }
        int at = 0;
        if (!contains(tree, tree.node(at), x, y)) {
            return AccessibleNode.NONE;
        }
        for (boolean descended = true; descended; ) {
            descended = false;
            // Last-to-first: the later sibling is the one painted on top.
            for (int child = tree.node(at).lastChild(); child != AccessibleNode.NONE;
                    child = tree.node(child).previousSibling()) {
                AccessibleNode candidate = tree.node(child);
                if (candidate.has(Accessible.State.SHOWING) && contains(tree, candidate, x, y)) {
                    at = child;
                    descended = true;
                    break;
                }
            }
        }
        return at;
    }

    private static boolean contains(AccessibleTree tree, AccessibleNode node, double x, double y) {
        double[] box = boundingRectangle(tree, node);
        return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
    }
}
