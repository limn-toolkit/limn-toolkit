package limn.backend.lwjgl.a11y.macos;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.internal.AccessibilityAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The child {@code accessibilityHitTest:} descends into at each level, with frames given as AppKit
 * would answer them. Where two siblings overlap, the later one is painted on top and is the one
 * under the pointer; the walk ran first to last and told VoiceOver about the one underneath, while
 * Windows and AT-SPI already answered the one on top.
 */
class AxHitTestTest {

    /** Three siblings: a wide one, a later one over its right half, and a hidden one over both. */
    private static List<AccessibleNode> overlapping() {
        Accessibility a = new Accessibility();
        AccessibilityAccess.beginWalk(a, 400, 300, Locale.ENGLISH);
        AccessibilityAccess.begin(a, 1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        int group = AccessibilityAccess.begin(a, 1001, 0, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.GROUP);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        AccessibilityAccess.begin(a, 1002, group, Locale.ENGLISH, 0, 0, 400, 100);
        a.role(Accessible.Role.BUTTON);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        a.end();
        AccessibilityAccess.begin(a, 1003, group, Locale.ENGLISH, 200, 0, 200, 100);
        a.role(Accessible.Role.BUTTON);
        AccessibilityAccess.inherited(a, true, true, true, false, false);
        a.end();
        AccessibilityAccess.begin(a, 1004, group, Locale.ENGLISH, 0, 0, 400, 100);
        a.role(Accessible.Role.BUTTON);
        AccessibilityAccess.inherited(a, true, false, false, false, false);
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);
        return tree.children(tree.node(tree.indexOf(1001)));
    }

    private static int at(List<AccessibleNode> kids, double x, double y) {
        return AxHitTest.childAt(kids.size(), kids::get, i -> {
            AccessibleNode n = kids.get(i);
            return new double[] {n.x(), n.y(), n.width(), n.height()};
        }, x, y);
    }

    @Test
    void theSiblingPaintedOnTopAnswersWhereTwoOverlap() {
        List<AccessibleNode> kids = overlapping();
        assertEquals(1, at(kids, 300, 50), "the later sibling is drawn over the earlier");
        assertEquals(0, at(kids, 100, 50), "and outside it the earlier one answers");
        assertEquals(-1, at(kids, 100, 200), "a point over no child answers none");
    }

    @Test
    void aChildThatIsNotShowingIsNeverTheAnswer() {
        List<AccessibleNode> kids = overlapping();
        assertEquals(false, kids.get(2).has(Accessible.State.SHOWING), "sanity: the third is hidden");
        assertEquals(0, at(kids, 100, 50), "the hidden last sibling does not cover the first");
    }
}
