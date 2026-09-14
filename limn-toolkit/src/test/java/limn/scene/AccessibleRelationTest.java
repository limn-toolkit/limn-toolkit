package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleRelation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link from a popup back to whatever opened it, in both directions, and what happens when it
 * names something nobody published.
 *
 * <p>Every popup, menu and dialog root in this toolkit already writes that link, for the size, the
 * direction and the language it inherits through it, and until now nothing could read it. It is the
 * only path from such a root back to its opener, and both platforms that have a relation vocabulary
 * expect the pair.
 */
class AccessibleRelationTest extends AccessibleTestBase {

    private static AccessibleRelation relationOf(AccessibleNode node, Accessible.Relation kind) {
        for (AccessibleRelation relation : node.relations()) {
            if (relation.kind() == kind) {
                return relation;
            }
        }
        return null;
    }

    @Test
    void anInSceneLayerNamesItsOpenerAndItsOpenerNamesItBack() {
        Group root = new Group();
        Probe opener = new Probe(Accessible.Role.BUTTON, "Open");
        opener.setFocusable(true);
        root.add(opener);
        bind(root);
        frame();

        Group menu = new Group();
        menu.setAccessibleRole(Accessible.Role.MENU);
        menu.setAccessibleName("File");
        menu.setInheritanceHost(opener);
        scene.pushOverlay(menu);
        frame();

        AccessibleNode popup = node("File");
        AccessibleRelation back = relationOf(popup, Accessible.Relation.POPUP_FOR);
        assertEquals(node("Open").id(), back.target(), describe(tree()));
        AccessibleRelation forward = relationOf(node("Open"), Accessible.Relation.CONTROLLER_FOR);
        assertEquals(popup.id(), forward.target(),
                "a client walking either direction finds the other");
    }

    /**
     * The link resolves to the nearest node actually published, because a widget-tree link and an
     * accessible parent are different things: a menu's opener is often a row inside a container the
     * transparency rule deleted. And a link that resolved to nothing is dropped: naming a node that
     * was never published is worse than naming nothing, because every platform answers it with an
     * element that does not resolve.
     */
    @Test
    void alinkThroughDeletedScaffoldingResolvesToWhatWasActuallyPublished() {
        Group root = new Group();
        Group scaffold = new Group();
        Probe opener = new Probe(Accessible.Role.BUTTON, "Open");
        scaffold.add(opener);
        root.add(scaffold);
        bind(root);
        frame();

        Group menu = new Group();
        menu.setAccessibleRole(Accessible.Role.MENU);
        menu.setAccessibleName("File");
        menu.setInheritanceHost(scaffold);         // deleted; the walk climbs past it
        scene.pushOverlay(menu);
        frame();

        assertTrue(node("File").relations().isEmpty(),
                "the scaffold hoisted away and the walk reached the window's own root, which the "
                        + "tree's shape already says: " + describe(tree()));
    }

    @Test
    void alinkNamingSomethingThatLeftTheTreeIsDropped() {
        Group root = new Group();
        Probe opener = new Probe(Accessible.Role.BUTTON, "Open");
        root.add(opener);
        bind(root);
        Group menu = new Group();
        menu.setAccessibleRole(Accessible.Role.MENU);
        menu.setAccessibleName("File");
        menu.setInheritanceHost(opener);
        scene.pushOverlay(menu);
        frame();
        assertEquals(1, node("File").relations().size());

        root.remove(opener);
        frame();

        assertTrue(node("File").relations().isEmpty(), describe(tree()));
    }

    /**
     * A popup that is a window of its own asks the scene holding its opener to publish the
     * {@code CONTROLLER_FOR} mirror, and asks once: the popup walks on every arrow key while it
     * is open, and the mirror it asks for on each of those walks is the one the host already
     * expects, so the host is neither told to walk again nor bought a frame for it. Before
     * {@code expectMirror} said whether it changed anything, every popup walk invalidated the
     * host — one O(n) host walk, and one frame request, per key while a reader listened.
     */
    @Test
    void aNativePopupsRepeatedWalksDoNotWalkTheHostAgain() {
        Group root = new Group();
        Probe opener = new Probe(Accessible.Role.BUTTON, "Open");
        root.add(opener);
        bind(root);

        // The popup's own scene over its own window, as a native popup is (ADR 039 §1.11), with
        // its root naming the opener in THIS scene as its inheritance host.
        Group menu = new Group();
        menu.setAccessibleRole(Accessible.Role.MENU);
        menu.setAccessibleName("File");
        menu.setInheritanceHost(opener);
        RecordingWindow popupWindow = new RecordingWindow();
        limn.testing.RecordingAccessibilityBridge popupBridge =
                limn.testing.RecordingAccessibilityBridge.listening();
        popupWindow.accessibility = popupBridge;
        Scene popup = new Scene(menu, nanos::get);
        popup.bind(popupWindow);
        popup.renderFrame(canvas);
        frame();
        assertEquals(node("File", popupBridge.tree()).id(),
                relationOf(node("Open"), Accessible.Relation.CONTROLLER_FOR).target(),
                "the host publishes the mirror after the popup's first walk: " + describe(tree()));

        int hostFrames = window.frameRequests;
        int hostPublished = bridge.published.size();
        long carried = scene.accessibleWalk().builder().carriedOver();
        for (int i = 0; i < 3; i++) {
            menu.invalidate();
            popup.renderFrame(canvas);
            frame();
        }

        assertEquals(hostFrames, window.frameRequests,
                "three more popup walks bought the host no frame");
        assertEquals(carried, scene.accessibleWalk().builder().carriedOver(),
                "and walked nothing in it: the opener's name was not carried over again");
        assertEquals(hostPublished, bridge.published.size());
        assertEquals(node("File", popupBridge.tree()).id(),
                relationOf(node("Open"), Accessible.Relation.CONTROLLER_FOR).target(),
                "while the mirror stands");
    }

    private static AccessibleNode node(String name, limn.accessibility.AccessibleTree tree) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            if (name.equals(tree.node(i).name())) {
                return tree.node(i);
            }
        }
        throw new AssertionError("no node named " + name + " in " + describe(tree));
    }

    /**
     * A relation is declared as a target and published as an identifier, and the walk is what turns
     * one into the other. Doing that inside the publish — which only runs once a difference has
     * been found — leaves the comparison reading an identifier this walk never filled in: the frame
     * after a link first resolves then reports a difference nobody made, and a link whose target
     * moved to another node while nothing else changed reports none at all and keeps naming a node
     * that is gone. Both are the same mistake, and the cheap half of it is what this pins.
     */
    @Test
    void aLinkThatResolvedIsNotADifferenceOnTheNextFrame() {
        Group root = new Group();
        Probe opener = new Probe(Accessible.Role.BUTTON, "Open");
        root.add(opener);
        bind(root);
        Group menu = new Group();
        menu.setAccessibleRole(Accessible.Role.MENU);
        menu.setAccessibleName("File");
        menu.setInheritanceHost(opener);
        scene.pushOverlay(menu);
        frame();
        assertEquals(node("Open").id(),
                relationOf(node("File"), Accessible.Relation.POPUP_FOR).target());
        int published = bridge.published.size();

        opener.invalidate();
        frame();
        opener.invalidate();
        frame();

        assertEquals(published, bridge.published.size(),
                "a damaged frame over a tree nobody touched publishes nothing, and a link that "
                        + "resolved on the frame before is not something somebody touched"
                        + describe(tree()));
    }

    @Test
    void aWindowTakingAndLosingTheDesktopsFocusIsRaisedRatherThanDiffed() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root);
        frame();
        bridge.events.clear();

        scene.windowFocusChanged(false);
        scene.inputBatchEnded();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.WINDOW_DEACTIVATED));

        scene.windowFocusChanged(true);
        scene.inputBatchEnded();
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.WINDOW_ACTIVATED));
    }
}
