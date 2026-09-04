package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.i18n.I18nString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the walk publishes and what it leaves out: scaffolding removed with its children hoisted in
 * its place, decoration removed with its children, and every surviving node carrying a box in the
 * scene's own coordinates.
 */
class AccessibleTreeTest extends AccessibleTestBase {

    @Test
    void scaffoldingIsRemovedAndItsChildrenAreHoistedIntoItsPlace() {
        Group outer = new Group();
        Group inner = new Group();
        Probe button = new Probe(Accessible.Role.BUTTON, "Save");
        inner.add(button);
        outer.add(inner);
        bind(outer);
        frame();

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(), "a window and a button, and no boxes: " + describe(tree));
        assertEquals(Accessible.Role.WINDOW, tree.root().role());
        assertEquals(Accessible.Role.BUTTON, tree.node(1).role());
        assertEquals(0, tree.node(1).parent(), "the button hoists to the window");
    }

    @Test
    void aContainerThatSaysSomethingSurvivesAndKeepsItsChildrenUnderIt() {
        Group outer = new Group();
        Probe button = new Probe(Accessible.Role.BUTTON, "Save");
        outer.add(button);
        outer.setAccessibleName("Actions");
        bind(outer);
        frame();

        AccessibleTree tree = tree();
        assertEquals(3, tree.nodeCount(), describe(tree));
        assertEquals("Actions", tree.node(1).name());
        assertEquals(Accessible.NameFrom.EXPLICIT, tree.node(1).nameFrom());
        assertEquals(1, tree.node(2).parent(), "the button is under the group that named itself");
    }

    @Test
    void anIgnoredWidgetTakesItsChildrenWithIt() {
        Group outer = new Group();
        Group decorative = new Group();
        decorative.add(new Probe(Accessible.Role.BUTTON, "Hidden"));
        decorative.setAccessibleIgnored(true);
        outer.add(decorative);
        outer.add(new Probe(Accessible.Role.BUTTON, "Shown"));
        bind(outer);
        frame();

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(), describe(tree));
        assertEquals("Shown", tree.node(1).name());
    }

    /**
     * The two free names, and the order between them. A widget with no name of its own takes its
     * tooltip, which names every icon-only control in the toolkit without one line of accessibility
     * code in any of them; a widget that already has a name takes the tooltip as its description
     * instead, so nothing is said twice.
     */
    @Test
    void aWidgetWithNoNameTakesItsTooltipAndOneWithANameTakesItAsADescription() {
        Group root = new Group();
        Probe unnamed = new Probe();
        unnamed.role = Accessible.Role.BUTTON;
        unnamed.setTooltip("Play");
        Probe named = new Probe(Accessible.Role.BUTTON, "Stop");
        named.setTooltip("Stop playback");
        root.add(unnamed);
        root.add(named);
        bind(root);
        frame();

        AccessibleNode fromTooltip = node("Play");
        assertEquals(Accessible.NameFrom.TOOLTIP, fromTooltip.nameFrom());
        assertEquals("", fromTooltip.description());
        assertEquals("Stop playback", node("Stop").description());
        assertEquals(Accessible.NameFrom.CONTENT, node("Stop").nameFrom());
    }

    @Test
    void anApplicationNameWinsOverWhateverTheWidgetDerived() {
        Probe button = new Probe(Accessible.Role.BUTTON, "Save");
        button.setAccessibleName(I18nString.literal("Save the document"));
        Group root = new Group();
        root.add(button);
        bind(root);
        frame();

        assertEquals("Save the document", tree().node(1).name());
        assertEquals(Accessible.NameFrom.EXPLICIT, tree().node(1).nameFrom());
    }

    @Test
    void everyNodeCarriesItsBoxInTheScenesOwnCoordinates() {
        Group root = new Group();
        Probe first = new Probe(Accessible.Role.BUTTON, "first");
        first.prefHeight = 30;
        Probe second = new Probe(Accessible.Role.BUTTON, "second");
        second.prefHeight = 25;
        root.add(first);
        root.add(second);
        bind(root);
        frame();

        assertEquals(0f, node("first").y());
        assertEquals(30f, node("first").height());
        assertEquals(30f, node("second").y(), "stacked, so the second starts where the first ends");
        assertEquals(25f, node("second").height());
    }

    /** The links a client navigates by, which is how one of the three platforms walks a tree. */
    @Test
    void theLinksLetAClientWalkForwardsAndBackwardsWithoutScanningAList() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "a"));
        root.add(new Probe(Accessible.Role.BUTTON, "b"));
        root.add(new Probe(Accessible.Role.BUTTON, "c"));
        bind(root);
        frame();

        AccessibleTree tree = tree();
        assertEquals(4, tree.nodeCount(), describe(tree));
        assertEquals(1, tree.root().firstChild());
        assertEquals(3, tree.root().lastChild());
        assertEquals(2, tree.node(1).nextSibling());
        assertEquals(2, tree.node(3).previousSibling());
        assertEquals(AccessibleNode.NONE, tree.node(3).nextSibling());
    }

    @Test
    void aWindowWithNoBridgePublishesNothingAndCostsNoWalk() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        RecordingWindow plain = new RecordingWindow();
        scene = new Scene(root, nanos::get);
        scene.bind(plain);
        scene.renderFrame(canvas);
        scene.renderFrame(canvas);

        assertSame(limn.backend.AccessibilityBridge.NONE, plain.accessibility());
    }

    /** A bridge attached and nobody listening publishes nothing at all. */
    @Test
    void nothingIsPublishedWhileNothingIsListening() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root, false);
        frame();
        frame();

        assertTrue(bridge.published.isEmpty(), "nothing is listening, so nothing is walked");
        assertTrue(bridge.events.isEmpty());
    }

    /** And it starts publishing the moment something does, with no audit of what was missed. */
    @Test
    void turningAReaderOnMidSessionPublishesTheWholeTreeOnTheNextFrame() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root, false);
        frame();

        bridge.listening = true;
        scene.requestRender();
        frame();

        assertEquals(1, bridge.published.size(), "the first tree this window ever published");
        assertNotNull(node("Save"));
    }

    @Test
    void aFocusableWidgetThatDeclaresNoRoleIsPublishedAsUnknownRatherThanDeleted() {
        Group root = new Group();
        Probe mystery = new Probe();
        mystery.setFocusable(true);
        root.add(mystery);
        bind(root);
        frame();

        AccessibleTree tree = tree();
        assertEquals(2, tree.nodeCount(), describe(tree));
        assertEquals(Accessible.Role.UNKNOWN, tree.node(1).role());
        assertTrue(tree.node(1).has(Accessible.State.FOCUSABLE));
    }

    @Test
    void aNodeCarriesTheVerbsItOffersAndTheOnesEveryFocusableWidgetGetsFree() {
        Group root = new Group();
        Probe button = new Probe(Accessible.Role.BUTTON, "Save");
        button.setFocusable(true);
        button.actions = new Accessible.Action[] {Accessible.Action.PRESS};
        root.add(button);
        bind(root);
        frame();

        AccessibleNode node = node("Save");
        assertNotNull(node.actions());
        assertTrue(node.actions().has(Accessible.Action.PRESS));
        assertTrue(node.actions().has(Accessible.Action.FOCUS));
        assertTrue(node.actions().has(Accessible.Action.SCROLL_INTO_VIEW));
        assertFalse(node.actions().has(Accessible.Action.TOGGLE));
        assertNull(node.toggle());
    }
}
