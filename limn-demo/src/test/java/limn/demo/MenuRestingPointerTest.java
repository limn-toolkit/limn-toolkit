package limn.demo;

import limn.accessibility.AccessibleNode;
import limn.accessibility.AccessibleTree;
import limn.demo.AccessibleGalleryTest.Harness;
import limn.demo.AccessibleGalleryTest.Palette;
import limn.demo.a11y.AccessibilityGallery;
import limn.demo.a11y.AccessibilityGallery.Entry;
import limn.demo.a11y.AccessibilityGallery.Step;
import limn.graphics.Rect;
import limn.scene.Scene;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A menu in a window of its own follows the pointer only when the pointer moves. Windows reports a
 * window that grows under a resting cursor as a mouse move, and a submenu opened from the keyboard
 * over a pointer resting on the first row closed at once, the highlight jumping to that row, which
 * NVDA then read (the Windows guest, 2026-09-29). Here the resting pointer is the same point
 * reported twice, as that move is: once before the keys, once after the submenu opened.
 */
class MenuRestingPointerTest {

    @Test
    void aSubmenuOpenedFromTheKeyboardSurvivesTheWindowGrowingUnderAStillPointer() {
        Entry entry = AccessibilityGallery.readerEntry("context-menu");
        try (Harness harness = new Harness(Palette.LIGHT)) {
            harness.show(entry);
            harness.built.focus().requestFocus();
            harness.settle();
            List<Step> steps = entry.reader().steps();
            steps.get(0).sendTo(harness.scenes.get(0)); // Shift+F10: the menu, on Cut
            harness.settle();
            HeadlessWindow host = harness.windows().get(0);
            HeadlessWindow menu = harness.windows().get(1);
            Scene hostScene = harness.scenes.get(0);
            Scene menuScene = menu.scene();
            // Published in the host's tree, in the host's points: the pointer is the menu window's.
            Rect cut = bounds(host.bridge().tree(), "Cut");
            float x = cut.x() + cut.width() / 2 - (menu.screenX() - host.screenX());
            float y = cut.y() + cut.height() / 2 - (menu.screenY() - host.screenY());
            drive(menuScene).mouseMoved(x, y); // the pointer resting on Cut
            drive(menuScene).inputBatchEnded();
            harness.settle();
            for (int i = 1; i <= 4; i++) { // down to Transform, and Right into its submenu
                steps.get(i).sendTo(hostScene); // the keyboard stays with the host, as a Win32 menu's does
                harness.settle();
            }
            assertEquals("Upper case", cursor(host), "the submenu is open on its first row");

            drive(menuScene).mouseMoved(x, y); // the window grew; the pointer did not move
            drive(menuScene).inputBatchEnded();
            harness.settle();
            assertEquals("Upper case", cursor(host), "a pointer that did not move takes nothing");

            drive(menuScene).mouseMoved(x, y + 1); // and one that does, does
            drive(menuScene).inputBatchEnded();
            harness.settle();
            assertEquals("Cut", cursor(host), "the pointer moving on Cut highlights it");
        }
    }

    private static String cursor(HeadlessWindow window) {
        AccessibleTree tree = window.bridge().tree();
        return tree.find(tree.effectiveFocus()).name();
    }

    private static Rect bounds(AccessibleTree tree, String name) {
        for (int i = 0; i < tree.nodeCount(); i++) {
            AccessibleNode node = tree.node(i);
            if (name.equals(node.name())) {
                return node.bounds();
            }
        }
        assertNotNull(null, "no node named " + name);
        return null;
    }
}
