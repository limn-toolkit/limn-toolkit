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
        return switch (direction) {
            case UiaIds.NAVIGATE_DIRECTION_PARENT -> outlineParent(tree, index);
            case UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD -> firstChild(tree, index);
            case UiaIds.NAVIGATE_DIRECTION_LAST_CHILD -> lastChild(tree, index);
            case UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING -> nextSibling(tree, index);
            case UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING -> previousSibling(tree, index);
            default -> AccessibleNode.NONE;
        };
    }

    // ---- tree rows, nested (decision 4; semantics 6; W4)
    //
    // A tree publishes its rows flat, as siblings under the tree, each with its level in a hierarchy
    // facet. NVDA 2024.4.2 derives a tree item's level from how many TreeItem ancestors it has and
    // ignores UIA's Level (readings/nvda-2024.4.2-uia.md §2), and a native tree nests its items in the
    // raw view (readings/windows-read-native-tree-levels.txt). So navigation nests them: a row's
    // parent is the nearest earlier sibling row of a lower level, or the node the row hangs under when
    // there is none; a row's children are its own children, then the later sibling rows whose parent
    // that makes it. Every other node navigates by the stored links, and nothing here allocates.
    //
    // A virtualized tree publishes only the rows it has mounted, plus the cursor row it keeps
    // realized off screen (decision 22), so the rows before a row among its siblings need not be the
    // rows before it in the outline. The search therefore walks back only while each earlier row is
    // the one right above in the hierarchy facet's flat row index; at a gap (a scrolled-away stretch,
    // or the kept cursor row far from the viewport) or an unknown index it stops, and the row hangs
    // under its stored parent. A row whose parent row is not published is then heard a level too
    // high up by a reader counting TreeItem ancestors, which is the degradation ADR 039 §2.1
    // records; nesting it under a published row that is not its parent would put it in the wrong
    // place at a plausible level, which nothing downstream could notice.

    /** @return whether a node is a tree row that navigation nests by its level */
    static boolean isNestedRow(AccessibleNode node) {
        return UiaRowsShape.isOutlineRow(node);
    }

    /**
     * @param tree  the published tree
     * @param index a node
     * @return its parent as navigation answers it: for a nested row, the nearest earlier sibling
     *         row of a lower level reached through rows whose flat row indices run unbroken down
     *         to this row's, else the stored parent
     */
    static int outlineParent(AccessibleTree tree, int index) {
        AccessibleNode node = tree.node(index);
        if (!isNestedRow(node) || node.hierarchy().level() == 1) {
            return node.parent();
        }
        int level = node.hierarchy().level();
        int above = node.hierarchy().row() - 1; // the flat row index the next earlier row must have
        for (int at = node.previousSibling(); at != AccessibleNode.NONE && above > 0;
                at = tree.node(at).previousSibling()) {
            AccessibleNode earlier = tree.node(at);
            if (!isNestedRow(earlier)) {
                continue;
            }
            if (earlier.hierarchy().row() != above) {
                break; // a gap: the row's parent is not among the rows published next to it
            }
            if (earlier.hierarchy().level() < level) {
                return at;
            }
            above--;
        }
        return node.parent();
    }

    /** @return whether {@code at}, a sibling after a nested row, still lies in that row's subtree */
    private static boolean inBlockOf(AccessibleTree tree, AccessibleNode row, int at) {
        AccessibleNode later = tree.node(at);
        return !isNestedRow(later) || later.hierarchy().level() > row.hierarchy().level();
    }

    private static int firstChild(AccessibleTree tree, int index) {
        AccessibleNode node = tree.node(index);
        for (int at = node.firstChild(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
            if (outlineParent(tree, at) == index) {
                return at;
            }
        }
        return firstRowChild(tree, index);
    }

    /** @return the first later sibling row whose navigation parent is this nested row, or none */
    private static int firstRowChild(AccessibleTree tree, int index) {
        AccessibleNode node = tree.node(index);
        if (!isNestedRow(node)) {
            return AccessibleNode.NONE;
        }
        for (int at = node.nextSibling(); at != AccessibleNode.NONE && inBlockOf(tree, node, at);
                at = tree.node(at).nextSibling()) {
            if (outlineParent(tree, at) == index) {
                return at;
            }
        }
        return AccessibleNode.NONE;
    }

    private static int lastChild(AccessibleTree tree, int index) {
        AccessibleNode node = tree.node(index);
        if (isNestedRow(node)) {
            int last = AccessibleNode.NONE;
            for (int at = node.nextSibling(); at != AccessibleNode.NONE && inBlockOf(tree, node, at);
                    at = tree.node(at).nextSibling()) {
                if (outlineParent(tree, at) == index) {
                    last = at;
                }
            }
            if (last != AccessibleNode.NONE) {
                return last;
            }
        }
        for (int at = node.lastChild(); at != AccessibleNode.NONE;
                at = tree.node(at).previousSibling()) {
            if (outlineParent(tree, at) == index) {
                return at;
            }
        }
        return AccessibleNode.NONE;
    }

    private static int nextSibling(AccessibleTree tree, int index) {
        int parent = outlineParent(tree, index);
        if (parent == AccessibleNode.NONE) {
            return AccessibleNode.NONE;
        }
        AccessibleNode node = tree.node(index);
        boolean ownChild = node.parent() == parent;
        for (int at = node.nextSibling(); at != AccessibleNode.NONE; at = tree.node(at).nextSibling()) {
            if (!ownChild && !inBlockOf(tree, tree.node(parent), at)) {
                return AccessibleNode.NONE;
            }
            if (outlineParent(tree, at) == parent) {
                return at;
            }
        }
        // The last of a nested row's own children is followed by its first child row.
        return ownChild ? firstRowChild(tree, parent) : AccessibleNode.NONE;
    }

    private static int previousSibling(AccessibleTree tree, int index) {
        int parent = outlineParent(tree, index);
        if (parent == AccessibleNode.NONE) {
            return AccessibleNode.NONE;
        }
        AccessibleNode node = tree.node(index);
        boolean ownChild = node.parent() == parent;
        for (int at = node.previousSibling(); at != AccessibleNode.NONE;
                at = tree.node(at).previousSibling()) {
            if (!ownChild && at == parent) {
                break;
            }
            if (outlineParent(tree, at) == parent) {
                return at;
            }
        }
        if (ownChild) {
            return AccessibleNode.NONE;
        }
        // The first child row of a nested row is preceded by the last of the row's own children.
        AccessibleNode row = tree.node(parent);
        for (int at = row.lastChild(); at != AccessibleNode.NONE; at = tree.node(at).previousSibling()) {
            if (outlineParent(tree, at) == parent) {
                return at;
            }
        }
        return AccessibleNode.NONE;
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
     * What {@code GetFocus} answers: where the user is, which is the tree's
     * {@linkplain AccessibleTree#effectiveFocus() effective focus} (decision 1; semantics 4) --
     * the cursor row, cell, day or segment of a focused widget, and the focused node itself when it
     * has no cursor. Until 2026-09-15 this answered {@link AccessibleTree#focused()}, the widget,
     * so a reader asking after a cursor move found the table and never the cell (W3, LAB-NEW-4).
     *
     * @param tree the published tree
     * @return the index of that node, or {@link AccessibleNode#NONE} when nothing in this window
     *         is focused or the cursor lives in another window's tree (decision 5), which the
     *         provider answers through that window
     */
    static int focus(AccessibleTree tree) {
        long focused = tree.effectiveFocus();
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
