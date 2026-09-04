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
