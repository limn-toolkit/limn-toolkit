package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.accessibility.SelectionItemFacet;
import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.StringBundle;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@link TabbedPane}'s tab header becomes in the accessible tree: one tab node per header,
 * named by the caption it holds, carrying its position in the tab order and the number of tabs,
 * and offering the two selections a user can already make — a select that changes the tab and a
 * press that changes it and dives into the panel.
 *
 * <p>The survey row in ADR 039 §7 has the role, the facet and the name's provenance right and is
 * wrong or silent in five places against the source, each of which a case below pins. It offers
 * one verb where the widget has two distinct gestures, both landing in the same private path: the
 * arrows select and stay on the strip, while a click and Enter select and move the keyboard into
 * the panel. It says a header needs no accessor because it reads its caption "from inside its own
 * package", when the caption is the header's own field and the accessor it defers to already
 * exists on the pane. §1.5's illustration has the pane numbering its headers through the child
 * hook, and a header's parent is the strip, which is never asked, while the header holds its own
 * index and reads the rest off the enclosing pane. The row is silent on overflow, where a header
 * scrolled out of the viewport publishes its real rectangle without {@code SHOWING} and the
 * scene's own gate then refuses its select — the cost this file records rather than hides. And it
 * is silent on the active bit, which the standing rule would put on the selected header and the
 * source refuses: roving focus already makes that header the focused node, and a container takes
 * the first active node in its subtree, so a pane inside a list cell would hijack the list's
 * cursor.
 *
 * <p>Nothing here asserts that the tab nodes hang under a tab list. The strip declares nothing
 * yet, so the tree deletes it and the tabs hoist; that assertion belongs to the strip's own step.
 *
 * <p>Every case drives the pane's public setters on a bound scene, or calls the scene from where
 * a bridge stands, and reads what the scene published; nothing here builds a tree.
 */
class TabbedPaneTabHeaderAccessibilityTest extends AccessibleComponentTestBase {

    private static final I18nString REPORTS = new I18nString("tab.reports", "Reports");

    private static final Locale BRAZILIAN = Locale.forLanguageTag("pt-BR");

    /** One bundle for one key: everything else falls through to the English. */
    private static final StringBundle CAPTIONS = (key, locale) ->
            "tab.reports".equals(key) && BRAZILIAN.equals(locale) ? "Relatórios" : null;

    /** The pane under test. */
    private TabbedPane pane;

    /** The column it is bound in, for the cases that turn the whole subtree around. */
    private Column root;

    /** A tab stop outside the pane, so that "does not steal focus" has somewhere to be stolen from. */
    private Button elsewhere;

    /** Every index the pane's own listener was given, in order. */
    private final List<Integer> selections = new ArrayList<>();

    @AfterEach
    void resetLanguage() {
        I18n.removeBundle(CAPTIONS);
        I18n.setLocale(Locale.ENGLISH);
    }

    // ------------------------------------------------------------------------------ the fixture

    /**
     * A pane of captioned tabs over empty panels, inside a box of a known width so that whether
     * the strip overflows is the test's decision and not the ruler's.
     */
    private void bindTabs(float width, String... captions) {
        Widget[] panels = new Widget[captions.length];
        for (int i = 0; i < panels.length; i++) {
            panels[i] = new SizedBox(60, 60);
        }
        bindPanels(width, captions, panels);
    }

    /** The same, with the panels the caller wants behind the tabs. */
    private void bindPanels(float width, String[] captions, Widget[] panels) {
        pane = new TabbedPane();
        for (int i = 0; i < captions.length; i++) {
            pane.addTab(captions[i], panels[i]);
        }
        pane.onSelect(selections::add);
        root = new Column();
        root.add(new SizedBox(width, 120, pane));
        elsewhere = new Button("Elsewhere");
        root.add(elsewhere);
        bind(root);
        // The deterministic ruler, so a header is 10pt per code point plus its two paddings and
        // the overflow cases can be sized rather than hoped for. Bind renders its first frame
        // under whatever ruler the process has, so lay out again and start from there.
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
        selections.clear();
    }

    /**
     * The strip's header widgets, in add order. The strip is the pane's first child by
     * construction, and a header is reached the way an application would reach one, through the
     * public tree; nothing here is a way in that a reader does not have.
     */
    private List<Widget> headers() {
        return pane.children().get(0).children();
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

    /** @return the captions of every tab node, in tree order */
    private List<String> tabOrder() {
        List<String> names = new ArrayList<>();
        for (AccessibleNode node : tabNodes()) {
            names.add(node.name());
        }
        return names;
    }

    /** @return every selected-state event raised so far, in order */
    private List<AccessibleEvent> selectedEvents() {
        List<AccessibleEvent> found = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                    && event.state() == Accessible.State.SELECTED) {
                found.add(event);
            }
        }
        return found;
    }

    /** Moves the pointer to the centre of a widget's box, as a hover does. */
    private void hover(Widget widget) {
        scene.mouseMoved(widget.localToSceneX() + widget.width() / 2,
                widget.localToSceneY() + widget.height() / 2);
        scene.inputBatchEnded();
    }

    // ------------------------------------------------------------------------------ what it is

    @Test
    void everyHeaderPublishesOneTabNodeNamedByItsCaptionAndPlacedInTheTabOrder() {
        bindTabs(360, "Alpha", "Beta", "Gamma");

        assertEquals(List.of("Alpha", "Beta", "Gamma"), tabOrder(),
                "one node per header and not one for the selected header alone, which is what the "
                        + "deletion of an unfocusable header that declares nothing would leave"
                        + describe(tree()));
        AccessibleNode alpha = node("Alpha");
        assertEquals(Accessible.Role.TAB, alpha.role(), describe(tree()));
        assertEquals(Accessible.NameFrom.CONTENT, alpha.nameFrom(),
                "the caption is painted text, which decides the attribute one platform writes it "
                        + "into" + describe(tree()));
        assertEquals(new SelectionItemFacet(true, 1, 3), alpha.selectionItem(),
                "the first tab added is the selected one" + describe(tree()));
        assertEquals(new SelectionItemFacet(false, 2, 3), node("Beta").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(false, 3, 3), node("Gamma").selectionItem(),
                "the numbers are the pane's tab order and tab count" + describe(tree()));
        assertTrue(alpha.has(Accessible.State.SELECTED),
                "the builder derives the bit from the facet" + describe(tree()));
        assertFalse(alpha.has(Accessible.State.ACTIVE),
                "the selected header is never a container's active descendant: it is already the "
                        + "focused node whenever the strip holds the keyboard, and the first "
                        + "active node in a subtree is what an enclosing container would take"
                        + describe(tree()));
        assertTrue(alpha.actions().has(Accessible.Action.SELECT), describe(tree()));
        assertTrue(alpha.actions().has(Accessible.Action.PRESS),
                "the click's own gesture, without which a press from a reader routes nowhere"
                        + describe(tree()));
        assertFalse(alpha.actions().has(Accessible.Action.DESELECT),
                "a pane that holds tabs always has one selected" + describe(tree()));
        assertFalse(alpha.actions().has(Accessible.Action.TOGGLE), describe(tree()));
        assertFalse(alpha.actions().has(Accessible.Action.EXPAND), describe(tree()));
        assertNull(alpha.toggle(), describe(tree()));
        assertNull(alpha.value(), describe(tree()));
        assertNull(alpha.text(), describe(tree()));
        assertNull(alpha.expand(), describe(tree()));
        assertEquals("", alpha.description(), "nothing describes a tab but its caption"
                + describe(tree()));
        assertEquals(List.of(), alpha.relations(),
                "no membership relation, because the strip is this node's parent and the tree "
                        + "already says so; and no controller-for, because a panel wrapped in a "
                        + "padding has no node for one to resolve to" + describe(tree()));
        assertEquals(List.of(), childrenOf(alpha),
                "the icon and the caption are one control" + describe(tree()));
    }

    @Test
    void theBoxIsTheHeadersOwnRectangle() {
        bindTabs(360, "Alpha", "Beta");

        Widget header = headers().get(1);
        AccessibleNode beta = node("Beta");
        assertEquals(header.localToSceneX(), beta.x(), describe(tree()));
        assertEquals(header.localToSceneY(), beta.y(), describe(tree()));
        assertEquals(header.width(), beta.width(), describe(tree()));
        assertEquals(header.height(), beta.height(),
                "the walk's free box, which the strip has already placed" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the name

    @Test
    void aCaptionThatFollowsTheUiLanguageIsResolvedAgainWhenTheSubtreeMoves() {
        I18n.addBundle(CAPTIONS);
        I18n.setLocale(Locale.ENGLISH);
        pane = new TabbedPane().addTab(REPORTS, new SizedBox(60, 60));
        root = new Column();
        root.add(new SizedBox(360, 120, pane));
        bind(root);
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
        long id = node("Reports").id();

        root.setLocale(BRAZILIAN);
        frame();

        AccessibleNode translated = node("Relatórios");
        assertEquals(id, translated.id(), "the same tab, spoken in another language"
                + describe(tree()));
        assertEquals(BRAZILIAN, translated.locale(), describe(tree()));
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "the name is the held caption re-resolved under the subtree's language, which a "
                        + "string the hook resolved and cached could not be: " + bridge.events);
    }

    @Test
    void anEmptyCaptionPublishesAnEmptyNameUntilTheApplicationSuppliesOne() {
        bindTabs(360, "", "Beta");

        AccessibleNode blank = tabNodes().get(0);
        assertEquals(Accessible.Role.TAB, blank.role(),
                "a caption-less tab is still a tab" + describe(tree()));
        assertEquals("", blank.name(),
                "the caption is handed over empty rather than invented from somewhere; the walk's "
                        + "own default would take a tooltip, and a header never has one"
                        + describe(tree()));
        assertEquals(new SelectionItemFacet(true, 1, 2), blank.selectionItem(), describe(tree()));

        headers().get(0).setAccessibleName("Overview");
        frame();

        AccessibleNode named = node("Overview");
        assertEquals(blank.id(), named.id(), "the same tab" + describe(tree()));
        assertEquals(Accessible.NameFrom.EXPLICIT, named.nameFrom(),
                "an application's name wins over anything the widget derived" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the set

    @Test
    void movingTheSelectionSwapsTheBitsAndRebuildsNothing() {
        bindTabs(360, "Alpha", "Beta", "Gamma");
        long alpha = node("Alpha").id();
        long beta = node("Beta").id();
        long gamma = node("Gamma").id();

        pane.setSelectedIndex(2);
        frame();

        assertEquals(new SelectionItemFacet(false, 1, 3), node("Alpha").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(true, 3, 3), node("Gamma").selectionItem(),
                describe(tree()));
        assertEquals(alpha, node("Alpha").id(), "identity is minted on the widget, and the roving "
                + "focusable flag does not re-mint it" + describe(tree()));
        assertEquals(beta, node("Beta").id(), describe(tree()));
        assertEquals(gamma, node("Gamma").id(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "a selection is not a structure change: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED), bridge.events
                .toString());
        List<AccessibleEvent> raised = selectedEvents();
        assertEquals(2, raised.size(), "two nodes moved, two events: " + bridge.events);
        for (AccessibleEvent event : raised) {
            if (event.nodeId() == gamma) {
                assertEquals(Boolean.TRUE, event.newValue(), bridge.events.toString());
            } else {
                assertEquals(alpha, event.nodeId(), bridge.events.toString());
                assertEquals(Boolean.FALSE, event.newValue(), bridge.events.toString());
            }
        }
    }

    @Test
    void addingATabRenumbersTheSetAndRenamesNobody() {
        bindTabs(360, "Alpha", "Beta", "Gamma");
        long alpha = node("Alpha").id();
        long beta = node("Beta").id();
        long gamma = node("Gamma").id();

        pane.addTab("Delta", new SizedBox(60, 60));
        frame();

        assertEquals(List.of("Alpha", "Beta", "Gamma", "Delta"), tabOrder(),
                "the new tab is last, because a header's index is final and the pane has no way "
                        + "to remove one" + describe(tree()));
        assertEquals(alpha, node("Alpha").id(), describe(tree()));
        assertEquals(beta, node("Beta").id(), describe(tree()));
        assertEquals(gamma, node("Gamma").id(), describe(tree()));
        assertEquals(new SelectionItemFacet(true, 1, 4), node("Alpha").selectionItem(),
                "adding a tab lays the strip out again, which is what buys this republish"
                        + describe(tree()));
        assertEquals(new SelectionItemFacet(false, 2, 4), node("Beta").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(false, 3, 4), node("Gamma").selectionItem(),
                describe(tree()));
        assertEquals(new SelectionItemFacet(false, 4, 4), node("Delta").selectionItem(),
                describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NAME_CHANGED),
                "a tab arriving renames nobody: " + bridge.events);
    }

    // ------------------------------------------------------------------------------ the focus

    @Test
    void theStripIsOneTabStopAndTheTwoFreeVerbsFollowIt() {
        bindTabs(360, "Alpha", "Beta", "Gamma");

        List<String> focusable = new ArrayList<>();
        for (AccessibleNode node : tabNodes()) {
            if (node.has(Accessible.State.FOCUSABLE)) {
                focusable.add(node.name());
            }
        }
        assertEquals(List.of("Alpha"), focusable,
                "roving focus: the strip is one tab stop and the tree offers exactly the one the "
                        + "keyboard reaches" + describe(tree()));
        assertTrue(node("Alpha").actions().has(Accessible.Action.FOCUS), describe(tree()));
        assertTrue(node("Alpha").actions().has(Accessible.Action.SCROLL_INTO_VIEW),
                "the walk grants both to a focusable widget, and the hook writes neither"
                        + describe(tree()));
        assertFalse(node("Beta").actions().has(Accessible.Action.FOCUS),
                "an unselected header is not a tab stop, so it is offered no focus"
                        + describe(tree()));
        assertFalse(node("Beta").actions().has(Accessible.Action.SCROLL_INTO_VIEW),
                describe(tree()));

        pane.setSelectedIndex(1);
        frame();

        focusable.clear();
        for (AccessibleNode node : tabNodes()) {
            if (node.has(Accessible.State.FOCUSABLE)) {
                focusable.add(node.name());
            }
        }
        assertEquals(List.of("Beta"), focusable, describe(tree()));
        assertTrue(node("Beta").actions().has(Accessible.Action.FOCUS), describe(tree()));
        assertFalse(node("Alpha").actions().has(Accessible.Action.FOCUS),
                "the tab stop moved with the selection, and the verbs with it" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the verbs

    @Test
    void aSelectFromTheBridgeChangesTheTabAndLeavesTheKeyboardWhereItWas() throws Exception {
        bindTabs(360, "Alpha", "Beta", "Gamma");
        scene.requestFocus(elsewhere);
        frame();
        bridge.events.clear();

        assertTrue(perform(node("Beta").id(), Accessible.Action.SELECT, Accessible.Argument.NONE),
                "the identifier resolves in the published tree, so the post is accepted");
        frame();

        assertEquals(1, pane.selectedIndex(), "a reader's select is the pane's own select");
        assertEquals(List.of(1), selections,
                "the application hears exactly what a click tells it");
        assertSame(elsewhere, scene.focusedWidget(),
                "a select is not a dive: the keyboard stays where the user left it");
        assertTrue(node("Beta").has(Accessible.State.SELECTED), describe(tree()));
        assertEquals(2, selectedEvents().size(), "two nodes moved: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a select is not an invocation, on any platform: " + bridge.events);
    }

    @Test
    void aPressDivesIntoThePanelAndIsAcknowledged() throws Exception {
        Column panel = new Column();
        Button inside = new Button("Inside");
        panel.add(inside);
        bindPanels(360, new String[] {"Alpha", "Beta"},
                new Widget[] {new SizedBox(60, 60), panel});
        scene.requestFocus(elsewhere);
        frame();
        bridge.events.clear();

        long beta = node("Beta").id();
        assertTrue(perform(beta, Accessible.Action.PRESS, Accessible.Argument.NONE));
        frame();

        assertEquals(1, pane.selectedIndex(), describe(tree()));
        assertEquals(List.of(1), selections);
        assertSame(inside, scene.focusedWidget(),
                "a press is the click's own gesture: select, then land on the panel's first "
                        + "focusable descendant");
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.INVOKED),
                "a press an assistive technology made is acknowledged: " + bridge.events);
        assertEquals(beta, bridge.events.stream()
                .filter(event -> event.type() == AccessibleEvent.Type.INVOKED)
                .findFirst().orElseThrow().nodeId(), bridge.events.toString());
    }

    @Test
    void selectingTheTabThatIsAlreadySelectedIsAcceptedAndSaysNothing() throws Exception {
        bindTabs(360, "Alpha", "Beta");

        assertTrue(perform(node("Alpha").id(), Accessible.Action.SELECT, Accessible.Argument.NONE),
                "accepted, as the pointer's own re-selection is");
        frame();

        assertEquals(0, pane.selectedIndex());
        assertEquals(List.of(), selections,
                "the pane's early return is what keeps two controls bound to each other from "
                        + "recursing, and a reader takes the same path a click does");
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.INVOKED), bridge.events.toString());
    }

    @Test
    void aVerbItDoesNotOfferIsRefused() throws Exception {
        bindTabs(360, "Alpha", "Beta");

        perform(node("Beta").id(), Accessible.Action.TOGGLE, Accessible.Argument.NONE);
        perform(node("Beta").id(), Accessible.Action.DESELECT, Accessible.Argument.NONE);
        perform(node("Beta").id(), Accessible.Action.EXPAND, Accessible.Argument.NONE);
        perform(node("Beta").id(), Accessible.Action.SET_VALUE, new Accessible.Argument.OfValue(1));
        frame();

        assertEquals(0, pane.selectedIndex(), "none of the four is a selection");
        assertEquals(List.of(), selections);
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());
    }

    @Test
    void aDisabledPaneKeepsItsTabsAndRefusesTheirSelect() throws Exception {
        bindTabs(360, "Alpha", "Beta", "Gamma");
        pane.setEnabled(false);
        frame();
        bridge.events.clear();

        for (AccessibleNode node : tabNodes()) {
            assertFalse(node.has(Accessible.State.ENABLED),
                    "the bit is inherited down the walk" + describe(tree()));
            assertFalse(node.has(Accessible.State.FOCUSABLE),
                    "and a disabled tab stop is not one" + describe(tree()));
            assertTrue(node.has(Accessible.State.VISIBLE),
                    "a disabled tab is heard as disabled rather than vanishing" + describe(tree()));
            assertTrue(node.has(Accessible.State.SHOWING), describe(tree()));
        }

        perform(node("Beta").id(), Accessible.Action.SELECT, Accessible.Argument.NONE);
        frame();

        assertEquals(0, pane.selectedIndex(),
                "the scene walks the widget and every parent for the enabled flag before it calls "
                        + "the hook, which is why the hook carries no guard of its own");
        assertEquals(List.of(), selections);
        assertEquals(List.of(), selectedEvents(), bridge.events.toString());
    }

    // ---------------------------------------------------------------------------- the overflow

    /**
     * The cost this step records rather than hides. In an overflowing strip a header scrolled out
     * of the viewport is published where it really is and without {@code SHOWING}, and the scene's
     * action gate refuses an action whose owner is not showing — so the tabs a reader most needs
     * are the ones its select cannot reach until something reveals them. There is no verb here
     * that could rescue them: scroll-into-view is the walk's and is granted to a focusable widget,
     * and an unselected header is not one. The routes back are in the source: selecting a visible
     * neighbour scrolls the run, and the strip's list chevron opens a menu of every tab.
     */
    @Test
    void anOverflowingStripPublishesRealBoxesAndRefusesTheTabItHasScrolledAway() throws Exception {
        bindTabs(200, "Tab A", "Tab B", "Tab C", "Tab D", "Tab E", "Tab F", "Tab G", "Tab H");
        Widget strip = pane.children().get(0);

        assertTrue(node("Tab A").has(Accessible.State.SHOWING),
                "the first tab is inside the viewport" + describe(tree()));
        AccessibleNode away = node("Tab G");
        Widget header = headers().get(6);
        assertEquals(header.localToSceneX(), away.x(),
                "the real rectangle, not a clamped or a zeroed one: the strip lays its headers "
                        + "out with the scroll offset folded in and moves them the moment it "
                        + "scrolls" + describe(tree()));
        assertEquals(header.width(), away.width(), describe(tree()));
        assertTrue(away.x() >= strip.localToSceneX() + strip.width(),
                "and that rectangle really is outside the viewport" + describe(tree()));
        assertTrue(away.has(Accessible.State.VISIBLE), describe(tree()));
        assertFalse(away.has(Accessible.State.SHOWING),
                "the clip walk already intersects with every clipping ancestor, and the strip "
                        + "clips" + describe(tree()));

        assertTrue(perform(away.id(), Accessible.Action.SELECT, Accessible.Argument.NONE),
                "the identifier is in the published tree, so the answer to the platform is "
                        + "accepted; the refusal is a precondition re-checked on arrival");
        frame();

        assertEquals(0, pane.selectedIndex(),
                "an action whose owner is not showing is refused, which is the cost of an "
                        + "overflowing strip and not a defect in the hook");
        assertEquals(List.of(), selections);

        pane.setSelectedIndex(7);
        frame();
        selections.clear();
        bridge.events.clear();

        AccessibleNode revealed = node("Tab G");
        assertEquals(away.id(), revealed.id(), "the same tab" + describe(tree()));
        assertTrue(revealed.has(Accessible.State.SHOWING),
                "selecting a tab scrolls it into view, and its neighbour comes with it"
                        + describe(tree()));

        assertTrue(perform(revealed.id(), Accessible.Action.SELECT, Accessible.Argument.NONE));
        frame();

        assertEquals(6, pane.selectedIndex(),
                "the same select the gate refused a moment ago now runs");
        assertEquals(List.of(6), selections);
    }

    // ------------------------------------------------------------------------- reading order

    @Test
    void readingRightToLeftKeepsTheTabOrderAndMovesTheBoxes() {
        bindTabs(360, "Alpha", "Beta", "Gamma");
        List<AccessibleNode> ltr = tabNodes();
        assertTrue(ltr.get(0).x() < ltr.get(1).x() && ltr.get(1).x() < ltr.get(2).x(),
                "the run advances from the edge reading starts on" + describe(tree()));

        root.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(List.of("Alpha", "Beta", "Gamma"), tabOrder(),
                "tree order is the order the tabs were added in, which is the tab order in both "
                        + "directions; nothing in the hook reads a direction" + describe(tree()));
        List<AccessibleNode> rtl = tabNodes();
        assertTrue(rtl.get(0).x() > rtl.get(1).x() && rtl.get(1).x() > rtl.get(2).x(),
                "the strip reflected its boxes, so the first tab is on the right" + describe(tree()));
        assertEquals(new SelectionItemFacet(true, 1, 3), rtl.get(0).selectionItem(),
                "a position in the tab order and never a screen side" + describe(tree()));
        assertEquals(new SelectionItemFacet(false, 3, 3), rtl.get(2).selectionItem(),
                describe(tree()));
        for (int i = 0; i < 3; i++) {
            assertEquals(headers().get(i).localToSceneX(), rtl.get(i).x(), describe(tree()));
        }
    }

    // ------------------------------------------------------------------------- what it costs

    @Test
    void aQuietStripPublishesNothingAndAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindTabs(360, "Alpha", "Beta", "Gamma");
        Widget header = headers().get(0);
        scene.requestFocus(header);
        frame();
        hover(header);
        frame();

        // The hover and the focus fades are wall-clock transitions that damage the header on
        // every frame they run for, and a measurement taken while one is mid-flight measures the
        // animation. Frame past both before measuring.
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(400);
        while (System.nanoTime() < until) {
            header.invalidate();
            frame();
        }
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            header.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        // A caption resolved inside the hook is one string per header per damaged frame spent
        // concluding that nothing moved, and every frame of a hover fade is such a frame. This is
        // the only place that is visible.
        long withAReaderAttached = AllocationProbe.leastAllocatedBy(() -> {
            header.invalidate();
            frame();
        }, 60);

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");
        assertTrue(bridge.events.isEmpty(), bridge.events.toString());

        bridge.listening = false;
        long withNobodyListening = AllocationProbe.leastAllocatedBy(() -> {
            header.invalidate();
            frame();
        }, 60);

        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a tab that did not move must cost no memory: the name is the held "
                        + "caption compared by reference, the two numbers are read off the pane, "
                        + "the role is an enum and the two verbs are bits");
    }
}
