package limn.testing;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;

import java.util.ArrayList;
import java.util.List;

/**
 * Reading a published tree in a test: one node by name, every node in a state, and the whole
 * tree as text for the message of an assertion that failed. The two accessibility test bases
 * each had these three; the tests under them each had a fourth for a node by identifier.
 */
public final class AccessibleTrees {

    private AccessibleTrees() {
    }

    /**
     * @param tree the tree
     * @param name a node's name, exactly
     * @return the first node with that name, or {@code null}
     */
    public static AccessibleNode named(AccessibleTree tree, String name) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).name().equals(name)) {
                return tree.node(i);
            }
        }
        return null;
    }

    /**
     * @param tree the tree
     * @param role a role
     * @return the first node with that role, or {@code null}
     */
    public static AccessibleNode withRole(AccessibleTree tree, Accessible.Role role) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                return tree.node(i);
            }
        }
        return null;
    }

    /**
     * @param tree  the tree
     * @param state a state
     * @return every node carrying it, in tree order
     */
    public static List<AccessibleNode> withState(AccessibleTree tree, Accessible.State state) {
        List<AccessibleNode> found = new ArrayList<>();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).has(state)) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    /**
     * The tree as one line per node, for the message of a failed assertion: index, role, name,
     * states, box and parent.
     *
     * @param tree the tree
     * @return the text, beginning with a newline so it reads under the assertion's own message
     */
    public static String describe(AccessibleTree tree) {
        StringBuilder out = new StringBuilder("\n");
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            out.append("  ").append(i).append(' ').append(node.role())
                    .append(" \"").append(node.name()).append("\" ")
                    .append(node.states())
                    .append(" box=").append(node.x()).append(',').append(node.y())
                    .append(' ').append(node.width()).append('x').append(node.height())
                    .append(" parent=").append(node.parent())
                    .append('\n');
        }
        return out.toString();
    }
}
