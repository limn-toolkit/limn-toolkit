package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.ScrollFacet;
import limn.accessibility.SelectionFacet;
import limn.scene.LayoutDirection;
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
 * What a {@link TabbedPane}'s header strip becomes in the accessible tree: one tab list above the
 * tabs, one horizontal run, holding the selection they are members of and saying how much of
 * itself is on screen.
 *
 * <p>The survey row in ADR 039 §7 gives this widget a role and a selection facet and is short in
 * three places against the source, each of which a case below pins. It omits the scroll facet,
 * though the strip is a clipped viewport with a wheel, two chevrons and a reveal all moving it —
 * the same omission §7.2 already records twice elsewhere. It is silent on orientation, which the
 * class fixes. And its empty facets column reads as "nothing more to decide" on a widget whose
 * hardest question, whether it should exist at all, the row does not raise: the strip declares
 * nothing today and is deleted as scaffolding, and because the sliding indicator is painted as an
 * overlay rather than as content, the deletion is silent even though the widget really does paint
 * information.
 *
 * <p>What this step deliberately does <em>not</em> write is the second hook the mapping asked for.
 * Marking the selected header active from the strip is the same bit on the same node that the tab
 * header's own step refused, for reasons that have not changed: only the selected header is a tab
 * stop, so it is already the focused node whenever the strip holds the keyboard, and a container
 * takes the first active node anywhere in its subtree, so a pane nested in a list cell would hand
 * that list one of these tabs as its own cursor. The facet's active descendant therefore stays
 * zero, and a case below says so rather than leaving it to be re-derived.
 *
 * <p>Every case here drives the pane's public API, the scene's public input, or the scene from
 * where a bridge stands. Nothing constructs a node, and nothing locates one by a tab's name.
 */
class TabStripAccessibilityTest extends AccessibleComponentTestBase {

    /** The scene the base binds is this wide, which is what the overflow cases are sized against. */
    private static final float SCENE_WIDTH = 400;

    /** Comparing two ratios that were computed as floats and widened. */
    private static final double EPS = 1e-6;

    /**
     * Six of these overflow a 400-point pane and five do not: under the deterministic ruler a
     * caption of four code points measures 40, and a header is that plus its two horizontal
     * paddings.
     */
    private static final String[] SIX = {"AAAA", "BBBB", "CCCC", "DDDD", "EEEE", "FFFF"};

    /** The pane under test. */
    private TabbedPane pane;

    /** The column it is bound in, for the case that turns the whole subtree around. */
    private Column root;

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A pane of captioned tabs over empty panels, in a box of a known width so that whether the
     * strip overflows is the test's decision and not the ruler's.
     */
    private void bindTabs(float width, String... captions) {
        pane = new TabbedPane();
        for (String caption : captions) {
            pane.addTab(caption, new SizedBox(60, 60));
        }
        root = new Column();
        root.add(new SizedBox(width, 120, pane));
        bind(root);
        // The deterministic ruler, so the overflow cases can be sized rather than hoped for. Bind
        // renders its first frame under whatever ruler the process has, so lay out again and start
        // from there.
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /**
     * The strip widget itself, reached the way an application would reach it: it is the pane's
     * first child by construction. Used only to compare the tree against the widget's own box,
     * never as a way into a hook.
     */
    private Widget strip() {
        return pane.children().get(0);
    }

    /** @return the tab list node */
    private AccessibleNode stripNode() {
        return node(Accessible.Role.TAB_LIST);
    }

    /** @return every tab node in the tree, in tree order */
    private List<AccessibleNode> tabNodes() {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == Accessible.Role.TAB) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    /** @return every event raised so far against {@code id}, in order */
    private List<AccessibleEvent> eventsFor(long id) {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.nodeId() == id) {
                found.add(event);
            }
        }
        return found;
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

    // ------------------------------------------------------------------------------ what it is

    @Test
    void aStripThatFitsIsOneUnnamedHorizontalTabListWithNowhereToScroll() {
        bindTabs(SCENE_WIDTH, "Alpha", "Beta", "Gamma");

        AccessibleNode list = stripNode();
        assertEquals("", list.name(),
                "the strip holds no string and is not focusable, so it is not one of the nodes a "
                        + "name is required of" + describe(tree()));
        assertEquals("", list.description(), describe(tree()));
        assertTrue(list.has(Accessible.State.HORIZONTAL),
                "one run of tabs at every width and in both directions, which is what tells a "
                        + "reader that Left and Right traverse it" + describe(tree()));
        assertFalse(list.has(Accessible.State.VERTICAL), describe(tree()));
        assertFalse(list.has(Accessible.State.FOCUSABLE),
                "the strip is a viewport and the selected header is the tab stop"
                        + describe(tree()));
        assertNull(list.actions(),
                "no verb: not focusable, so neither free one, and the vocabulary has no scroll "
                        + "verb to offer" + describe(tree()));
        assertNull(list.value(), describe(tree()));
        assertNull(list.text(), describe(tree()));
        assertNull(list.toggle(), describe(tree()));
        assertNull(list.expand(), describe(tree()));
        assertNull(list.selectionItem(),
                "the strip holds a selection and is not a member of one" + describe(tree()));
        assertEquals(List.of(), list.relations(), describe(tree()));

        assertEquals(strip().localToSceneX(), list.x(), describe(tree()));
        assertEquals(strip().localToSceneY(), list.y(), describe(tree()));
        assertEquals(SCENE_WIDTH, list.width(),
                "nothing overflows, so the viewport is the whole strip row" + describe(tree()));
        assertEquals(strip().height(), list.height(), describe(tree()));

        assertFacet(new ScrollFacet(0, 0, 1, 1, false, false), list.scroll(),
                "three tabs fit, and a fitting strip must say nowhere to go rather than vend a "
                        + "scrollable tab list to every platform" + describe(tree()));
    }

    @Test
    void theSelectionFacetIsTheContainersOwnShapeAndClaimsNoCursor() {
        bindTabs(SCENE_WIDTH, "Alpha", "Beta", "Gamma");

        assertEquals(new SelectionFacet(false, true, 0), stripNode().selection(),
                "one index that swaps, a pane holding tabs always has one selected, and no active "
                        + "descendant: only the selected header is a tab stop, so it is already "
                        + "the focused node whenever the strip holds the keyboard, and the first "
                        + "active node in a subtree is what an enclosing container would take"
                        + describe(tree()));
        assertEquals(List.of(), nodesWith(Accessible.State.ACTIVE),
                "nothing in this pane marks a node active" + describe(tree()));
    }

    @Test
    void everyTabHangsUnderTheTabList() {
        bindTabs(SCENE_WIDTH, "Alpha", "Beta", "Gamma");

        assertEquals(tabNodes(), childrenOf(stripNode()),
                "the headers are the strip's children in the widget tree and must be its children "
                        + "in this one: a selection change is addressed to a tab's published "
                        + "parent, so a deleted strip would raise it on the window"
                        + describe(tree()));
    }

    @Test
    void aTabChangeRaisesItsSelectionEventOnTheTabList() {
        bindTabs(SCENE_WIDTH, "Alpha", "Beta", "Gamma");
        long list = stripNode().id();

        pane.setSelectedIndex(2);
        frame();

        List<AccessibleEvent> selections = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.SELECTION_CHANGED) {
                selections.add(event);
            }
        }
        assertFalse(selections.isEmpty(), "moving the selection is a selection change: "
                + bridge.events);
        for (AccessibleEvent event : selections) {
            assertEquals(list, event.nodeId(),
                    "on the tab list, which is the node a platform can read a selection off: "
                            + bridge.events);
        }
    }

    // ------------------------------------------------------------------------------ overflow

    @Test
    void underOverflowTheBoxIsTheViewportBetweenTheThreeControls() {
        bindTabs(SCENE_WIDTH, SIX);

        AccessibleNode list = stripNode();
        assertTrue(list.x() > 0, "the leading chevron is outside the viewport" + describe(tree()));
        assertTrue(list.width() < SCENE_WIDTH, describe(tree()));
        assertEquals(3 * list.x(), SCENE_WIDTH - list.width(), EPS,
                "three square controls, one before the viewport and two after it, so the pane "
                        + "keeps three of them and the strip starts after one; publishing the "
                        + "pane's own width here would put a reader's cursor on a strip half "
                        + "again as wide as the one on screen" + describe(tree()));
        assertEquals(strip().localToSceneX(), list.x(), describe(tree()));
        assertEquals(strip().width(), list.width(),
                "the walk's free box, which is the viewport by construction" + describe(tree()));

        ScrollFacet facet = list.scroll();
        assertTrue(facet.horizontallyScrollable(), describe(tree()));
        assertFalse(facet.verticallyScrollable(),
                "the run has no second axis" + describe(tree()));
        assertEquals(0, facet.horizontalPercent(), EPS,
                "the first tab is selected and the strip has not moved" + describe(tree()));
        assertEquals(0, facet.verticalPercent(), EPS, describe(tree()));
        assertTrue(facet.horizontalViewSize() < 1,
                "six tabs are wider than the viewport shows" + describe(tree()));
        assertEquals(1, facet.verticalViewSize(), EPS, describe(tree()));
    }

    @Test
    void theViewSizeIsTheViewportOverTheHeadersAndNotOverThePane() {
        bindTabs(SCENE_WIDTH, SIX);

        AccessibleNode list = stripNode();
        float headersTotal = 0;
        for (Widget header : strip().children()) {
            headersTotal += header.width();
        }
        assertEquals(list.width() / headersTotal, list.scroll().horizontalViewSize(), EPS,
                "the fraction of the tab run the viewport shows" + describe(tree()));
        assertTrue(list.scroll().horizontalViewSize() < list.width() / SCENE_WIDTH,
                "dividing by the pane's width instead would overstate how much is on screen"
                        + describe(tree()));
    }

    @Test
    void theFacetFollowsTheLiveOffsetAndTheViewportDoesNotMoveWithIt() {
        bindTabs(SCENE_WIDTH, SIX);
        AccessibleNode before = stripNode();
        double viewSize = before.scroll().horizontalViewSize();

        scene.scrolled(0, -1, before.x() + before.width() / 2, before.y() + before.height() / 2);
        scene.inputBatchEnded();
        frame();

        AccessibleNode after = stripNode();
        assertTrue(after.scroll().horizontalPercent() > 0,
                "a wheel notch over the strip moves it, and the facet is read from the field it "
                        + "moved rather than from a value cached when the tree was last built"
                        + describe(tree()));
        assertTrue(after.scroll().horizontalPercent() < 1, describe(tree()));
        assertEquals(viewSize, after.scroll().horizontalViewSize(), EPS,
                "scrolling changes where the viewport sits, not how big it is"
                        + describe(tree()));
        assertEquals(before.bounds(), after.bounds(),
                "the viewport does not move when its content scrolls" + describe(tree()));

        pane.setSelectedIndex(SIX.length - 1);
        frame();

        assertEquals(1, stripNode().scroll().horizontalPercent(), EPS,
                "selecting the last tab reveals it, which runs the strip to its end"
                        + describe(tree()));
    }

    @Test
    void rightToLeftTheOffsetIsStillMeasuredFromTheEdgeReadingStartsFrom() {
        bindTabs(SCENE_WIDTH, SIX);
        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        AccessibleNode list = stripNode();
        assertEquals(1.5 * list.x(), SCENE_WIDTH - list.width(), EPS,
                "the three controls swap ends keeping their order, so two of them sit before the "
                        + "viewport and one after it" + describe(tree()));
        assertEquals(strip().localToSceneX(), list.x(), describe(tree()));
        assertEquals(strip().width(), list.width(), describe(tree()));
        assertTrue(list.scroll().horizontallyScrollable(), describe(tree()));
        assertEquals(0, list.scroll().horizontalPercent(), EPS,
                "the strip is resting on its first tab, which is the start of the tab order in "
                        + "both directions; a mirrored percent would say it is scrolled to the end"
                        + describe(tree()));

        pane.setSelectedIndex(SIX.length - 1);
        frame();

        assertEquals(1, stripNode().scroll().horizontalPercent(), EPS,
                "and the last tab is the end of it in both directions" + describe(tree()));
    }

    @Test
    void aScrolledAwayHeaderLosesShowingAndTheTabListKeepsIt() {
        bindTabs(SCENE_WIDTH, SIX);
        AccessibleNode first = tabNodes().get(0);
        assertTrue(first.has(Accessible.State.SHOWING),
                "the selected tab starts in view" + describe(tree()));

        pane.setSelectedIndex(SIX.length - 1);
        frame();

        AccessibleNode list = stripNode();
        assertTrue(list.has(Accessible.State.SHOWING),
                "the tab list itself is on screen throughout" + describe(tree()));
        AccessibleNode scrolledAway = tabNodes().get(0);
        assertEquals(first.id(), scrolledAway.id(), describe(tree()));
        assertTrue(scrolledAway.has(Accessible.State.VISIBLE),
                "it is not hidden, it is out of the viewport" + describe(tree()));
        assertFalse(scrolledAway.has(Accessible.State.SHOWING),
                "the clip walk the toolkit already does, not a test the strip re-implements"
                        + describe(tree()));
        assertTrue(scrolledAway.x() < list.x(),
                "and its box overhangs the viewport, which is where it is" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the edges

    @Test
    void anEmptyPaneIsAnEmptyTabListThatClaimsNoSelection() {
        bindTabs(SCENE_WIDTH);

        AccessibleNode list = stripNode();
        assertEquals(List.of(), childrenOf(list),
                "an empty pane is legal and an empty tab list is honest" + describe(tree()));
        assertEquals(new SelectionFacet(false, false, 0), list.selection(),
                "required is false here and not the unconditional true a combo may write: a combo "
                        + "refuses an empty item list and this pane documents the opposite"
                        + describe(tree()));
        assertFacet(new ScrollFacet(0, 0, 1, 1, false, false), list.scroll(),
                "no headers, nothing to scroll" + describe(tree()));
    }

    @Test
    void aStripWithinTheFittingSlopAdvertisesNoScroll() {
        // Five tabs of four code points measure 360 in a pane three tenths of a point narrower:
        // inside the half-point slop the pane calls fitting, so every one of its scrolling paths
        // declines, while the bare arithmetic would still find three tenths of a point to travel.
        bindTabs(359.7f, "AAAA", "BBBB", "CCCC", "DDDD", "EEEE");

        float headersTotal = 0;
        for (Widget header : strip().children()) {
            headersTotal += header.width();
        }
        assertTrue(headersTotal > strip().width(),
                "the case is only worth anything while the arithmetic disagrees with the widget");
        assertFacet(new ScrollFacet(0, 0, 1, 1, false, false), stripNode().scroll(),
                "the facet is derived from the strip's own overflow predicate and not from the "
                        + "subtraction alone, so it can never advertise a movement the widget "
                        + "refuses to make" + describe(tree()));
    }

    @Test
    void aQuietFrameRepublishesNothingAboutTheStrip() {
        bindTabs(SCENE_WIDTH, SIX);
        long list = stripNode().id();

        frame();
        frame();

        assertEquals(List.of(), eventsFor(list),
                "the hook reads primitives off fields nothing touched, so two trees compare equal: "
                        + bridge.events);
    }
}
