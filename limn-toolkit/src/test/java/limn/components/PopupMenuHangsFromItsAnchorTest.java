package limn.components;

import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu shown at a widget hangs from it as a popover does: it closes when the widget leaves the
 * scene, by itself or with a container, or stops being shown, and a menu shown at a point of the
 * scene, at no widget, does not. Before, a context menu stayed open over a row that had been taken
 * out, holding the keyboard, and its rows acted on the widget that left.
 */
class PopupMenuHangsFromItsAnchorTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;
    private Scene scene;
    private Column page;
    private Column rows;
    private Button row;

    private void open() {
        backend = new HeadlessBackend(runtime);
        HeadlessWindow window = backend.open("Orders", 600, 400);
        row = new Button("Order 1042");
        rows = new Column();
        rows.add(row);
        page = new Column();
        page.add(rows);
        page.add(new Button("Refresh"));
        scene = new Scene(page, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        window.frame();
    }

    private void settle() {
        for (int i = 0; i < 14; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            for (HeadlessWindow w : backend.windows()) {
                if (w.scene() != null && !w.isClosed()) {
                    w.frame();
                }
            }
        }
        runtime.drain();
    }

    private PopupMenu menu(DisplayMode mode, List<String> closes) {
        PopupMenu menu = new PopupMenu(new Menu().addItem("Open", () -> { }).addItem("Delete", () -> { }))
                .setDisplayMode(mode);
        menu.observeClose(() -> closes.add("closed"));
        return menu;
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuShownAtAWidgetClosesWhenTheWidgetIsTakenOut(DisplayMode mode) {
        open();
        List<String> closes = new ArrayList<>();
        PopupMenu menu = menu(mode, closes);
        menu.showAt(row, 10, 10);
        settle();
        assertTrue(menu.isOpen(), mode.name());

        rows.remove(row);
        settle();

        assertFalse(menu.isOpen(), mode + ": closed with the row it was opened at");
        assertEquals(List.of("closed"), closes, mode + ": once");
        assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(), mode + ": no window of it left");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuShownAtAWidgetClosesWhenAContainerAboveItIsTakenOut(DisplayMode mode) {
        open();
        List<String> closes = new ArrayList<>();
        PopupMenu menu = menu(mode, closes);
        menu.showAnchored(row, 0, 0, 40, 20);
        settle();
        assertTrue(menu.isOpen(), mode.name());

        page.remove(rows);
        settle();

        assertFalse(menu.isOpen(), mode + ": closed with the list the row is in");
        assertEquals(List.of("closed"), closes, mode + ": once");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuShownAtAWidgetClosesWhenTheWidgetIsHidden(DisplayMode mode) {
        open();
        List<String> closes = new ArrayList<>();
        PopupMenu menu = menu(mode, closes);
        menu.showAt(row, 10, 10);
        settle();

        rows.setVisible(false);
        settle();

        assertFalse(menu.isOpen(), mode + ": closed with the row hidden");
        assertEquals(List.of("closed"), closes, mode + ": once");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aMenuShownAtAPointOfTheSceneStaysWhenAWidgetLeaves(DisplayMode mode) {
        open();
        List<String> closes = new ArrayList<>();
        PopupMenu menu = menu(mode, closes);
        menu.showAt(scene, 10, 10);
        settle();

        rows.remove(row);
        settle();

        assertTrue(menu.isOpen(), mode + ": hung from no widget");
        assertEquals(List.of(), closes, mode.name());
        menu.close();
        settle();
    }
}
