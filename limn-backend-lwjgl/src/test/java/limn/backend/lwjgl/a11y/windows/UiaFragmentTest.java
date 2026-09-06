package limn.backend.lwjgl.a11y.windows;

import limn.accessibility.Accessibility;
import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The five questions a fragment answers, over trees built the way a scene builds one.
 *
 * <p>Every case here is about the snapshot and nothing else — no widget, no window, no thread — so
 * the answers a client would get on a guest are the answers asserted here.
 */
class UiaFragmentTest {

    /**
     * A window at a screen origin with a scale factor, holding two buttons side by side and a
     * nested group inside the first.
     */
    private static AccessibleTree scene(int screenX, int screenY, float factor, long focusedId) {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.name(I18nString.literal("A window"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);

        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.GROUP);
        a.name(I18nString.literal("Left"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.begin(1002, 1, Locale.ENGLISH, 10, 20, 100, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, focusedId == 1002);
        a.end();
        a.end();

        a.begin(1003, 0, Locale.ENGLISH, 200, 0, 200, 100);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Cancel"), Accessible.NameFrom.CONTENT);
        a.action(Accessible.Action.PRESS);
        a.inherited(true, true, true, true, focusedId == 1003);
        a.end();
        a.end();
        return a.publish(focusedId, screenX, screenY, factor, true);
    }

    @Test
    void navigationFollowsTheLinksTheSnapshotStores() {
        AccessibleTree tree = scene(0, 0, 1f, 0);
        int window = tree.indexOf(1000);
        int left = tree.indexOf(1001);
        int save = tree.indexOf(1002);
        int cancel = tree.indexOf(1003);

        assertEquals(left, UiaFragment.navigate(tree, window, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
        assertEquals(cancel, UiaFragment.navigate(tree, window, UiaIds.NAVIGATE_DIRECTION_LAST_CHILD));
        assertEquals(window, UiaFragment.navigate(tree, left, UiaIds.NAVIGATE_DIRECTION_PARENT));
        assertEquals(cancel, UiaFragment.navigate(tree, left, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING));
        assertEquals(left,
                UiaFragment.navigate(tree, cancel, UiaIds.NAVIGATE_DIRECTION_PREVIOUS_SIBLING));
        assertEquals(save, UiaFragment.navigate(tree, left, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
    }

    @Test
    void theEndsOfTheTreeAnswerNothingRatherThanWrappingAround() {
        AccessibleTree tree = scene(0, 0, 1f, 0);
        int window = tree.indexOf(1000);
        int save = tree.indexOf(1002);

        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, window, UiaIds.NAVIGATE_DIRECTION_PARENT),
                "the root of this fragment has no parent inside it");
        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, save, UiaIds.NAVIGATE_DIRECTION_FIRST_CHILD));
        assertEquals(AccessibleNode.NONE,
                UiaFragment.navigate(tree, save, UiaIds.NAVIGATE_DIRECTION_NEXT_SIBLING));
        assertEquals(AccessibleNode.NONE, UiaFragment.navigate(tree, save, 99),
                "a direction nobody defined is not a navigation");
    }

    /**
     * The identifier is 64 bits and the array is of 32-bit integers, so it takes two slots.
     * Truncating instead would make two nodes one element to a client the moment the counter
     * outran 32 bits, which §1.3 says it will in a long-lived application.
     */
    @Test
    void theRuntimeIdCarriesTheWholeIdentifierBehindTheMarker() {
        assertArrayEquals(new int[] {UiaIds.APPEND_RUNTIME_ID, 0, 1002},
                UiaFragment.runtimeId(1002));

        long big = (7L << 32) | 0x0000_0000_DEAD_BEEFL;
        assertArrayEquals(new int[] {UiaIds.APPEND_RUNTIME_ID, 7, 0xDEADBEEF},
                UiaFragment.runtimeId(big));

        assertNotEquals(UiaFragment.runtimeId(1L << 32)[1], UiaFragment.runtimeId(0)[1],
                "two identifiers differing only above 32 bits must not share a runtime id");
    }

    @Test
    void theRectangleIsTheWindowsOriginPlusTheNodesBoxTimesTheFactor() {
        AccessibleTree tree = scene(200, 100, 2f, 0);
        AccessibleNode save = tree.node(tree.indexOf(1002));

        assertArrayEquals(new double[] {200 + (0 + 10) * 2, 100 + (0 + 20) * 2, 100 * 2, 40 * 2},
                UiaFragment.boundingRectangle(tree, save), 1e-9,
                "logical and window-relative in, native screen coordinates out -- and both the "
                        + "origin and the factor come off the snapshot, because the window's own "
                        + "position may only be read on the thread that owns it");
    }

    @Test
    void theFocusedNodeIsTheOneTheSnapshotNamesAndNothingWhenNoneIs() {
        AccessibleTree focused = scene(0, 0, 1f, 1003);
        assertEquals(focused.indexOf(1003), UiaFragment.focus(focused));

        assertEquals(AccessibleNode.NONE, UiaFragment.focus(scene(0, 0, 1f, 0)),
                "a window nothing in is focused answers nothing, not its root");
    }

    @Test
    void theElementUnderAPointIsTheDeepestOneWhoseBoxHoldsIt() {
        AccessibleTree tree = scene(200, 100, 2f, 0);

        // The Save button's screen box is (220, 140) 200x80.
        assertEquals(tree.indexOf(1002), UiaFragment.elementFromPoint(tree, 300, 180));
        // Inside the left group but outside the button.
        assertEquals(tree.indexOf(1001), UiaFragment.elementFromPoint(tree, 205, 105));
        // The other button.
        assertEquals(tree.indexOf(1003), UiaFragment.elementFromPoint(tree, 700, 150));
        // Inside the window and in neither.
        assertEquals(tree.indexOf(1000), UiaFragment.elementFromPoint(tree, 300, 500));
        // Outside the window.
        assertEquals(AccessibleNode.NONE, UiaFragment.elementFromPoint(tree, 5, 5));
    }

    /**
     * The reason this walks the snapshot rather than calling {@code Widget#hitTest}: that method
     * answers null for a disabled subtree at every level, because it routes input and input does
     * not reach a disabled control. UI Automation expects to find the disabled button and be told
     * it is disabled.
     */
    @Test
    void aDisabledControlIsStillFoundUnderThePointer() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 10, 20, 100, 40);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Save"), Accessible.NameFrom.CONTENT);
        a.inherited(false, true, true, false, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        int found = UiaFragment.elementFromPoint(tree, 50, 30);

        assertEquals(tree.indexOf(1001), found,
                "hitTest would answer the window here, and the user hovering a greyed-out button "
                        + "would hear the panel behind it");
        assertEquals(Boolean.FALSE,
                UiaProperties.valueOf(tree.node(found), UiaIds.IS_ENABLED),
                "and what they hear instead is that it is disabled");
    }

    /**
     * A node scrolled out of its viewport is not on the glass, so nothing can be over it — which is
     * the same fact {@code IsOffscreen} publishes, read here for a different purpose.
     */
    @Test
    void aScrolledAwayNodeDoesNotAnswerForAPointInsideItsViewport() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.SCROLL_PANE);
        a.scroll(0, 0.5, 1, 0.5, false, true);
        a.inherited(true, true, true, false, false);
        // Its box overlaps the viewport, and the walk says it is not showing.
        a.begin(1002, 1, Locale.ENGLISH, 0, 0, 200, 40);
        a.role(Accessible.Role.LIST_ITEM);
        a.name(I18nString.literal("Row"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, false, false, false);
        a.end();
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        assertEquals(tree.indexOf(1001), UiaFragment.elementFromPoint(tree, 10, 10),
                "the pane answers, because the row it holds is not on the glass");
    }

    /**
     * Two siblings over one point, which is the shape an in-scene popup makes over the content
     * beneath it. The later one is painted on top, so it is what the pointer is over; answering
     * the earlier one would put a reader on the control the menu is covering.
     */
    @Test
    void whereSiblingsOverlapTheLaterOneAnswersBecauseItIsPaintedOnTop() {
        Accessibility a = new Accessibility();
        a.beginWalk(400, 300, Locale.ENGLISH);
        a.begin(1000, AccessibleNode.NONE, Locale.ENGLISH, 0, 0, 400, 300);
        a.role(Accessible.Role.WINDOW);
        a.inherited(true, true, true, false, false);
        a.begin(1001, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.BUTTON);
        a.name(I18nString.literal("Underneath"), Accessible.NameFrom.CONTENT);
        a.inherited(true, true, true, true, false);
        a.end();
        a.begin(1002, 0, Locale.ENGLISH, 0, 0, 200, 100);
        a.role(Accessible.Role.MENU);
        a.name(I18nString.literal("On top"), Accessible.NameFrom.EXPLICIT);
        a.inherited(true, true, true, false, false);
        a.end();
        a.end();
        AccessibleTree tree = a.publish(0, 0, 0, 1f, true);

        assertEquals(tree.indexOf(1002), UiaFragment.elementFromPoint(tree, 50, 50),
                "the menu covers the button, and a reader told it was over the button would be "
                        + "describing a control the user cannot see or reach");
    }

    @Test
    void anEmptyTreeAnswersNothingRatherThanThrowing() {
        assertEquals(AccessibleNode.NONE, UiaFragment.elementFromPoint(AccessibleTree.EMPTY, 0, 0));
        assertEquals(AccessibleNode.NONE, UiaFragment.focus(AccessibleTree.EMPTY));
    }
}
