package limn.components;

import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless backend lets go of a window as it closes: it leaves {@code windows()} and the
 * front-to-back order the ending is decided by, so a test's fixture is not what keeps a closed
 * popup's scene, and the page it held, alive. Before, every window ever opened stayed in both
 * lists, and a leak check over native popups found pages alive that only the fixture held.
 */
class HeadlessBackendForgetsClosedWindowsTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));

    private void frames(HeadlessBackend backend, int n) {
        for (int i = 0; i < n; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
    }

    @Test
    void aClosedWindowLeavesTheListAndWhatItsSceneHeldIsCollected() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow main = backend.open("Orders", 600, 400);
        Scene scene = new Scene(new Column(), clock::get);
        scene.setTextRuler(RULER);
        scene.bind(main);
        main.frame();

        // A page in a window of its own, as a native popup's is, then closed.
        HeadlessWindow other = backend.open("Details", 300, 200);
        Column details = new Column();
        details.add(new Label("Order 1042"));
        Scene otherScene = new Scene(details, clock::get);
        otherScene.setTextRuler(RULER);
        otherScene.bind(other);
        frames(backend, 2);
        WeakReference<Column> page = new WeakReference<>(details);
        details = null;
        otherScene = null;
        assertEquals(List.of(main, other), backend.windows());

        other.close();
        frames(backend, 2);

        assertTrue(other.isClosed(), "the reference a test kept still answers");
        assertEquals(List.of(main), backend.windows(), "the closed window left the list");
        other = null;
        assertTrue(collected(page), "nothing in the backend holds the closed window's page");
    }

    @Test
    void aMenuWindowThatClosedIsNotAmongTheWindows() {
        HeadlessBackend backend = new HeadlessBackend(runtime);
        HeadlessWindow main = backend.open("Orders", 600, 400);
        Scene scene = new Scene(new Column(), clock::get);
        scene.setTextRuler(RULER);
        scene.bind(main);
        main.frame();
        PopupMenu menu = new PopupMenu(new Menu().addItem("Open", () -> { })).setDisplayMode(DisplayMode.NATIVE_WINDOW);
        menu.showAt(scene, 20, 20);
        frames(backend, 3);
        assertEquals(2, backend.windows().size(), "the menu opened a window of its own");

        menu.close();
        frames(backend, 8);

        assertFalse(menu.isOpen());
        assertEquals(List.of(main), backend.windows(), "only the window still open");
    }

    private static boolean collected(WeakReference<?> ref) {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ref.get() == null;
    }
}
