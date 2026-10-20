package limn.components;

import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.scene.layout.SizedBox;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popover's close runs the application's code (the closes of the popovers hung in it, their
 * {@code onClose}), and that code may show a popover again: the close in progress never takes down
 * what was shown meanwhile, never leaves a layer over the window that nothing owns, and a popover
 * that says it is open is shown.
 */
class PopoverReentrancyTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> heard = new ArrayList<>();
    private HeadlessBackend backend;
    private HeadlessWindow owner;
    private Scene scene;

    private void frames(int rounds) {
        for (int round = 0; round < rounds; round++) {
            now[0] += TimeUnit.MILLISECONDS.toNanos(16);
            runtime.drain();
            for (HeadlessWindow w : new ArrayList<>(backend.windows())) {
                if (!w.isClosed() && w.scene() != null) {
                    w.frame();
                }
            }
        }
    }

    private void inWindow(Widget<?> root) {
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        runtime.setDelayClock(() -> now[0]);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
    }

    private long openWindows() {
        return backend.windows().stream().filter(w -> !w.isClosed()).count();
    }

    /** Whether a popover that says it is open is shown: its content in a scene, in a window that is up. */
    private static boolean shown(Popover popover) {
        Scene in = popover.content().scene();
        return in != null && (in.window() == null || !in.window().isClosed());
    }

    private enum Trigger { CODE, APPLICATION_SWITCH }

    // ------------------------------------------------------------------ an inner onClose showing the outer again

    @Test
    void anInnerOnCloseShowingTheOuterAgainLeavesTheWindowUsableInTheScene() {
        for (Trigger trigger : Trigger.values()) {
            innerOnCloseShowsTheOuterAgain(DisplayMode.IN_SCENE, trigger);
        }
    }

    @Test
    void anInnerOnCloseShowingTheOuterAgainLeavesTheWindowUsableInWindows() {
        for (Trigger trigger : Trigger.values()) {
            innerOnCloseShowsTheOuterAgain(DisplayMode.NATIVE_WINDOW, trigger);
        }
    }

    /**
     * "Back to the main popover when the sub-popover goes": normally nothing, since the outer one
     * is still open. When the outer one is the one closing, its close ran the inner's handler half
     * way through, and the handler's fresh presentation was taken down by the rest of it while the
     * old layer stayed over the window for good, swallowing every press.
     */
    private void innerOnCloseShowsTheOuterAgain(DisplayMode mode, Trigger trigger) {
        heard.clear();
        Button more = new Button("More");
        Column holder = new Column();
        holder.add(more);
        Button base = new Button("Base");
        int[] clicks = {0};
        base.onAction(() -> clicks[0]++);
        Column root = new Column();
        root.add(holder);
        root.add(base);
        root.add(new SizedBox(300, 200, new Label("Orders")));
        inWindow(root);
        Button columns = new Button("Columns");
        Column outerContent = new Column();
        outerContent.add(columns);
        Popover outer = new Popover(outerContent).setDisplayMode(mode).attachTo(more)
                .onClose(origin -> heard.add("outer " + origin));
        Popover inner = new Popover(new SizedBox(100, 30, new TextField())).setDisplayMode(mode).attachTo(columns);
        inner.onClose(origin -> {
            heard.add("inner " + origin);
            outer.showFor(more);
        });
        outer.showFor(more);
        frames(3);
        inner.showFor(columns);
        frames(3);
        assertTrue(outer.isOpen() && inner.isOpen(), "sanity: both open");

        switch (trigger) {
            case CODE -> outer.close();
            case APPLICATION_SWITCH -> {
                owner.desktopFocus(true);
                owner.desktopFocus(false);
            }
        }
        frames(40);
        String where = mode + " " + trigger + " " + heard;
        assertFalse(inner.isOpen(), where);
        assertEquals(outer.isOpen(), shown(outer), "the outer one is shown exactly when it says it is open: " + where);

        if (outer.isOpen()) {
            // Shown again during its own close: what it hears is its own, a press outside included.
            drive(scene).click(590, 390);
            frames(40);
            assertFalse(outer.isOpen(), "a press outside closes the one shown again: " + where);
            clicks[0] = 0;
        }
        outer.close();
        frames(40);
        drive(scene).click(base);
        frames(3);
        assertEquals(1, clicks[0], "the window takes a click again, nothing is left over it: " + where);
        assertEquals(1, openWindows(), "the owner's window alone: " + where);
        outer.showFor(more.scene() != null ? more : base);
        frames(3);
        assertTrue(outer.isOpen() && shown(outer), "and the outer one opens and shows again: " + where);
        owner.requestClose();
        runtime.drain();
    }
}
