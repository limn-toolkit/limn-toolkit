package limn.backend.lwjgl.a11y;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * The order the point lookups try siblings in, which is the whole of what the three bridges share
 * about a point. Windows and AT-SPI are asserted through their own lookups as well; macOS asks
 * AppKit for every frame, which no test here can, so its order is pinned here: first to last, as
 * its children are pushed, with the splitter before them all.
 */
class PointLookupTest {

    /** A split pane's children as it publishes them: two contents with the splitter between. */
    private static List<AccessibleNode> splitChildren() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        int split = a.begin(1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.SPLIT_PANE);
        a.inherited(true, true, true, false, false);
        Accessible.Role[] roles = {Accessible.Role.BUTTON, Accessible.Role.LABEL,
                Accessible.Role.SPLITTER, Accessible.Role.BUTTON};
        for (int i = 0; i < roles.length; i++) {
            a.begin(1002 + i, split, Locale.ENGLISH, i * 100, 0, 100, 300);
            a.role(roles[i]);
            a.inherited(true, true, true, false, false);
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

        assertArrayEquals(new int[] {2, 3, 1, 0}, PointLookup.tryOrder(kids.size(), kids::get, true),
                "last to first, as Windows and AT-SPI try siblings, after the splitter");
        assertArrayEquals(new int[] {2, 0, 1, 3}, PointLookup.tryOrder(kids.size(), kids::get, false),
                "first to last, as macOS tries them, after the splitter");
    }

    @Test
    void aSiblingTheBridgeHasNoNodeForKeepsItsPlace() {
        List<AccessibleNode> kids = splitChildren();

        assertArrayEquals(new int[] {2, 0, 1, 3},
                PointLookup.tryOrder(kids.size(), i -> i == 1 ? null : kids.get(i), false));
        assertArrayEquals(new int[0], PointLookup.tryOrder(0, i -> null, true));
    }
}
