package limn.scene;

import limn.accessibility.Accessible;
import limn.accessibility.AccessibleEvent;
import limn.accessibility.AccessibleTree;
import limn.testing.NoopCanvas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * Where a node is: in the scene, on the screen, and in a mirrored interface.
 *
 * <p>Boxes are published in the scene's own logical points and converted once, by each bridge, from
 * the window stamp the tree carries. That stamp is what a reader on a platform thread cannot ask
 * for: a window's screen origin and its scale are user-interface-thread-confined, so they are
 * captured by the thread that may and travel with the tree.
 */
class AccessibleGeometryTest extends AccessibleTestBase {

    /** A container that lays its children out left to right, mirrored under a right-to-left tree. */
    private static final class Strip extends Widget {
        @Override
        protected Size onMeasure(Constraints constraints) {
            return constraints.constrain(200, 20);
        }

        @Override
        protected void onLayout() {
            boolean rtl = layoutDirection() == LayoutDirection.RTL;
            float cursor = 0;
            for (int i = 0; i < children().size(); i++) {
                Widget child = children().get(i);
                float childWidth = child.measure(Constraints.loose(width(), height())).width();
                // ADR 032: mirroring is a placement decision, so the box that comes out is
                // physical and left-origin in both directions.
                float x = rtl ? width() - cursor - childWidth : cursor;
                child.layoutBox(x, 0, childWidth, 20);
                cursor += childWidth;
            }
        }
    }

    private List<String> readingOrder() {
        List<String> order = new ArrayList<>();
        AccessibleTree tree = tree();
        for (int i = 1; i < tree.nodeCount(); i++) {
            order.add(tree.node(i).name());
        }
        return order;
    }

    /**
     * In a mirrored interface the tree order is unchanged and the boxes run the other way. A tree
     * sorted on x would be right in one direction and backwards in the other, which is exactly what
     * a screen reader user would experience as the interface being read out of order.
     */
    @Test
    void mirroringMovesTheBoxesAndNotTheReadingOrder() {
        Strip root = new Strip();
        root.add(new Probe(Accessible.Role.BUTTON, "first"));
        root.add(new Probe(Accessible.Role.BUTTON, "second"));
        bind(root);
        frame();
        assertEquals(List.of("first", "second"), readingOrder());
        assertTrue(node("first").x() < node("second").x(), "left to right, they ascend in x");

        scene.setLayoutDirection(LayoutDirection.RTL);
        frame();

        assertEquals(List.of("first", "second"), readingOrder(),
                "reading order is tree order, whichever way the interface reads");
        assertTrue(node("first").x() > node("second").x(),
                "and the boxes descend in x: " + describe(tree()));
    }

    @Test
    void theTreeCarriesTheWindowOriginAndScaleAReaderCannotAskFor() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root);
        window.screenX = 200;
        window.screenY = 172;
        window.logicalToScreenFactor = 2f;
        bridge.host.requestRestamp();
        runtime.drain();
        frame();

        AccessibleTree tree = tree();
        assertEquals(200, tree.screenX());
        assertEquals(172, tree.screenY());
        assertEquals(2f, tree.logicalToScreenFactor());
        assertTrue(tree.supportsAbsolutePositioning());

        // What a bridge does with it, once, in its own idiom: multiply and add.
        assertEquals(200 + node("Save").x() * 2f, screenLeftOf("Save"));
    }

    private float screenLeftOf(String name) {
        AccessibleTree tree = tree();
        return tree.screenX() + node(name).x() * tree.logicalToScreenFactor();
    }

    @Test
    void aWindowThatCannotKnowWhereItIsSaysSoRatherThanReportingZeroAsAPosition() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root);
        window.canPosition = false;
        bridge.host.requestRestamp();
        runtime.drain();
        frame();

        assertFalse(tree().supportsAbsolutePositioning(),
                "window-relative extents are the truthful answer where the protocol has no other");
    }

    /**
     * Resizing the window is the opposite of moving it: every box is scene-local and every one of
     * them changed, so it is a walk and a diff, arriving as a bounds difference on the nodes a reader
     * already holds -- not a structure change, and not a node destroyed.
     *
     * <p>Until the layout pass owned the flag, this published nothing. The headless path is the one
     * an embedder takes and the one that first showed it: a frame rendered into a canvas of another
     * size relays out the whole tree through {@code layoutPass}, which reaches no damage funnel.
     */
    @Test
    void aResizeRepublishesEveryBoxAsABoundsDifference() {
        Strip root = new Strip();
        root.add(new Probe(Accessible.Role.BUTTON, "first"));
        root.add(new Probe(Accessible.Role.BUTTON, "second"));
        bind(root);
        // Mirrored, so that a wider window moves the children and not only the window's own box.
        scene.setLayoutDirection(LayoutDirection.RTL);
        frame();
        long first = node("first").id();
        float firstX = node("first").x();
        bridge.events.clear();
        int published = bridge.published.size();

        canvas = new NoopCanvas(320, 200);
        frame();

        assertEquals(published + 1, bridge.published.size(), "one tree, walked and published");
        assertEquals(first, node("first").id(), "no identifier moved");
        assertEquals(firstX + 120, node("first").x(), "and the box did: " + describe(tree()));
        assertEquals(320, tree().sceneWidth());
        assertEquals(3, bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED),
                "the window's box and both children's, each its own event under the budget: "
                        + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED),
                "nothing was rebuilt: " + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED),
                "and nothing left: " + bridge.events);
    }

    /**
     * The same through the window's own path: the framebuffer callback queues a resize whose arm
     * marks the layout dirty and schedules a frame without declaring damage, because a layout frame
     * damages everything structurally. That was true of damage and not of the accessible tree.
     */
    @Test
    void aResizeDragArrivesTheSameWay() {
        Strip root = new Strip();
        root.add(new Probe(Accessible.Role.BUTTON, "first"));
        bind(root);
        scene.setLayoutDirection(LayoutDirection.RTL);
        frame();
        float firstX = node("first").x();
        bridge.events.clear();

        drive(scene).windowResized(320, 200);
        drive(scene).inputBatchEnded();
        canvas = new NoopCanvas(320, 200);
        frame();

        assertEquals(firstX + 120, node("first").x(), describe(tree()));
        assertTrue(bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED) > 0, "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.STRUCTURE_CHANGED), "" + bridge.events);
        assertEquals(0, bridge.countOf(AccessibleEvent.Type.NODE_DESTROYED), "" + bridge.events);
    }

    /**
     * Moving the window re-stamps the tree and walks nothing: every box in it is scene-local, and a
     * move has not touched one of them. This is the assertion that separates the two requests a
     * bridge can make.
     */
    @Test
    void movingTheWindowReStampsTheTreeAndWalksNothing() {
        Group root = new Group();
        root.add(new Probe(Accessible.Role.BUTTON, "Save"));
        bind(root);
        frame();
        AccessibleTree before = tree();
        long id = node("Save").id();
        bridge.events.clear();
        int published = bridge.published.size();

        window.screenX = 400;
        bridge.host.requestRestamp();
        runtime.drain();
        frame();

        AccessibleTree after = tree();
        assertEquals(published + 1, bridge.published.size(), "one re-stamped tree");
        assertEquals(400, after.screenX());
        assertEquals(before.nodeCount(), after.nodeCount());
        assertEquals(id, node("Save").id(), "no identifier moved");
        for (int i = 0; i < after.nodeCount(); i++) {
            assertSame(before.node(i), after.node(i), "the nodes themselves were not rebuilt");
        }
        assertEquals(1, bridge.countOf(AccessibleEvent.Type.BOUNDS_CHANGED));
        assertEquals(0, bridge.first(AccessibleEvent.Type.BOUNDS_CHANGED).nodeId(),
                "one window-level event, not one per node");
        assertNotEquals(before.generation(), after.generation());
    }
}
