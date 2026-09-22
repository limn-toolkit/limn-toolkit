package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import limn.accessibility.AccessibleTree;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.AllocationProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * What a {@link TabbedPane} itself becomes in the accessible tree, which is nothing, and what it
 * still owes while being nothing, which is two things §7's row does not mention.
 *
 * <p>The pane declares no role, no name, no description, no verb and no state of its own, it is
 * never focusable of its own accord, and it declares no children of its own. So ADR 039 §1.6's
 * predicate deletes it and hoists the strip, the three overflow controls and every panel
 * into its place, and a reader hears a tab list and a panel rather than a box around them. §7's
 * row says exactly that and then says "the pane itself is scaffolding", which reads as no code at
 * all and is wrong twice.
 *
 * <p><b>It paints.</b> A hairline rule under the strip, which is enough for the
 * paints-and-says-nothing warning to name a toolkit class in an application's log and recommend
 * {@code setAccessibleIgnored(true)} — a flag that here deletes the tab list, the chevrons and
 * every panel together. That is the {@code BackdropPanel} case for the third time and takes the
 * same seam, and the assertion after every test below is its regression.
 *
 * <p><b>And it is the only object that knows which panel belongs to which tab.</b> A panel is
 * whatever an application handed {@code addTab} and knows nothing about tabs; a header's own
 * parent is the strip, so the strip is what a header's parent hook would reach. So the pane is
 * deleted and still carries an {@code onAccessibilityChild}, the {@code Dialog$ActionRow} shape,
 * and the correctness of it is in two guards: a panel that already said what it is keeps its role,
 * a panel that already has a name keeps its name, and only the link is unconditional. §7's row for
 * the selected content reads as though the pane owned both, and a hook written that way would
 * elide a scroll view's own role and overwrite a label's own caption.
 *
 * <p>Everything here drives the pane's public API on a bound scene and reads back what the scene
 * published. Nothing constructs a node, and nothing calls a hook.
 */
class TabbedPaneAccessibilityTest extends AccessibleComponentTestBase {

    /** The pane under test. */
    private TabbedPane pane;

    /** The container it is bound in, named so the hoist lands somewhere other than the window. */
    private Column root;

    /** The panel widgets, in tab order, so a published box can be read against the widget's. */
    private Widget[] panels;

    /** Every record the walk logged while a test was running; see the assertion after each. */
    private final List<LogRecord> logged = new ArrayList<>();

    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private Logger walkLogger;

    @BeforeEach
    void captureTheWalksLog() {
        walkLogger = Logger.getLogger("limn.scene.AccessibleWalk");
        walkLogger.addHandler(capture);
    }

    /**
     * The pane is deleted and must be deleted in silence, and that is checked after every case
     * rather than in one of them.
     *
     * <p>The walk names a class at most once for the life of the virtual machine, so an assertion
     * placed in a single test would be reading a set an earlier test had already filled and would
     * pass whatever the pane said about itself. Checked after each, whichever case runs first is
     * the one that catches a lost {@code paintsDecoration}.
     *
     * <p>It looks for this class and not for any record at all, because a case here may bind a
     * widget of somebody else's whose own warning is that widget's business.
     */
    @AfterEach
    void theToolkitsPaneIsNeverNamedInAnApplicationsLog() {
        walkLogger.removeHandler(capture);
        List<LogRecord> aboutThePane = new ArrayList<>();
        for (LogRecord record : logged) {
            if (record.getParameters() != null && record.getParameters().length > 0
                    && TabbedPane.class.getName().equals(record.getParameters()[0])) {
                aboutThePane.add(record);
            }
        }
        assertEquals(List.of(), aboutThePane,
                "the pane paints, and is deleted, and there is nothing in that worth telling an "
                        + "application: the ink is a hairline between the strip and the panel. A "
                        + "warning here names a toolkit class the application cannot correct and "
                        + "recommends the one flag that would take the tab list, the chevrons and "
                        + "every panel out of the tree: " + aboutThePane);
    }

    // ------------------------------------------------------------------------------ the fixture

    /** Captioned tabs over empty panels, in a named container and a box of known width. */
    private void bindTabs(float width, String... captions) {
        Widget[] empty = new Widget[captions.length];
        for (int i = 0; i < empty.length; i++) {
            empty[i] = new SizedBox(60, 60);
        }
        bindPanels(width, captions, empty);
    }

    /** The same, with the panels the caller wants behind the tabs. */
    private void bindPanels(float width, String[] captions, Widget[] behind) {
        panels = behind;
        pane = new TabbedPane();
        for (int i = 0; i < captions.length; i++) {
            pane.addTab(captions[i], behind[i]);
        }
        root = new Column();
        root.add(new SizedBox(width, 120, pane));
        // Named, so that what the pane's deletion hoists has somewhere to land other than the
        // window node: a test whose tree collapsed to the root would pass without the hoist.
        root.setAccessibleName("Settings");
        bind(root);
        // The deterministic ruler, so a header is 10pt per code point plus its two paddings and
        // nothing here depends on the platform's fonts.
        scene.setTextRuler(RULER);
        frame();
        bridge.events.clear();
    }

    /** The strip's headers, in add order; the strip is the pane's first child by construction. */
    private List<Widget> headers() {
        return pane.children().get(0).children();
    }

    /** @return every node carrying {@code role}, in tree order */
    private List<AccessibleNode> nodesOf(Accessible.Role role) {
        List<AccessibleNode> found = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (tree.node(i).role() == role) {
                found.add(tree.node(i));
            }
        }
        return found;
    }

    /**
     * @param role the role to look for
     * @param name the name to look for
     * @return the one node carrying both; tabs and their panels share a caption, so neither a role
     *         nor a name alone identifies one
     */
    private AccessibleNode nodeOf(Accessible.Role role, String name) {
        for (AccessibleNode node : nodesOf(role)) {
            if (node.name().equals(name)) {
                return node;
            }
        }
        throw new AssertionError("no " + role + " named \"" + name + "\"" + describe(tree()));
    }

    /** @return the node {@code relation} points at */
    private AccessibleNode targetOf(AccessibleRelation relation) {
        int index = tree().indexOf(relation.target());
        assertTrue(index >= 0, "the relation names a node this tree does not contain"
                + describe(tree()));
        return tree().node(index);
    }

    /**
     * @param node the node to read
     * @param kind the relation to look for
     * @return its one relation of that kind
     */
    private AccessibleRelation only(AccessibleNode node, Accessible.Relation kind) {
        AccessibleRelation found = null;
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() != kind) {
                continue;
            }
            if (found != null) {
                throw new AssertionError("two " + kind + " relations on \"" + node.name() + "\""
                        + describe(tree()));
            }
            found = relation;
        }
        assertNotNull(found, "no " + kind + " on " + node.role() + " \"" + node.name() + "\""
                + describe(tree()));
        return found;
    }

    /** Moves the pointer to the centre of a widget's box, as a hover does. */
    private void hover(Widget widget) {
        drive(scene).mouseMoved(widget.localToSceneX() + widget.width() / 2,
                widget.localToSceneY() + widget.height() / 2);
        drive(scene).inputBatchEnded();
    }

    // ------------------------------------------------------------------------------ the deletion

    /**
     * The pane is no node, and everything it holds hangs where the pane hung.
     *
     * <p>A role, a state or even an empty describe hook on this class would grow a box around
     * every tabbed pane in every application that ships one, and cost a reader a level of nesting
     * for a widget that is a layout.
     */
    @Test
    void thePaneIsNoNodeAndEverythingInsideItHoistsIntoItsPlace() {
        bindTabs(400, "Alpha", "Beta", "Gamma");

        AccessibleTree tree = tree();
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            assertTrue(node.role() != Accessible.Role.GROUP || !node.name().isEmpty(),
                    "a nameless group survived the predicate" + describe(tree));
        }
        assertEquals(12, tree.nodeCount(),
                "the window, the container, the tab list, three tabs, three overflow controls and "
                        + "three panels. The pane is not among them; its three controls are, and "
                        + "they publish while everything fits as three buttons that are not "
                        + "visible" + describe(tree));

        int container = tree.indexOf(node("Settings").id());
        assertEquals(container, nodeOf(Accessible.Role.TAB_LIST, "").parent(),
                "the strip hoists into the pane's own place" + describe(tree));
        for (AccessibleNode panel : nodesOf(Accessible.Role.TAB_PANEL)) {
            assertEquals(container, panel.parent(),
                    "and so does every panel: the panels are the pane's children and the tabs are "
                            + "the strip's, which is why a panel is not a tab's child in this tree"
                            + describe(tree));
        }
    }

    /**
     * Naming the pane materialises a box around the whole of it, and moves nobody's identifier.
     *
     * <p>Transparency here is per instance, not per class, and this is the escape hatch an
     * application uses to have a reader announce a region. It must not cost what it announces:
     * identity is minted over the widget tree, so the tabs a client is holding stay the tabs it is
     * holding when a container appears above them.
     */
    @Test
    void namingThePaneMaterialisesAGroupAroundItAndReKeysNothing() {
        bindTabs(400, "Alpha", "Beta", "Gamma");
        long list = nodeOf(Accessible.Role.TAB_LIST, "").id();
        long alpha = nodeOf(Accessible.Role.TAB, "Alpha").id();
        long panel = nodeOf(Accessible.Role.TAB_PANEL, "Alpha").id();

        pane.setAccessibleName("Appearance");
        frame();

        AccessibleNode group = node("Appearance");
        assertEquals(Accessible.Role.GROUP, group.role(),
                "an application that names a layout gets a group over it" + describe(tree()));
        int at = tree().indexOf(group.id());
        assertEquals(at, nodeOf(Accessible.Role.TAB_LIST, "").parent(),
                "with the strip and the panels inside it" + describe(tree()));
        assertEquals(at, nodeOf(Accessible.Role.TAB_PANEL, "Alpha").parent(), describe(tree()));
        assertEquals(list, nodeOf(Accessible.Role.TAB_LIST, "").id(), describe(tree()));
        assertEquals(alpha, nodeOf(Accessible.Role.TAB, "Alpha").id(),
                "identity is minted on the widget, so a container appearing above a tab is not "
                        + "that tab becoming a different element" + describe(tree()));
        assertEquals(panel, nodeOf(Accessible.Role.TAB_PANEL, "Alpha").id(), describe(tree()));
    }

    // ------------------------------------------------------------------------------ the panels

    /**
     * A panel that said nothing about itself is the tab's panel, named by the tab and linked to it.
     *
     * <p>The name's provenance is the whole of Finding 5 arriving here: it comes from another
     * widget, so it is a label and not a title, and on the one platform that maps the two to
     * different attributes a panel announced as a title would be read twice.
     */
    @Test
    void aPanelThatSaidNothingIsTheTabsPanelNamedByTheTab() {
        bindTabs(400, "Alpha", "Beta");

        AccessibleNode panel = nodeOf(Accessible.Role.TAB_PANEL, "Alpha");
        assertEquals(Accessible.NameFrom.LABEL, panel.nameFrom(),
                "the caption belongs to the tab and is borrowed here" + describe(tree()));
        assertEquals(nodeOf(Accessible.Role.TAB, "Alpha").id(),
                only(panel, Accessible.Relation.LABELLED_BY).target(),
                "and the link says which tab it was borrowed from" + describe(tree()));
        assertEquals(panels[0].localToSceneX(), panel.x(), describe(tree()));
        assertEquals(panels[0].localToSceneY(), panel.y(),
                "the panel starts under the strip" + describe(tree()));
        assertEquals(panels[0].width(), panel.width(), describe(tree()));
        assertEquals(panels[0].height(), panel.height(),
                "the walk's free box, which the pane has already laid out" + describe(tree()));
    }

    /**
     * A panel that is already something keeps what it is and keeps its own name, and is still
     * linked to its tab.
     *
     * <p>This is the case §7's row does not consider and the one that fails the moment somebody
     * simplifies the two guards away. The hook runs after the panel's own, so an unconditional role
     * would replace a scroll view's — deleting the scrolling a reader is told about with it, which
     * that widget's own step settled — and an unconditional name would replace a label's own
     * caption with the tab's.
     */
    @Test
    void aPanelThatIsAlreadySomethingKeepsItsRoleAndItsOwnName() {
        Label caption = new Label("Read me first");
        bindPanels(400, new String[] {"Scrolled", "Written"},
                new Widget[] {new ScrollView(new SizedBox(600, 600)), caption});

        AccessibleNode tab = nodeOf(Accessible.Role.TAB, "Scrolled");
        AccessibleNode viewport = targetOf(only(tab, Accessible.Relation.CONTROLLER_FOR));
        assertEquals(Accessible.Role.SCROLL_PANE, viewport.role(),
                "a panel wrapped in a scroll view is still a scroll pane; eliding that role would "
                        + "take the scroll facet with it" + describe(tree()));
        assertNotNull(viewport.scroll(),
                "which is the thing that would have been lost" + describe(tree()));
        assertEquals("Scrolled", viewport.name(),
                "a scroll view has no name of its own, so it does take the tab's"
                        + describe(tree()));
        assertEquals(Accessible.NameFrom.LABEL, viewport.nameFrom(), describe(tree()));
        assertEquals(tab.id(), only(viewport, Accessible.Relation.LABELLED_BY).target(),
                describe(tree()));

        pane.setSelectedIndex(1);
        frame();

        AccessibleNode label = nodeOf(Accessible.Role.LABEL, "Read me first");
        assertEquals(Accessible.NameFrom.CONTENT, label.nameFrom(),
                "a panel that is a caption keeps its caption; overwriting it with the tab's word "
                        + "would leave the one thing on the panel unreadable" + describe(tree()));
        assertEquals(nodeOf(Accessible.Role.TAB, "Written").id(),
                only(label, Accessible.Relation.LABELLED_BY).target(),
                "and it is still this tab's panel, which is the one thing the pane knows and the "
                        + "panel cannot" + describe(tree()));
        assertEquals(List.of(), nodesOf(Accessible.Role.TAB_PANEL),
                "neither panel needed the role, because both had one already" + describe(tree()));
    }

    /** An application's own name still wins, because its overrides run after every hook. */
    @Test
    void anApplicationCanStillRenameAPanel() {
        bindTabs(400, "Alpha", "Beta");
        panels[0].setAccessibleName("General settings");
        frame();

        AccessibleNode panel = nodeOf(Accessible.Role.TAB_PANEL, "General settings");
        assertEquals(Accessible.NameFrom.EXPLICIT, panel.nameFrom(), describe(tree()));
        assertEquals(nodeOf(Accessible.Role.TAB, "Alpha").id(),
                only(panel, Accessible.Relation.LABELLED_BY).target(),
                "the link stands whoever named the panel: it is a fact about the tree and not a "
                        + "way of naming" + describe(tree()));
    }

    /** An application's own role wins too, and the panel is still the tab's. */
    @Test
    void anApplicationCanStillRoleAPanel() {
        bindTabs(400, "Alpha", "Beta");
        panels[0].setAccessibleRole(Accessible.Role.GROUP);
        frame();

        AccessibleNode panel = nodeOf(Accessible.Role.GROUP, "Alpha");
        assertEquals(nodeOf(Accessible.Role.TAB, "Alpha").id(),
                only(panel, Accessible.Relation.LABELLED_BY).target(), describe(tree()));
        assertEquals(1, nodesOf(Accessible.Role.TAB_PANEL).size(),
                "only the other panel kept the pane's role" + describe(tree()));
    }

    /**
     * Only a panel takes the panel's role and the panel's link, and the pane's own four other
     * children arrive at the same hook.
     *
     * <p>Which is what the scan for which child this is exists for, and why it is a scan by
     * {@code ==} rather than by {@code List#indexOf}: a panel is an application's widget, and two
     * that answered equal to each other would be named after one another's tabs.
     *
     * <p>Six tabs in a four-hundred-point pane, so the three overflow controls are laid out and
     * visible rather than being skipped as invisible. They publish the buttons their own step
     * gave them and nothing this hook said: it declines to speak for anything that is not a page.
     */
    @Test
    void theStripAndItsControlsAreNotPanels() {
        bindTabs(400, "AAAA", "BBBB", "CCCC", "DDDD", "EEEE", "FFFF");

        assertTrue(nodeOf(Accessible.Role.TAB_LIST, "").width() < 400,
                "the strip really is a viewport between the three controls here, so this case is "
                        + "asking about controls that are laid out and on screen"
                        + describe(tree()));
        assertEquals(6, nodesOf(Accessible.Role.TAB_PANEL).size(),
                "six tabs, six panels, and nothing else took the role" + describe(tree()));
        assertEquals(List.of(), nodeOf(Accessible.Role.TAB_LIST, "").relations(),
                "the strip is not a tab's panel and takes neither the role nor the link"
                        + describe(tree()));
        assertEquals(18, tree().nodeCount(),
                "the window, the container, the tab list, six tabs, three overflow controls and "
                        + "six panels. The two chevrons and the all-tabs button are nodes of their "
                        + "own now, and they take neither the panel role nor the panel link from "
                        + "this hook" + describe(tree()));
    }

    // ------------------------------------------------------------------------------ the edge

    /** The link between a tab and its panel is published from both ends, and both ends resolve. */
    @Test
    void theEdgeBetweenATabAndItsPanelIsPublishedFromBothEnds() {
        bindTabs(400, "Alpha", "Beta", "Gamma");

        for (String caption : List.of("Alpha", "Beta", "Gamma")) {
            AccessibleNode tab = nodeOf(Accessible.Role.TAB, caption);
            AccessibleNode panel = nodeOf(Accessible.Role.TAB_PANEL, caption);
            assertEquals(panel.id(), only(tab, Accessible.Relation.CONTROLLER_FOR).target(),
                    "the tab says which panel it controls, which it could not before a panel had a "
                            + "node of its own to land on" + describe(tree()));
            assertEquals(tab.id(), only(panel, Accessible.Relation.LABELLED_BY).target(),
                    "and the panel says which tab names it" + describe(tree()));
        }
    }

    // --------------------------------------------------------------------------- the selection

    /**
     * The panels a user is not looking at are published, and published as not on screen.
     *
     * <p>That is §1.2's inherited rule doing the work and no code here: an unselected panel is not
     * visible, so it and everything under it lose {@code VISIBLE} and {@code SHOWING}, which is
     * also what stops a hidden tab's controls announcing as focusable. Publishing them at all is
     * what lets a reader hear that the pane has three panels rather than one.
     */
    @Test
    void onlyTheSelectedPanelIsShowingAndTheHiddenOnesControlsAreNotTabStops() {
        Button inside = new Button("Save");
        bindPanels(400, new String[] {"Alpha", "Beta"},
                new Widget[] {new SizedBox(60, 60), inside});

        AccessibleNode shown = nodeOf(Accessible.Role.TAB_PANEL, "Alpha");
        assertTrue(shown.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(shown.has(Accessible.State.SHOWING), describe(tree()));

        AccessibleNode hidden = node("Save");
        assertFalse(hidden.has(Accessible.State.VISIBLE),
                "the panel behind the other tab" + describe(tree()));
        assertFalse(hidden.has(Accessible.State.SHOWING), describe(tree()));
        assertFalse(hidden.has(Accessible.State.FOCUSABLE),
                "and a control on a panel nobody is looking at is not a tab stop"
                        + describe(tree()));

        pane.setSelectedIndex(1);
        frame();

        assertFalse(nodeOf(Accessible.Role.TAB_PANEL, "Alpha").has(Accessible.State.VISIBLE),
                "the panels swap with the selection" + describe(tree()));
        AccessibleNode nowShown = node("Save");
        assertTrue(nowShown.has(Accessible.State.VISIBLE), describe(tree()));
        assertTrue(nowShown.has(Accessible.State.SHOWING), describe(tree()));
        assertTrue(nowShown.has(Accessible.State.FOCUSABLE), describe(tree()));
    }

    /**
     * Moving the selection moves which panel is on screen and rebuilds nothing.
     *
     * <p>A panel appearing and disappearing with the selection would raise a structure change and a
     * destroyed node on every tab click, which a reader experiences as the element it is holding
     * becoming invalid. The panels are all published all the time and two bits move.
     */
    @Test
    void movingTheSelectionMovesTwoBitsAndKeepsEveryIdentity() {
        bindTabs(400, "Alpha", "Beta", "Gamma");
        long first = nodeOf(Accessible.Role.TAB_PANEL, "Alpha").id();
        long third = nodeOf(Accessible.Role.TAB_PANEL, "Gamma").id();

        pane.setSelectedIndex(2);
        frame();

        assertEquals(first, nodeOf(Accessible.Role.TAB_PANEL, "Alpha").id(), describe(tree()));
        assertEquals(third, nodeOf(Accessible.Role.TAB_PANEL, "Gamma").id(), describe(tree()));
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "a selection is not a structure change: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                bridge.events.toString());

        List<Long> flipped = new ArrayList<>();
        for (AccessibleEvent event : bridge.events) {
            if (event.type() == AccessibleEvent.Type.STATE_CHANGED
                    && event.state() == Accessible.State.VISIBLE) {
                flipped.add(event.nodeId());
            }
        }
        assertEquals(List.of(first, third), flipped,
                "one panel went dark and one came up: " + bridge.events);
    }

    /** A tab added later brings its panel with it, named and linked like the rest. */
    @Test
    void aTabAddedLaterBringsItsOwnPanel() {
        bindTabs(400, "Alpha", "Beta");
        Widget late = new SizedBox(60, 60);

        pane.addTab("Gamma", late);
        frame();

        AccessibleNode panel = nodeOf(Accessible.Role.TAB_PANEL, "Gamma");
        assertEquals(nodeOf(Accessible.Role.TAB, "Gamma").id(),
                only(panel, Accessible.Relation.LABELLED_BY).target(), describe(tree()));
        assertFalse(panel.has(Accessible.State.VISIBLE),
                "it arrives behind the selected tab" + describe(tree()));
    }

    // ------------------------------------------------------------------------- what it costs

    /**
     * A frame that damaged a hovered tab and changed nothing publishes nothing and allocates
     * nothing.
     *
     * <p>This is the sharp case for the pane's own hook, because a tabbed pane is damaged on
     * frames where nothing accessible moved: a hover fade, a focus fade and the indicator's slide
     * all run on the clock. The hook must therefore hand the panel the caption the header already
     * holds, by reference, and must not build a string, a list or a lambda per child per frame.
     */
    @Test
    void aQuietFrameOnAHoveredTabPublishesNothingAndAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.isSupported(),
                "this virtual machine does not count per-thread allocation");
        bindTabs(400, "Alpha", "Beta", "Gamma");
        Widget header = headers().get(0);
        scene.requestFocus(header);
        frame();
        hover(header);
        frame();

        // The hover and focus fades are timed transitions that damage the header on every
        // frame they run for; a measurement taken mid-flight measures the animation.
        settleAnimations(header);
        int published = bridge.published.size();
        bridge.events.clear();

        for (int i = 0; i < 20; i++) {
            header.invalidate();
            frame();
        }

        assertEquals(published, bridge.published.size(),
                "damage changes nothing a reader hears, so no snapshot");
        assertTrue(bridge.events.isEmpty(), "and no events: " + bridge.events);

        long[] cost = AllocationProbe.typicalAllocatedByEach(() -> {
            bridge.listening = true;
            header.invalidate();
            frame();
        }, () -> {
            bridge.listening = false;
            header.invalidate();
            frame();
        }, 60);
        long withAReaderAttached = cost[0];
        long withNobodyListening = cost[1];
        bridge.listening = true;

        assertEquals(published, bridge.published.size(), "still no difference, so no snapshot");


        assertEquals(withNobodyListening, withAReaderAttached,
                "describing a panel that did not move must cost no memory: the name is the "
                        + "header's own caption compared by reference, the scan for which panel "
                        + "this is walks a list the pane already holds, and the link is a pair of "
                        + "slots in the builder's own buffer");
    }
}
