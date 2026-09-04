package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link SplitPane}'s panes become in the accessible tree, which is nothing.
 *
 * <p>A pane is scaffolding: it declares no role, no name and no action, it is never focusable, and
 * an application can never reach it to give it a tooltip. ADR 039 §1.6's predicate therefore
 * deletes it every frame and hoists its content into the split's own place, and the right amount of
 * accessibility code in {@code SplitPane.Pane} is none.
 *
 * <p><b>A widget that needs no code still owes tests</b>, and these are the reason. The deletion of
 * the node does not delete the pane's <em>clip</em>: {@link Widget#isShowing()} walks the widget
 * tree and intersects a box against every ancestor that clips its children, so a control inside a
 * collapsed pane publishes as visible and not on screen with nothing written anywhere. That answer
 * is free, it is correct, and it is entirely dependent on the pane keeping
 * {@code clipsChildren()} — a fact with nothing else in the suite standing on it. So the tests
 * below pin the absence of the node and the presence of the clip together, because the two are the
 * same decision.
 *
 * <p>Everything here drives the split's public API and reads the tree the scene published; nothing
 * constructs a node.
 */
class SplitPaneAccessibilityTest extends AccessibleComponentTestBase {

    /**
     * A leaf with a fixed preferred size and an application-supplied name, which is what an
     * ordinary control in a pane amounts to for this test: a widget that survives the predicate and
     * so has a node whose box and states can be read.
     */
    private static final class Box extends Widget {
        private final float prefWidth;
        private final float prefHeight;

        Box(String name, float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
            setAccessibleName(name);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }
    }

    /**
     * A container that lays one child inside its own box and one far outside it: the case the pane
     * exists for, since without the clip the second child would paint into the other pane.
     */
    private static final class Overflow extends Widget {
        Overflow(Widget inside, Widget outside) {
            add(inside);
            add(outside);
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(0, 0);
        }

        @Override
        protected void onLayout() {
            place(children().get(0), 0);
            place(children().get(1), 10_000);
        }

        private static void place(Widget child, float x) {
            child.measure(Constraints.tight(10, 10));
            child.layoutBox(x, 0, 10, 10);
        }
    }

    private SplitPane split;

    /** Binds a horizontal split of two named boxes and settles the first frame. */
    private void bindSplit() {
        split = SplitPane.horizontal(new Box("first", 80, 40), new Box("second", 80, 40));
        bind(split);
    }

    @Test
    void noAnonymousGroupStandsBetweenTheSplitAndItsContents() {
        bindSplit();

        for (int i = 0; i < tree().nodeCount(); i++) {
            AccessibleNode node = tree().node(i);
            assertFalse(node.role() == Accessible.Role.GROUP && node.name().isEmpty(),
                    "a nameless group survived the predicate: " + describe(tree()));
        }
        assertEquals(3, tree().nodeCount(),
                "the window, and one node per content: the split, both panes and the divider are "
                        + "all scaffolding today" + describe(tree()));
        assertEquals(node("first").parent(), node("second").parent(),
                "both contents hoist into the same place");
        assertEquals(0, node("first").parent(), "which is the window node itself");
    }

    @Test
    void aCollapsedPaneIsVisibleAndNotShowing() {
        bindSplit();
        split.setMinimums(0, 0);

        split.setRatio(0);
        frame();
        assertTrue(node("first").has(Accessible.State.VISIBLE),
                "nothing hid the collapsed pane's content; it has no room" + describe(tree()));
        assertFalse(node("first").has(Accessible.State.SHOWING),
                "the pane's clip reaches the tree even though the pane has no node"
                        + describe(tree()));
        assertTrue(node("second").has(Accessible.State.SHOWING));

        split.setRatio(1);
        frame();
        assertTrue(node("first").has(Accessible.State.SHOWING));
        assertTrue(node("second").has(Accessible.State.VISIBLE));
        assertFalse(node("second").has(Accessible.State.SHOWING),
                "and the mirror of it on the other side" + describe(tree()));
    }

    @Test
    void contentOverflowingAPaneIsNotShowing() {
        Box inside = new Box("inside", 10, 10);
        Box outside = new Box("outside", 10, 10);
        split = SplitPane.horizontal(new Overflow(inside, outside), new Box("second", 80, 40));
        bind(split);

        assertTrue(node("inside").has(Accessible.State.SHOWING));
        assertTrue(node("outside").has(Accessible.State.VISIBLE),
                "an overflowing child is still published; it is not on screen" + describe(tree()));
        assertFalse(node("outside").has(Accessible.State.SHOWING),
                "a child that overflows its pane stops at the gutter" + describe(tree()));
    }

    @Test
    void aRatioChangeMovesBoxesAndKeepsIdentity() {
        bindSplit();
        long firstId = node("first").id();
        long secondId = node("second").id();
        float wasX = node("second").x();

        split.setRatio(0.8f);
        frame();

        assertEquals(firstId, node("first").id(), "identity is minted over the widget tree");
        assertEquals(secondId, node("second").id());
        assertNotEquals(wasX, node("second").x(), "and the boxes moved");
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0,
                "a drag is a move, and a move is what a reader is told about");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was added or removed by moving the divider");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED));

        split.setMinimums(0, 0);
        split.setRatio(0);
        frame();

        assertEquals(firstId, node("first").id(),
                "a collapsed pane goes off screen; it does not go away" + describe(tree()));
        assertFalse(node("first").has(Accessible.State.SHOWING));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "destroying and rebuilding a subtree on every frame of a drag is what a screen "
                        + "reader experiences as everything it holds becoming invalid");
    }

    @Test
    void hoistedContentsKeepTreeOrderUnderRtl() {
        bindSplit();
        // The divider is a tab stop only when the application asks, and it is the third child
        // because the constructor adds it last so that it wins the hit test where its grab band
        // overlaps the panes. It publishes with no role of its own until its own step lands.
        split.setDividerFocusable(true);
        split.setLayoutDirection(LayoutDirection.RTL);
        frame();

        List<AccessibleNode> children = childrenOf(tree().node(0));
        assertEquals(3, children.size(), describe(tree()));
        assertEquals("first", children.get(0).name(), "tree order is the widget tree's order");
        assertEquals("second", children.get(1).name());
        assertTrue(children.get(2).has(Accessible.State.FOCUSABLE),
                "and the splitter is announced after both panes" + describe(tree()));
        assertTrue(node("first").x() > node("second").x(),
                "the leading pane is on the right, and the tree is not sorted by geometry"
                        + describe(tree()));
    }

    @Test
    void namingAPaneMaterialisesItAndReKeysNothing() {
        bindSplit();
        long firstId = node("first").id();

        split.children().get(0).setAccessibleName("Sidebar");
        frame();

        AccessibleNode sidebar = node("Sidebar");
        assertEquals(Accessible.Role.GROUP, sidebar.role(),
                "an application that wants a named region names the pane, with no code in Pane");
        assertEquals(tree().indexOf(sidebar.id()), node("first").parent(),
                "and the content hangs under it" + describe(tree()));
        assertEquals(firstId, node("first").id(),
                "a container that stops being scaffolding must not re-key what is under it");
    }
}
