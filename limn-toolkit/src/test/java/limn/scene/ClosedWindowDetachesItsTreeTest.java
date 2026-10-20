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

    @Test
    void anOutOfMemoryErrorFromAFadesArrivalAsTheWindowClosesIsContainedAndTheNextArrivalRuns() {
        // Two containers fading as their window closes, the first one's onArrive throwing an
        // OutOfMemoryError: the close contains it, as it contains every other throw of its
        // teardown, and the second arrival, which may give back something that outlives the window,
        // still runs. The close let the error leave and dropped the second arrival.
        List<Throwable> reported = new ArrayList<>();
        List<limn.backend.CrashPhase> phases = new ArrayList<>();
        limn.backend.CrashHandler handler = (phase, error) -> {
            phases.add(phase);
            reported.add(error);
            return true;
        };
        limn.backend.Crashes.install(handler);
        try (HeadlessUi ui = new HeadlessUi()) {
            HeadlessBackend backend = new HeadlessBackend(ui.runtime());
            List<String> seen = new ArrayList<>();
            limn.scene.layout.Opacity first = new limn.scene.layout.Opacity(new Noting("a", seen)).setOpacity(0.2f);
            limn.scene.layout.Opacity second = new limn.scene.layout.Opacity(new Noting("b", seen)).setOpacity(0.2f);
            limn.scene.layout.Column root = new limn.scene.layout.Column();
            root.add(first);
            root.add(second);
            Scene scene = new Scene(root);
            scene.setTextRuler(TestRulers.FIXED);
            HeadlessWindow window = backend.open("Closes", 300, 200);
            scene.bind(window);
            List<String> ran = new ArrayList<>();
            OutOfMemoryError fatal = new OutOfMemoryError("simulated (expected)");
            first.fadeTo(1f, 1.0, () -> {
                ran.add("first");
                throw fatal;
            });
            second.fadeTo(1f, 1.0, () -> ran.add("second"));
            window.frame();
            window.frame();

            Throwable escaped = null;
            try {
                window.close();
            } catch (OutOfMemoryError e) {
                escaped = e; // caught here so the test runner is not taken down with it
            }

            assertNull(escaped, "the close contains it");
            assertEquals(List.of("first", "second"), ran, "the next arrival still ran");
            assertEquals(List.of(fatal), reported, "contained and reported once");
            assertEquals(List.of(limn.backend.CrashPhase.WINDOW_CLOSE), phases);
            assertNull(first.scene());
            assertNull(second.scene());
        } finally {
            limn.backend.Crashes.uninstall(handler);
        }
    }
}
