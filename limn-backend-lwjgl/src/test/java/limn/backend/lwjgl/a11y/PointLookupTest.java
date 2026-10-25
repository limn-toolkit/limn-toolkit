package limn.backend.lwjgl.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.internal.AccessibilityAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The order the point lookups try siblings in, which is the whole of what the three bridges share
 * about a point: last to first, the later sibling being painted over the earlier, with a splitter
 * before them all. Each bridge's own lookup is asserted as well, macOS's through {@code AxHitTest}.
 */
class PointLookupTest {

    /** A split pane's children as it publishes them: two contents with the splitter between. */
    private static List<AccessibleNode> splitChildren() {
        Accessibility a = new Accessibility();
        AccessibilityAccess.beginWalk(a, 400, 300, Locale.ENGLISH);
        AccessibilityAccess.begin(a, 1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        int split = AccessibilityAccess.begin(a, 1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.SPLIT_PANE);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        Accessible.Role[] roles = {Accessible.Role.BUTTON, Accessible.Role.LABEL,
                Accessible.Role.SPLITTER, Accessible.Role.BUTTON};
        for (int i = 0; i < roles.length; i++) {
            AccessibilityAccess.begin(a, 1002 + i, split, Locale.ENGLISH, i * 100, 0, 100, 300);
            a.role(roles[i]);
            AccessibilityAccess.inherited(a, true, true, true, false, false);
            a.end();
        }
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        return tree.children(tree.node(tree.indexOf(1001)));
    }

    @Test
    void theSplitterComesFirstAndTheRestKeepThePlatformsOrder() {
        List<AccessibleNode> kids = splitChildren();

        assertArrayEquals(new int[] {2, 3, 1, 0}, PointLookup.tryOrder(kids.size(), kids::get),
                "last to first, after the splitter");
    }

    @Test
    void aSiblingTheBridgeHasNoNodeForKeepsItsPlace() {
        List<AccessibleNode> kids = splitChildren();

        assertArrayEquals(new int[] {2, 3, 1, 0},
                PointLookup.tryOrder(kids.size(), i -> i == 1 ? null : kids.get(i)));
        assertArrayEquals(new int[0], PointLookup.tryOrder(0, i -> null));
    }
}
