package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ScrollFacet;
import limn.graphics.Rect;
import limn.input.Keys;
import limn.scene.Constraints;
import limn.scene.LayoutDirection;
import limn.scene.Size;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link ScrollView} becomes in the accessible tree: one {@code SCROLL_PANE} node whether
 * or not anything overflows, with no name, no verb and no tab stop, carrying where the viewport
 * sits in the content and how much of it shows, and nothing else.
 *
 * <p>The cases that pin where ADR 039 §7's row was short or wrong. The row says descendants
 * "gain {@code SCROLL_INTO_VIEW}", and that is neither the pane's doing nor true as stated: the
 * walk offers it to every focusable widget wherever it sits and to no other, so a label scrolled
 * away gets nothing, and the pane has no hook that could reach past its one direct child without
 * putting a nameless group between the pane and its controls. The row leaves every number to
 * guess, and the source settles them: percent is offset over maximum offset, leading-edge based
 * in both directions and published unflipped; view size is the viewport over the <em>child</em>,
 * which the layout makes at least the viewport, so it never exceeds one and is exactly one on a
 * fixed axis; scrollable is the wheel's predicate, a maximum offset above zero, and not the bar's
 * half-point slop. And a scroll is not an event of its own: the model has none, so what a reader
 * hears is a bounds change on each published descendant that moved.
 *
 * <p>Every case drives the pane's public API on a bound scene, or the scene's own wheel and key
 * paths, or calls the scene from where a bridge stands, and reads back what the scene published.
 * Nothing constructs a node.
 */
class ScrollViewAccessibilityTest extends AccessibleComponentTestBase {

    private static final double EPS = 1e-6;

    /** A leaf of a fixed preferred size that says nothing unless a test names it. */
    private static final class Box extends Widget {
        private final float prefWidth;
        private final float prefHeight;

        Box(float prefWidth, float prefHeight) {
            this.prefWidth = prefWidth;
            this.prefHeight = prefHeight;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(prefWidth, prefHeight);
        }
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * Binds {@code pane} inside a box of the given size, in a column so that the box keeps its
     * size: the scene lays its root tight to the window, and a sized box as the root would be
     * the window.
     *
     * @return the root, for a test that sets a direction on it
     */
    private Column bindIn(float width, float height, ScrollView pane) {
        Column root = new Column();
        root.add(new SizedBox(width, height, pane));
        bind(root);
        return root;
    }

    /** {@link #bindIn} with a layout direction set on the root before the first frame. */
    private void bindIn(float width, float height, ScrollView pane, LayoutDirection direction) {
        Column root = new Column();
        root.setLayoutDirection(direction);
        root.add(new SizedBox(width, height, pane));
        bind(root);
    }

    /** @return the one scroll-pane node in the current tree */
    private AccessibleNode paneNode() {
        return node(Accessible.Role.SCROLL_PANE);
    }

    /** @return every event of {@code type} raised so far, in order */
    private List<AccessibleEvent> eventsOf(AccessibleEvent.Type type) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == type) {
                found.add(event);
            }
        }
        return found;
    }

    /** @return how many nodes in {@code tree} carry {@code role} */
    private static int countOf(AccessibleTree tree, Accessible.Role role) {
        int count = 0;
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                count++;
            }
        }
        return count;
    }

    private static void assertFacet(ScrollFacet expected, ScrollFacet actual, String why) {
        assertNotNull(actual, why);
        assertEquals(expected.horizontalPercent(), actual.horizontalPercent(), EPS,
                "horizontal percent: " + why);
        assertEquals(expected.verticalPercent(), actual.verticalPercent(), EPS,
                "vertical percent: " + why);
        assertEquals(expected.horizontalViewSize(), actual.horizontalViewSize(), EPS,
                "horizontal view size: " + why);
        assertEquals(expected.verticalViewSize(), actual.verticalViewSize(), EPS,
                "vertical view size: " + why);
        assertEquals(expected.horizontallyScrollable(), actual.horizontallyScrollable(),
                "horizontally scrollable: " + why);
        assertEquals(expected.verticallyScrollable(), actual.verticallyScrollable(),
                "vertically scrollable: " + why);
    }

    // ------------------------------------------------------------------------------- the shape

    @Test
    void anOverflowingPaneIsOneScrollPaneNodeWithNoNameNoVerbAndNoTabStop() {
        bindIn(100, 100, new ScrollView(new Box(100, 400)));

        assertEquals(1, countOf(tree(), Accessible.Role.SCROLL_PANE), describe(tree()));
        AccessibleNode node = paneNode();
        assertEquals(0, node.parent(),
                "the column and the sized box are scaffolding; the pane hangs off the window"
                        + describe(tree()));
        assertEquals("", node.name(),
                "the pane holds no string; a tooltip or the application names it" + describe(tree()));
        assertEquals("", node.description(), describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSABLE),
                "a scroll pane is never a tab stop" + describe(tree()));
        assertFalse(node.has(Accessible.State.FOCUSED), describe(tree()));
        assertFalse(node.has(Accessible.State.HORIZONTAL),
                "the axis bits name the one axis of a single value, and a pane has two"
                        + describe(tree()));
        assertFalse(node.has(Accessible.State.VERTICAL), describe(tree()));
        assertTrue(node.has(Accessible.State.ENABLED), describe(tree()));
        assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        assertNull(node.actions(),
                "no verb: the vocabulary has no scroll with an axis in it, and the two the walk "
                        + "adds are for focusable widgets only" + describe(tree()));
        assertNull(node.value(), describe(tree()));
        assertNull(node.selection(), describe(tree()));
        assertFacet(new ScrollFacet(0, 0, 1, 0.25, false, true), node.scroll(),
                "a 100 point viewport over 400 points of content, at the top");
        assertEquals(1, countOf(tree(), Accessible.Role.SCROLL_BAR),
                "the vertical bar is the pane's own child, in add order after the content"
                        + describe(tree()));
        assertEquals(tree().indexOf(node.id()), node(Accessible.Role.SCROLL_BAR).parent(),
                describe(tree()));
    }

    @Test
    void contentThatFitsIsStillTheOneNodeWithNothingToScroll() {
        bindIn(100, 100, new ScrollView(new Box(100, 50)));

        assertEquals(1, countOf(tree(), Accessible.Role.SCROLL_PANE),
                "the node is not conditional on overflow: a pane that came and went with its "
                        + "content would raise a structure change on every resize" + describe(tree()));
        assertFacet(new ScrollFacet(0, 0, 1, 1, false, false), paneNode().scroll(),
                "nothing to scroll is carried by the two booleans, not by the node's absence");
        assertEquals(0, countOf(tree(), Accessible.Role.SCROLL_BAR),
                "and the bar ignores itself, which is its own decision" + describe(tree()));
    }

    // ------------------------------------------------------------------------------- the scroll

    @Test
    void aScrollIsARepublishAndABoundsChangeNotAStructureChange() {
        Box top = new Box(100, 200);
        top.setAccessibleName("Top");
        Column content = new Column();
        content.add(top);
        content.add(new Box(100, 200));
        ScrollView pane = new ScrollView(content);
        bindIn(100, 100, pane);
        long paneId = paneNode().id();
        long topId = node("Top").id();
        float topBefore = node("Top").y();
        int published = bridge.published.size();

        pane.scrollTo(0, 100);
        frame();

        assertEquals(published + 1, bridge.published.size(), "a scroll changes the facet");
        assertEquals(paneId, paneNode().id(), "the same node" + describe(tree()));
        assertEquals(topId, node("Top").id(), describe(tree()));
        assertEquals(100.0 / 300.0, paneNode().scroll().verticalPercent(), EPS,
                "offset over maximum offset, which is content less viewport" + describe(tree()));
        assertEquals(topBefore - 100, node("Top").y(), 1e-3,
                "the child is physically moved, so descendants' scene coordinates are already "
                        + "scroll-corrected" + describe(tree()));
        List<AccessibleEvent> bounds = eventsOf(AccessibleEvent.Type.BOUNDS_CHANGED);
        assertEquals(1, bounds.size(),
                "the one thing a reader hears about a scroll: the box that moved: " + bridge.events);
        assertEquals(topId, bounds.get(0).nodeId(), bridge.events.toString());
        Rect was = (Rect) bounds.get(0).oldValue();
        Rect now = (Rect) bounds.get(0).newValue();
        assertEquals(topBefore, was.y(), 1e-3);
        assertEquals(topBefore - 100, now.y(), 1e-3);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "a scroll is not a change of shape: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), bridge.events.toString());

        published = bridge.published.size();
        bridge.events.clear();
        frame();
        assertEquals(published, bridge.published.size(), "a quiet frame publishes nothing");
        assertTrue(bridge.events.isEmpty(), "and raises nothing: " + bridge.events);
    }

    @Test
    void theWheelThroughTheSceneMovesTheFacetOnTheNextFrame() {
        ScrollView pane = new ScrollView(new Box(100, 400));
        bindIn(100, 100, pane);

        scene.scrolled(0, -1, 50, 50);
        scene.inputBatchEnded();
        frame();

        assertEquals(48, pane.offsetY(), 1e-3, "one notch");
        assertEquals(48.0 / 300.0, paneNode().scroll().verticalPercent(), EPS,
                "the scroll rides the damage the repaint already declares; nothing marks the node "
                        + "dirty by hand" + describe(tree()));
    }

    @Test
    void pageDownThroughTheScenesKeyPathMovesTheFacetOnTheNextFrame() {
        Button inside = new Button("Inside");
        Column content = new Column();
        content.add(inside);
        content.add(new Box(100, 400));
        ScrollView pane = new ScrollView(content);
        bindIn(100, 100, pane);
        inside.requestFocus();
        frame();
        assertTrue(inside.isFocused(), "the key has to bubble up from a focused descendant");
        double before = paneNode().scroll().verticalPercent();

        scene.keyEvent(Keys.PAGE_DOWN, true, false, 0);
        scene.keyEvent(Keys.PAGE_DOWN, false, false, 0);
        scene.inputBatchEnded();
        frame();

        assertEquals(100, pane.offsetY(), 1e-3, "a page is the viewport");
        assertTrue(paneNode().scroll().verticalPercent() > before,
                "the key the button did not want reached the pane, and the tree followed"
                        + describe(tree()));
    }

    // ---------------------------------------------------------------------------- the direction

    @Test
    void thePercentIsLeadingEdgeBasedInBothDirectionsWhileTheContentMovesTheOtherWay() {
        Box ltrContent = new Box(300, 100);
        ltrContent.setAccessibleName("Content");
        ScrollView ltr = new ScrollView(ltrContent, true, false);
        bindIn(100, 100, ltr, LayoutDirection.LTR);
        assertEquals(0, paneNode().scroll().horizontalPercent(), EPS);
        assertEquals(1.0 / 3.0, paneNode().scroll().horizontalViewSize(), EPS);
        float ltrStart = node("Content").x();
        ltr.scrollTo(ltr.maxOffsetX(), 0);
        frame();
        assertEquals(1, paneNode().scroll().horizontalPercent(), EPS, describe(tree()));
        float ltrEnd = node("Content").x();
        assertEquals(-200, ltrEnd - ltrStart, 1e-3, "left to right, the content moves left");

        Box rtlContent = new Box(300, 100);
        rtlContent.setAccessibleName("Content");
        ScrollView rtl = new ScrollView(rtlContent, true, false);
        bindIn(100, 100, rtl, LayoutDirection.RTL);
        assertEquals(0, paneNode().scroll().horizontalPercent(), EPS,
                "zero is the leading edge, which is the right edge here, and the facet's own "
                        + "start" + describe(tree()));
        assertEquals(1.0 / 3.0, paneNode().scroll().horizontalViewSize(), EPS);
        float rtlStart = node("Content").x();
        rtl.scrollTo(rtl.maxOffsetX(), 0);
        frame();
        assertEquals(1, paneNode().scroll().horizontalPercent(), EPS,
                "the same number as left to right: the percent is published unflipped, and the "
                        + "mirroring is in where the content sits" + describe(tree()));
        float rtlEnd = node("Content").x();
        assertEquals(200, rtlEnd - rtlStart, 1e-3, "right to left, the content moves right");
    }

    // ------------------------------------------------------------------------------- the gutter

    @Test
    void aReservedGutterShrinksTheViewSizeAndNotTheNodesBox() {
        ScrollView pane = new ScrollView(new Box(200, 400), true, true)
                .setBarLayout(ScrollGutters.Layout.RESERVED);
        bindIn(100, 100, pane);

        float t = ScrollBar.thickness();
        assertTrue(t > 0, "the fixture needs a gutter to have a width");
        assertEquals(100 - t, pane.viewportHeight(), 1e-3, "the horizontal bar took its strip");
        AccessibleNode node = paneNode();
        assertEquals((100 - t) / 400.0, node.scroll().verticalViewSize(), EPS,
                "the viewport less the gutter over the child, not the box over it"
                        + describe(tree()));
        assertEquals((100 - t) / 200.0, node.scroll().horizontalViewSize(), EPS, describe(tree()));
        assertTrue(node.scroll().verticalViewSize() < 100.0 / 400.0, describe(tree()));
        assertEquals(pane.localToSceneX(), node.x(), 1e-3, describe(tree()));
        assertEquals(pane.localToSceneY(), node.y(), 1e-3, describe(tree()));
        assertEquals(pane.width(), node.width(), 1e-3,
                "the node is the whole box, gutters included, because the bars sit inside it"
                        + describe(tree()));
        assertEquals(pane.height(), node.height(), 1e-3, describe(tree()));
        assertEquals(2, countOf(tree(), Accessible.Role.SCROLL_BAR), describe(tree()));
    }

    // --------------------------------------------------------------------------------- the clip

    @Test
    void aDescendantScrolledPastTheViewportIsVisibleAndNotShowing() {
        Box top = new Box(100, 100);
        top.setAccessibleName("Top");
        Column content = new Column();
        content.add(top);
        content.add(new Box(100, 300));
        ScrollView pane = new ScrollView(content);
        bindIn(100, 100, pane);
        assertTrue(node("Top").has(Accessible.State.SHOWING), describe(tree()));

        pane.scrollTo(0, 150);
        frame();

        AccessibleNode away = node("Top");
        assertTrue(away.has(Accessible.State.VISIBLE),
                "nothing hid it; it was scrolled away" + describe(tree()));
        assertFalse(away.has(Accessible.State.SHOWING),
                "the pane clips its children, and the walk's clip test is what says so; the pane "
                        + "writes no state of its own" + describe(tree()));

        pane.scrollTo(0, 0);
        frame();
        assertTrue(node("Top").has(Accessible.State.SHOWING), describe(tree()));
    }

    // ---------------------------------------------------------------------------- the zero pane

    @Test
    void aPaneLaidOutAtNothingPublishesWholeViewsAndDoesNotChurn() {
        ScrollView pane = new ScrollView(new Box(0, 0));
        bindIn(0, 0, pane);

        assertFacet(new ScrollFacet(0, 0, 1, 1, false, false), paneNode().scroll(),
                "zero over zero is not a number, and a not-a-number differs from itself on "
                        + "every frame; the guards are what keep the diff quiet");
        int published = bridge.published.size();

        for (int i = 0; i < 3; i++) {
            pane.invalidate();  // a repaint that changes no accessible fact
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "a damaged frame that walks and finds the same six numbers publishes nothing");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());
    }

    // -------------------------------------------------------------------------------- no verbs

    @Test
    void noVerbMovesThePaneAndNoneRaisesInvoked() throws InterruptedException {
        ScrollView pane = new ScrollView(new Box(100, 400));
        bindIn(100, 100, pane);
        long id = paneNode().id();

        assertTrue(perform(id, Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1)),
                "the host accepts any id it published; accepted is not done");
        assertTrue(perform(id, Accessible.Action.INCREMENT, Accessible.Argument.NONE));
        assertTrue(perform(id, Accessible.Action.PRESS, Accessible.Argument.NONE));
        assertTrue(perform(id, Accessible.Action.SCROLL_INTO_VIEW, Accessible.Argument.NONE));
        frame();

        assertEquals(0, pane.offsetY(), 1e-3,
                "a value with no axis in it, a step with no axis in it and a press mean nothing "
                        + "to a pane, and the hook refuses them all");
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
        assertEquals(0, paneNode().scroll().verticalPercent(), EPS, describe(tree()));
    }
}
