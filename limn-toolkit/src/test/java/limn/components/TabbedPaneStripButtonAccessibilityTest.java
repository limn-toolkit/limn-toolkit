package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link TabbedPane}'s three overflow controls become in the accessible tree: one button
 * each, named by the tooltip each now holds, offering the press its own click performs, and the
 * all-tabs one saying that it opens a menu.
 *
 * <p>The survey row in ADR 039 §7 gives this class a role, a press and an em-dash under synthetic
 * children, and all three are right. It is wrong in one place and silent in five, and a case below
 * pins each. <b>Wrong:</b> it treats three instances as one shape, where the all-tabs button opens
 * a popup of every tab and owes the state and the verb that say so. <b>Silent</b> on the dead side,
 * where the pane disables the chevron at the end of its travel and the scene's own gate then
 * refuses a press that would move nothing; on visibility, since all three are hidden while the
 * strip fits, which is most panes in most applications; on why these are nodes at all, which is
 * the only hard question here and has three answers; on the relations, which must <em>not</em> be
 * declared because the walk writes both ends from the popup's host link; and on mirroring, where
 * the names have to name the tab order, because the pane puts the previous-tabs chevron on the
 * edge reading starts from and turns the glyph around with it.
 *
 * <p>This file is also where a promise the tab header's own step left open is kept. A header the
 * strip has scrolled out of the viewport publishes without {@code SHOWING} and cannot be selected
 * where it stands; the two routes back are in the source, and one of them is the chevron a case
 * below presses.
 *
 * <p>Every case drives the pane's public API on a bound scene, or calls the scene from where a
 * bridge stands, and reads back what the scene published. Nothing constructs a node, and nothing
 * calls a hook.
 */
class TabbedPaneStripButtonAccessibilityTest extends AccessibleComponentTestBase {

    /** The scene the base binds is this wide, which is what the overflow cases are sized against. */
    private static final float SCENE_WIDTH = 400;

    /**
     * Six of these overflow a four-hundred-point pane and five do not: under the deterministic
     * ruler a caption of four code points measures 40, and a header is that plus its two paddings.
     */
    private static final String[] SIX = {"AAAA", "BBBB", "CCCC", "DDDD", "EEEE", "FFFF"};

    /** Comparing two ratios that were computed as floats and widened. */
    private static final double EPS = 1e-6;

    /** The pane under test. */
    private TabbedPane pane;

    /** The column it is bound in, for the case that turns the whole subtree around. */
    private Column root;

    /** What the process asked for before this class changed it; see {@link #inSceneMenus}. */
    private DisplayMode menusWere;

    /**
     * A menu in a window of its own cannot be opened headlessly, and the presentation is a
     * process-wide default rather than a property of the menu this pane builds, so it is set here
     * and put back afterwards.
     */
    @BeforeEach
    void inSceneMenus() {
        menusWere = PopupMenu.defaultDisplayMode();
        PopupMenu.setDefaultDisplayMode(DisplayMode.IN_SCENE);
    }

    @AfterEach
    void restoreMenus() {
        PopupMenu.setDefaultDisplayMode(menusWere);
    }

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
     * The three controls and the strip, reached the way an application would reach them: they are
     * the pane's first four children, in the order its constructor adds them. Used only to compare
     * the tree against a widget's own box, never as a way into a hook.
     */
    private Widget strip() {
        return pane.children().get(0);
    }

    private Widget previous() {
        return pane.children().get(1);
    }

    private Widget next() {
        return pane.children().get(2);
    }

    private Widget all() {
        return pane.children().get(3);
    }

    /**
     * The three nodes, by the name the toolkit gives them. Resolved rather than written out, so
     * that the case says "the string the component holds" and passes under whatever language the
     * machine running it reports.
     */
    private AccessibleNode previousNode() {
        return node(ComponentStrings.TAB_PREVIOUS.get());
    }

    private AccessibleNode nextNode() {
        return node(ComponentStrings.TAB_NEXT.get());
    }

    private AccessibleNode allNode() {
        return node(ComponentStrings.TAB_LIST_ALL.get());
    }

    /** @return the tab list's node, which is what the two chevrons move */
    private AccessibleNode stripNode() {
        return node(Accessible.Role.TAB_LIST);
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

    /** @return the node carrying {@code id} */
    private AccessibleNode nodeWith(long id) {
        AccessibleTree tree = tree();
        int index = tree.indexOf(id);
        assertTrue(index >= 0, "the tree holds no node " + id + describe(tree));
        return tree.node(index);
    }

    /** @return the identifier {@code kind} resolves to on this node, or zero when it has none */
    private long targetOf(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation.target();
            }
        }
        return 0;
    }

    /** Moves the pointer to the centre of a widget's box, as a hover does. */
    private void hover(Widget widget) {
        scene.mouseMoved(widget.localToSceneX() + widget.width() / 2,
                widget.localToSceneY() + widget.height() / 2);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------------------------ what they are

    /**
     * Three buttons, three names, and the verb each one's own click takes — with the menu the
     * all-tabs button opens declared on that button alone.
     *
     * <p>Without a hook here there is no button in this tree at all: the class paints and declares
     * nothing, so the transparency predicate deletes all three, the chevrons and the whole-tab-list
     * route disappear from what a reader can reach, and the walk logs a warning naming a toolkit
     * class in an application's log.
     */
    @Test
    void theThreeControlsPublishNamedButtonsWithTheVerbTheirClickTakes() {
        bindTabs(SCENE_WIDTH, SIX);

        for (AccessibleNode node : List.of(previousNode(), nextNode(), allNode())) {
            assertEquals(Accessible.Role.BUTTON, node.role(), describe(tree()));
            assertEquals(Accessible.NameFrom.TOOLTIP, node.nameFrom(),
                    "the name is the tooltip the walk takes when nothing else supplied one, which "
                            + "is why the hook declares none: declaring the same string there "
                            + "would have the walk add it a second time as the description"
                            + describe(tree()));
            assertEquals("", node.description(),
                    "and the description stays empty, because the tooltip was spent on the name"
                            + describe(tree()));
            assertTrue(node.actions().has(Accessible.Action.PRESS), describe(tree()));
            assertFalse(node.actions().has(Accessible.Action.FOCUS),
                    "none of the three is a tab stop: the keyboard reaches the strip through the "
                            + "roving header, so the walk grants neither of its two free verbs"
                            + describe(tree()));
            assertFalse(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW), describe(tree()));
            assertFalse(node.has(Accessible.State.FOCUSABLE), describe(tree()));
            assertEquals(List.of(), childrenOf(node),
                    "two lines and a fade, and nothing inside the box a reader could address"
                            + describe(tree()));
            assertEquals(List.of(), node.relations(),
                    "the opener pair is the walk's, written from the host link the popup sets, and "
                            + "a mirror declared here would put two of the same relation on one "
                            + "node" + describe(tree()));
            assertNull(node.toggle(), describe(tree()));
            assertNull(node.value(), describe(tree()));
            assertNull(node.text(), describe(tree()));
            assertNull(node.expand(),
                    "no expanded bit: the menu is built, opened and forgotten in one call, so this "
                            + "widget could not answer whether the one it opened is still up"
                            + describe(tree()));
            assertNull(node.selectionItem(), describe(tree()));
            assertNull(node.scroll(),
                    "the strip's own node carries the scroll facet" + describe(tree()));
        }

        assertEquals(Set.of(Accessible.Action.PRESS), previousNode().actions().actions(),
                "a chevron scrolls and does nothing else" + describe(tree()));
        assertEquals(Set.of(Accessible.Action.PRESS), nextNode().actions().actions(),
                describe(tree()));
        assertEquals(Set.of(Accessible.Action.PRESS, Accessible.Action.SHOW_MENU),
                allNode().actions().actions(),
                "both, and not the menu verb alone: one platform vends its invoke pattern from the "
                        + "press and would leave a menu-only button unactuable, and the other two "
                        + "route the more precise word" + describe(tree()));
        assertFalse(previousNode().has(Accessible.State.HAS_POPUP), describe(tree()));
        assertFalse(nextNode().has(Accessible.State.HAS_POPUP), describe(tree()));
        assertTrue(allNode().has(Accessible.State.HAS_POPUP),
                "the one control whose whole purpose is to open a menu says so before it is pressed"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the geometry

    /**
     * The box is the square the pane laid out, which on a narrow pane is not the square the button
     * measured.
     *
     * <p>{@code onMeasure} answers a strip-height square; the pane caps each control at a sixth of
     * its width, so below six strip heights the two disagree — and that is precisely the pane these
     * controls were shrunk for. A box derived from the measure would draw a reader's cursor over
     * the tabs.
     */
    @Test
    void theBoxIsTheSquareThePaneLaidOutAndNotTheSquareTheButtonMeasured() {
        bindTabs(150, SIX);

        for (Widget widget : List.of(previous(), next(), all())) {
            AccessibleNode node = node(widget.tooltipSource().get());
            assertEquals(widget.localToSceneX(), node.x(), describe(tree()));
            assertEquals(widget.localToSceneY(), node.y(), describe(tree()));
            assertEquals(widget.width(), node.width(), describe(tree()));
            assertEquals(widget.height(), node.height(), describe(tree()));
        }

        AccessibleNode chevron = previousNode();
        assertTrue(chevron.width() < chevron.height(),
                "a hundred and fifty points is under six strip heights, so the laid-out control is "
                        + "narrower than the square it measures; publishing the measure here would "
                        + "be wrong by the difference" + describe(tree()));
        assertEquals(chevron.x() + chevron.width(), stripNode().x(), EPS,
                "the viewport starts where the leading chevron ends" + describe(tree()));
        assertEquals(3 * chevron.width(), 150 - stripNode().width(), EPS,
                "and the three squares are what the pane spent on them, which is the mirror of the "
                        + "strip's own assertion from the other side" + describe(tree()));
    }

    /**
     * Turned around, the ends swap and the names do not.
     *
     * <p>The chevrons point at the start and the end of the tab order, and the pane puts the
     * previous-tabs one on the edge reading starts from, which is the right in a right-to-left
     * pane. A name like "scroll left" would be true where it was written and false in half the
     * world, and nothing else in this suite would catch it.
     */
    @Test
    void readingRightToLeftSwapsTheEndsAndKeepsTheNamesAndTheTreeOrder() {
        bindTabs(SCENE_WIDTH, SIX);
        assertTrue(previousNode().x() < stripNode().x(), describe(tree()));
        assertTrue(stripNode().x() < nextNode().x(), describe(tree()));
        assertTrue(nextNode().x() < allNode().x(),
                "left to right: the previous chevron first, then the viewport, then the next "
                        + "chevron and the all-tabs button, that one outermost" + describe(tree()));
        List<Long> order = List.of(previousNode().id(), nextNode().id(), allNode().id());

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(order, List.of(previousNode().id(), nextNode().id(), allNode().id()),
                "tree order is the order the pane adds its children in, which no direction moves"
                        + describe(tree()));
        assertTrue(allNode().x() < nextNode().x(), describe(tree()));
        assertTrue(nextNode().x() < stripNode().x(), describe(tree()));
        assertTrue(stripNode().x() < previousNode().x(),
                "the four boxes are reflected about the pane's centre, so the control that scrolls "
                        + "toward the first tab is now on the right" + describe(tree()));
        assertEquals(ComponentStrings.TAB_PREVIOUS.get(), previousNode().name(),
                "and the names still name the tab order: nothing in the hook reads a direction, "
                        + "because there is no direction in what these are called"
                        + describe(tree()));
        assertEquals(ComponentStrings.TAB_NEXT.get(), nextNode().name(), describe(tree()));
        assertEquals(ComponentStrings.TAB_LIST_ALL.get(), allNode().name(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ the verb

    /**
     * The chevron at the end of its travel is published disabled, and the scene refuses its press.
     *
     * <p>That bit is the layout's — the pane writes it as it places the controls — and the gate is
     * the scene's, which is why the hook carries no enabled guard of its own. A hook that
     * re-derived the answer, or an {@code activate()} reached by some path that skipped the clamp,
     * would show up here as a strip that moved when it had nowhere to go.
     */
    @Test
    void theDeadSideChevronIsPublishedDisabledAndItsPressIsRefused() throws Exception {
        bindTabs(SCENE_WIDTH, SIX);

        assertFalse(previousNode().has(Accessible.State.ENABLED),
                "at rest the strip is on its first tab and there is nothing before it"
                        + describe(tree()));
        assertTrue(nextNode().has(Accessible.State.ENABLED), describe(tree()));

        float restingX = strip().children().get(0).localToSceneX();
        assertTrue(perform(previousNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE),
                "the identifier is in the published tree, so the answer to the platform is "
                        + "accepted; the refusal is a precondition re-checked on arrival");
        frame();

        assertEquals(0, stripNode().scroll().horizontalPercent(), EPS,
                "and nothing moved" + describe(tree()));
        assertEquals(restingX, strip().children().get(0).localToSceneX(), describe(tree()));

        pane.setSelectedIndex(SIX.length - 1);
        frame();

        assertTrue(previousNode().has(Accessible.State.ENABLED),
                "selecting the last tab runs the strip to its end, and the two bits swap"
                        + describe(tree()));
        assertFalse(nextNode().has(Accessible.State.ENABLED), describe(tree()));

        assertTrue(perform(nextNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(1, stripNode().scroll().horizontalPercent(), EPS,
                "the dead side is dead at both ends" + describe(tree()));

        assertTrue(perform(previousNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertTrue(stripNode().scroll().horizontalPercent() < 1,
                "and the live one is live" + describe(tree()));
    }

    /**
     * A press on the leading chevron scrolls the strip exactly as a click on it does, which is the
     * route back to a tab the action gate refuses.
     *
     * <p>The tab header's own step records the cost and leaves this open: a header scrolled out of
     * the viewport publishes its real rectangle without {@code SHOWING}, and the scene refuses to
     * select it where it stands. Two routes back exist in the source, and this is the one that does
     * not require selecting something else first.
     */
    @Test
    void aPressOnAChevronScrollsTheStripTheSameThreeQuartersAClickDoes() throws Exception {
        bindTabs(SCENE_WIDTH, SIX);
        pane.setSelectedIndex(SIX.length - 1);
        frame();

        AccessibleNode away = node("AAAA");
        assertFalse(away.has(Accessible.State.SHOWING),
                "the first tab is off the leading end of the viewport now" + describe(tree()));
        double scrolled = stripNode().scroll().horizontalPercent();

        assertTrue(perform(previousNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertTrue(stripNode().scroll().horizontalPercent() < scrolled,
                "the press reached the pane's own scroll, with its clamp and its relayout"
                        + describe(tree()));
        AccessibleNode back = node("AAAA");
        assertEquals(away.id(), back.id(), "the same tab" + describe(tree()));
        assertTrue(back.has(Accessible.State.SHOWING),
                "and a tab no assistive technology could operate a moment ago is on screen, which "
                        + "is what makes this control load-bearing rather than decorative"
                        + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the popup

    /**
     * Pressing the all-tabs button opens the menu, and the menu names this button as its opener.
     *
     * <p>Both halves of that link are the walk's, written from the inheritance host the popup
     * installs — and both land here only because this widget is a published node. Deleted, the
     * climb from the host runs past the deleted pane onto whatever container is above it, and the
     * menu announces as the popup of something that did not open it.
     *
     * <p>Nothing here asserts what the surface's node <em>is</em>. That widget is still undescribed
     * and its own step will change the answer; what this case is about is which node the pair
     * resolves to.
     */
    @Test
    void aPressOnTheListChevronOpensTheMenuThatNamesThisButtonAsItsOpener() throws Exception {
        bindTabs(SCENE_WIDTH, SIX);
        assertEquals(List.of(), allNode().relations(),
                "no menu, no relation" + describe(tree()));
        int before = tree().nodeCount();

        assertTrue(perform(allNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertTrue(tree().nodeCount() > before,
                "the overlay and its rows joined the tree" + describe(tree()));
        AccessibleNode button = allNode();
        assertEquals(1, button.relations().size(),
                "one relation and not two: the mirror is the walk's" + describe(tree()));
        long surface = targetOf(button, Accessible.Relation.CONTROLLER_FOR);
        assertEquals(tree().focused(), surface,
                "the button controls the layer that took the keyboard when the menu opened"
                        + describe(tree()));
        assertEquals(0, nodeWith(surface).parent(),
                "which is an overlay, hanging under the window node" + describe(tree()));
        assertEquals(button.id(), targetOf(nodeWith(surface), Accessible.Relation.POPUP_FOR),
                "and the menu points back at the button rather than at a container that did not "
                        + "open it" + describe(tree()));
    }

    /** The menu verb reaches the same menu, and the two chevrons refuse it. */
    @Test
    void theMenuVerbOpensTheSameMenuAndIsRefusedOnAChevron() throws Exception {
        bindTabs(SCENE_WIDTH, SIX);

        assertTrue(perform(previousNode().id(), Accessible.Action.SHOW_MENU,
                Accessible.Argument.NONE));
        frame();
        assertEquals(List.of(), previousNode().relations(),
                "a chevron has no menu, and the hook says so rather than scrolling instead"
                        + describe(tree()));
        assertEquals(0, stripNode().scroll().horizontalPercent(), EPS, describe(tree()));

        assertTrue(perform(allNode().id(), Accessible.Action.SHOW_MENU, Accessible.Argument.NONE));
        frame();

        AccessibleNode button = allNode();
        long surface = targetOf(button, Accessible.Relation.CONTROLLER_FOR);
        assertEquals(tree().focused(), surface,
                "the second verb reaches the same private path the first one does"
                        + describe(tree()));
        assertEquals(button.id(), targetOf(nodeWith(surface), Accessible.Relation.POPUP_FOR),
                describe(tree()));
    }

    // ------------------------------------------------------------------------------ no overflow

    /**
     * A strip that fits still publishes three buttons, and none of them is on screen.
     *
     * <p>Declaring the role and the verb only while the strip overflows would make three nodes
     * appear and disappear on every resize past the fitting point — a structure change and three
     * destroyed nodes for a change that is not structural, which a reader experiences as elements
     * going invalid. The inherited bits already say the controls are not there.
     */
    @Test
    void aStripThatFitsStillPublishesThreeButtonsAndNoneOfThemIsOnScreen() throws Exception {
        bindTabs(SCENE_WIDTH, "Alpha", "Beta");

        List<Long> ids = List.of(previousNode().id(), nextNode().id(), allNode().id());
        for (AccessibleNode node : List.of(previousNode(), nextNode(), allNode())) {
            assertEquals(Accessible.Role.BUTTON, node.role(), describe(tree()));
            assertFalse(node.has(Accessible.State.VISIBLE), describe(tree()));
            assertFalse(node.has(Accessible.State.SHOWING),
                    "the layout hides all three while everything fits, and the stale box is "
                            + "harmless for the reason an unselected panel's is" + describe(tree()));
        }

        assertTrue(perform(nextNode().id(), Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();
        assertEquals(0, stripNode().scroll().horizontalPercent(), EPS,
                "a strip that fits has nowhere to go" + describe(tree()));

        bridge.events.clear();
        for (String caption : SIX) {
            pane.addTab(caption, new SizedBox(60, 60));
        }
        frame();

        assertEquals(ids, List.of(previousNode().id(), nextNode().id(), allNode().id()),
                "the same three nodes across the fitting point" + describe(tree()));
        for (long id : ids) {
            for (AccessibleEvent event : eventsFor(id)) {
                assertNotEquals(AccessibleEvent.Type.NODE_DESTROYED, event.type(),
                        "only their bits moved: " + bridge.events);
                assertNotEquals(AccessibleEvent.Type.STRUCTURE_CHANGED, event.type(),
                        "only their bits moved: " + bridge.events);
                assertNotEquals(AccessibleEvent.Type.NAME_CHANGED, event.type(),
                        "only their bits moved: " + bridge.events);
            }
        }
        assertTrue(previousNode().has(Accessible.State.VISIBLE),
                "and they are on screen now" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ what it costs

    /**
     * A chevron nobody touched publishes nothing and allocates nothing.
     *
     * <p>The name is a constant the widget holds and the builder compares by reference, so there is
     * no counter to invent here. Resolving it inside the hook instead would spend one string per
     * chevron per damaged frame concluding that nothing moved, and a hover fade damages one on
     * every frame it runs for.
     */
    @Test
    void aQuietChevronPublishesNothingAndAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindTabs(SCENE_WIDTH, SIX);
        Widget chevron = next();
        hover(chevron);
        frame();

        // The hover fade is a timed transition that damages the control on every frame it
        // runs for, and a measurement taken while one is mid-flight measures the animation. Move
        // time past it before measuring.
        settleAnimations(chevron);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            chevron.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            chevron.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            chevron.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a control that did not move must cost no memory: the name is the held "
                        + "constant compared by reference, the role is an enum and the verbs are "
                        + "bits");
    }

    // ------------------------------------------------------------------------------ the override

    /** An application's own name wins, which is what makes the tooltip a default and not a fact. */
    @Test
    void anApplicationCanRenameAChevronAndItsOwnNameWins() {
        bindTabs(SCENE_WIDTH, SIX);
        long id = allNode().id();

        all().setAccessibleName("Show all tabs");
        frame();

        AccessibleNode renamed = node("Show all tabs");
        assertEquals(id, renamed.id(), "the same control, renamed" + describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, renamed.nameFrom(),
                "an application's name is not a tooltip" + describe(tree()));
        assertTrue(renamed.has(Accessible.State.HAS_POPUP),
                "and renaming it says nothing about what it does" + describe(tree()));
    }
}
