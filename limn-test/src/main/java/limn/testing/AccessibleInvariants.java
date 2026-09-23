package limn.testing;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The four invariants every published tree holds, whatever is in it:
 * no node has role {@code UNKNOWN}; a focusable node has a name; no two nodes share an id; and a
 * node that says it is showing has a box, lies inside the scene, and overlaps every showing
 * ancestor. "Inside" cannot mean wholly inside, because a row scrolled half off the top of a list
 * is published where it is, with {@code SHOWING} saying how much of it is on screen.
 *
 * <p>Written once here so that the gallery test in {@code limn-demo} and the per-shape contracts
 * in this package hold the same tree to the same rules; before, the gallery had its own copy.
 */
public final class AccessibleInvariants {

    private AccessibleInvariants() {
    }

    /**
     * Checks one published tree; each violation names the node and the rule.
     *
     * @param window the window the tree belongs to, for the message
     * @param tree   what the scene published
     * @return the violations, empty when the tree holds
     */
    public static List<String> violations(String window, AccessibleTree tree) {
        List<String> out = new ArrayList<>();
        if (tree.nodeCount() == 0) {
            out.add("window \"" + window + "\" published no tree at all");
            return out;
        }
        Map<Long, Integer> byId = new HashMap<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            String where = "window \"" + window + "\", node " + describe(node);
            if (node.role() == Accessible.Role.UNKNOWN) {
                out.add(where + ": has role UNKNOWN");
            }
            if (node.has(Accessible.State.FOCUSABLE) && node.name().isBlank()) {
                out.add(where + ": is focusable and has no name");
            }
            Integer earlier = byId.put(node.id(), i);
            if (earlier != null) {
                out.add(where + ": shares its id with node " + describe(tree.node(earlier)));
            }
            if (node.has(Accessible.State.SHOWING)) {
                if (node.width() <= 0 || node.height() <= 0) {
                    out.add(where + ": is showing with an empty box");
                } else if (!overlaps(node.x(), node.y(), node.width(), node.height(),
                        0, 0, tree.sceneWidth(), tree.sceneHeight())) {
                    out.add(where + ": is showing and lies wholly outside the scene ("
                            + tree.sceneWidth() + "x" + tree.sceneHeight() + ")");
                } else {
                    for (int up = node.parent(); up != AccessibleNode.NONE;
                         up = tree.node(up).parent()) {
                        AccessibleNode ancestor = tree.node(up);
                        if (ancestor.has(Accessible.State.SHOWING)
                                && ancestor.width() > 0 && ancestor.height() > 0
                                && !overlaps(node.x(), node.y(), node.width(), node.height(),
                                ancestor.x(), ancestor.y(), ancestor.width(),
                                ancestor.height())) {
                            out.add(where + ": is showing and lies wholly outside its showing "
                                    + "ancestor " + describe(ancestor));
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether two boxes share any area.
     *
     * @return true when they overlap by more than an edge
     */
    public static boolean overlaps(float x, float y, float w, float h,
                                   float ox, float oy, float ow, float oh) {
        return x < ox + ow && ox < x + w && y < oy + oh && oy < y + h;
    }

    /** One node for a message: id, role, name and box. */
    public static String describe(AccessibleNode node) {
        return "#" + node.id() + " " + node.role() + " \"" + node.name() + "\" box=("
                + node.x() + ", " + node.y() + ", " + node.width() + "x" + node.height() + ")";
    }
}
