package limn.scene;

import limn.testfixtures.PlainWidget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A window that closes takes its widgets out of the scene as a removal would: what a widget took
 * when it was attached, from something that lives longer than the window, it gives back when it is
 * detached, and a close that detached nothing left every such thing holding the window's widgets.
 */
class ClosedWindowDetachesItsTreeTest {

    /** Writes down each time it enters and leaves a scene. */
    private static final class Noting extends PlainWidget {
        final List<String> seen;
        final String name;

        Noting(String name, List<String> seen) {
            this.name = name;
            this.seen = seen;
        }

        @Override
        protected Size onMeasure(Constraints constraints) {
            return new Size(40, 20);
        }

        @Override
        protected void onAttached() {
            seen.add(name + " attached");
        }

        @Override
        protected void onDetached() {
            seen.add(name + " detached from " + (scene() != null ? "its scene" : "nothing"));
        }
    }

    @Test
    void everyWidgetAndEveryOverlayIsDetachedOnceAsTheWindowCloses() {
        try (HeadlessUi ui = new HeadlessUi()) {
            HeadlessBackend backend = new HeadlessBackend(ui.runtime());
            List<String> seen = new ArrayList<>();
            Noting root = new Noting("root", seen);
            Noting child = new Noting("child", seen);
            root.add(child);
            Noting overlay = new Noting("overlay", seen);
            Scene scene = new Scene(root);
            scene.setTextRuler(TestRulers.FIXED);
            HeadlessWindow window = backend.open("Closes", 300, 200);
            scene.bind(window);
            scene.pushOverlay(overlay);
            window.frame();
            seen.clear();

            window.close();
            assertEquals(List.of("overlay detached from its scene", "child detached from its scene",
                    "root detached from its scene"), seen, "children before parents, the scene still answered");
            assertNull(root.scene());
            assertNull(child.scene());
            assertNull(overlay.scene());

            window.close();
            assertEquals(3, seen.size(), "a window closes once");
        }
    }

    @Test
    void aSceneBoundAgainAfterItsWindowClosedHasItsTreeBack() {
        try (HeadlessUi ui = new HeadlessUi()) {
            HeadlessBackend backend = new HeadlessBackend(ui.runtime());
            List<String> seen = new ArrayList<>();
            Noting root = new Noting("root", seen);
            Scene scene = new Scene(root);
            scene.setTextRuler(TestRulers.FIXED);
            scene.bind(backend.open("First", 300, 200));
            backend.windows().get(0).close();
            seen.clear();

            HeadlessWindow second = backend.open("Second", 300, 200);
            scene.bind(second);
            assertEquals(List.of("root attached"), seen);
            assertSame(scene, root.scene());
            second.frame();
        }
    }
}
