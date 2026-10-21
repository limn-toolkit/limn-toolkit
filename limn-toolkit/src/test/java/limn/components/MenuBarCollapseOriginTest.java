package limn.components;

import limn.input.Keys;
import limn.scene.Change;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A menu bar's dropdown closed because the bar left the window (a page switch, the bar taken
 * out) or was hidden is announced as the bar's own adjustment, never as the person collapsing the
 * menu: an application that watches the bar to react to the person closing a menu (a tour that
 * advances, focus put back where the person was) must not hear a page switch a timer made as a
 * click. Before, the dropdown's anchor rule closed it ahead of the bar and its close reached the
 * bar as the person's.
 */
class MenuBarCollapseOriginTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;
    private HeadlessWindow window;
    private Scene scene;
    private Column page;
    private MenuBar bar;
    private final List<Change.Origin> collapses = new ArrayList<>();
    private boolean lettersBefore;

    private void open(DisplayMode mode) {
        lettersBefore = limn.scene.internal.AccessLetterSystem.setPresentForTests(true);
        backend = new HeadlessBackend(runtime);
        window = backend.open("Orders", 600, 400);
        bar = new MenuBar().setDisplayMode(mode).addMenu("&File", new Menu().addItem("Open", () -> { }));
        page = new Column();
        page.add(bar);
        page.add(new TextField());
        scene = new Scene(page, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(window);
        window.frame();
        drive(scene).press(Keys.F, Keys.MOD_ALT);
        settle();
        assertTrue(bar.isOpen(), mode + ": Alt+F opened File");
        bar.observeChanges((source, change) -> {
            if (change.aspect() == Change.Aspect.EXPANDED) {
                collapses.add(change.origin());
            }
        });
    }

    @AfterEach
    void restoreLetters() {
        limn.scene.internal.AccessLetterSystem.setPresentForTests(lettersBefore);
    }

    private void settle() {
        for (int i = 0; i < 6; i++) {
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

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPageSwitchCollapsesTheMenuAsAnAdjustment(DisplayMode mode) {
        open(mode);
        Column next = new Column();
        next.add(new TextField());
        scene.setRoot(next);
        settle();
        assertFalse(bar.isOpen(), mode.name());
        assertEquals(List.of(Change.Origin.ADJUSTMENT), collapses, mode + ": the switch is not the person's");
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void takingTheBarOutCollapsesTheMenuAsAnAdjustment(DisplayMode mode) {
        open(mode);
        page.remove(bar);
        settle();
        assertFalse(bar.isOpen(), mode.name());
        assertEquals(List.of(Change.Origin.ADJUSTMENT), collapses, mode.name());
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void hidingTheBarCollapsesTheMenuAsAnAdjustment(DisplayMode mode) {
        open(mode);
        bar.setVisible(false);
        settle();
        assertFalse(bar.isOpen(), mode.name());
        assertEquals(List.of(Change.Origin.ADJUSTMENT), collapses, mode.name());
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void escapeIsStillThePersonClosingTheMenu(DisplayMode mode) {
        open(mode);
        Scene menus = scene;
        for (HeadlessWindow w : backend.windows()) {
            if (w != window && !w.isClosed() && w.scene() != null) {
                menus = w.scene();
            }
        }
        drive(menus).press(Keys.ESCAPE);
        settle();
        assertFalse(bar.isOpen(), mode.name());
        assertEquals(List.of(Change.Origin.USER), collapses, mode.name());
    }
}
