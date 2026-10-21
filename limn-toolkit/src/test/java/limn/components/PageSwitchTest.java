package limn.components;

import limn.accessibility.AccessibleEvent;
import limn.scene.Scene;
import limn.scene.layout.Column;
import limn.testing.AccessibleHarness;
import limn.testing.AccessibleTrees;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One window, one scene, two pages: {@link Scene#setRoot} with the toolkit's own components on the
 * page that leaves. What the old page had open closes, a composition in its field ends, a reader
 * is told the window's content changed, and a dialog opened from it gives the keyboard to the new
 * page when its answer is what switched the page.
 */
class PageSwitchTest extends ComponentTestBase {

    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private HeadlessBackend backend;
    private HeadlessWindow window;
    private Scene scene;

    private void open(Column page) {
        backend = new HeadlessBackend(runtime);
        window = backend.open("Orders", 600, 400);
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

    /** A window that cannot place another (fullscreen, Wayland): every popup is drawn in the scene. */
    private void openOnAWindowThatPlacesNoOther(Column page) {
        scene = new Scene(page, clock::get);
        scene.setTextRuler(RULER);
        scene.bind(new limn.testing.StubWindow(false));
        frames();
    }

    /** {@link #settle()} for a scene bound to a window of no backend. */
    private void frames() {
        for (int i = 0; i < 14; i++) {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(50));
            runtime.drain();
            scene.renderFrame(new limn.testing.NoopCanvas(600, 400));
        }
        runtime.drain();
    }

    private static Column page(limn.scene.Widget<?>... widgets) {
        Column page = new Column();
        for (limn.scene.Widget<?> widget : widgets) {
            page.add(widget);
        }
        return page;
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPopoverOpenFromTheOldPageClosesOnceAndTheNewPageIsShown(DisplayMode mode) {
        Button opener = new Button("Filter");
        open(page(opener));
        List<String> closed = new ArrayList<>();
        Popover popover = new Popover(page(new TextField())).setDisplayMode(mode);
        popover.onClose(origin -> closed.add("closed"));
        popover.showFor(opener);
        settle();
        assertTrue(popover.isOpen());
        Button next = new Button("Next");

        scene.setRoot(page(next));
        settle();

        assertFalse(popover.isOpen(), mode + ": closed with the page it hung from");
        assertEquals(List.of("closed"), closed, mode + ": once");
        assertSame(scene, next.scene(), mode.name());
        assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(),
                mode + ": no window of the popover's left open");
        new Column().add(opener.parent()); // the old page is free
    }

    @Test
    void aCompositionOpenInAFieldOfTheOldPageEndsAndTypingGoesToTheNewPage() {
        TextField old = new TextField().setText("abc");
        open(page(old));
        scene.requestFocus(old);
        drive(scene).preeditChanged("nihon", 5, 0, 5);
        drive(scene).inputBatchEnded();
        assertEquals("nihon", old.composingText());
        TextField fresh = new TextField();

        scene.setRoot(page(fresh));
        settle();

        assertEquals("", old.composingText(), "the composition ended with the page");
        assertEquals("abc", old.text(), "and nothing of it was committed");
        assertSame(fresh, scene.focusedWidget(), "the keyboard was in the old page: the new page's first field");
        drive(scene).type("x");
        assertEquals("x", fresh.text());
        assertEquals("abc", old.text());
    }

    @Test
    void aReaderIsToldTheWindowsContentChangedAndWhereTheKeyboardWent() {
        Button one = new Button("Page one");
        AccessibleHarness harness = new AccessibleHarness(runtime, page(one));
        harness.focus(one);
        harness.clearObservations();
        Button two = new Button("Page two");

        harness.scene.setRoot(page(two));
        harness.frame();

        assertNotNull(AccessibleTrees.named(harness.tree(), "Page two"), harness.describe());
        assertNull(AccessibleTrees.named(harness.tree(), "Page one"), harness.describe());
        assertFalse(harness.bridge.eventsOf(AccessibleEvent.Type.STRUCTURE_CHANGED).isEmpty(),
                "the window's children changed: " + harness.bridge.events);
        assertEquals(harness.node("Page two").id(), harness.tree().focused(), "the keyboard's new place is published");
        assertFalse(harness.bridge.eventsOf(AccessibleEvent.Type.FOCUS_CHANGED).isEmpty(), harness.bridge.events.toString());
    }

    /**
     * A dialog opened from the old page whose answer switches the page: the keyboard was in the
     * old page under the dialog, and when the dialog closes it goes to the new page's first stop
     * rather than to the button that opened it, which has left, or to nothing.
     */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aDialogWhoseAnswerSwitchesThePageGivesTheKeyboardToTheNewPage(DisplayMode mode) {
        Button opener = new Button("Sign out");
        open(page(opener));
        scene.requestFocus(opener);
        TextField first = new TextField();
        Column next = page(first);
        new Dialog("Sign out?", "").addButton("Cancel", "cancel").addPrimaryButton("Sign out", "yes")
                .setCancelResult("cancel").setDisplayMode(mode).show(scene)
                .thenAccept(answer -> scene.setRoot(next));
        settle();

        Scene dialogs = mode == DisplayMode.IN_SCENE ? scene
                : backend.windows().get(backend.windows().size() - 1).scene();
        drive(dialogs).press(limn.input.Keys.ENTER); // the primary button's answer
        settle();

        assertSame(next, scene.root(), mode + ": the answer switched the page");
        assertSame(first, scene.focusedWidget(), mode + ": the new page's first field");
    }

    // ------------------------------------------------------- the keyboard in a popup of the old page
    //
    // A popup hung from the old page closes with it; when the keyboard was in that popup, not in
    // the page, it goes to the new page's first stop, as it does in a window of the popup's own
    // (where the page under it kept the keyboard all along). Before, the popup's close looked for
    // a first stop in a page that had not joined yet, found none, and left the keyboard on
    // nothing until a Tab.

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theKeyboardInAPopoverOfTheOldPageGoesToTheNewPagesFirstStop(DisplayMode mode) {
        Button opener = new Button("Account");
        open(page(new TextField(), opener));
        scene.requestFocus(opener);
        TextField inPanel = new TextField();
        Popover popover = new Popover(page(inPanel)).setDisplayMode(mode);
        popover.showFor(opener);
        settle();
        if (mode == DisplayMode.IN_SCENE) {
            scene.requestFocus(inPanel);
            assertSame(inPanel, scene.focusedWidget(), "the keyboard is in the panel");
        }
        TextField first = new TextField();

        scene.setRoot(page(first, new TextField()));

        assertSame(first, scene.focusedWidget(), mode + ": the new page's first stop, at once");
        settle();
        assertFalse(popover.isOpen(), mode.name());
        assertSame(first, scene.focusedWidget(), mode + ": and there it stays");
    }

    /** The commonest page switch: an account panel's Settings button, clicked or pressed with Space. */
    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void aPopoversButtonThatSwitchesThePageGivesTheKeyboardToTheNewPagesFirstStop(DisplayMode mode) {
        for (boolean byKey : new boolean[] {false, true}) {
            String how = mode + (byKey ? ", Space" : ", click");
            Button opener = new Button("Account");
            open(page(new TextField(), opener));
            scene.requestFocus(opener);
            TextField first = new TextField();
            Column settings = page(first, new TextField());
            Button go = new Button("Settings").onAction(() -> scene.setRoot(settings));
            Popover popover = new Popover(page(go)).setDisplayMode(mode);
            popover.showFor(opener);
            settle();
            Scene panel = go.scene();
            assertNotNull(panel, how);
            if (byKey) {
                panel.requestFocus(go);
                drive(panel).press(limn.input.Keys.SPACE);
            } else {
                drive(panel).click(go);
            }

            assertSame(settings, scene.root(), how + ": the button switched the page");
            assertSame(first, scene.focusedWidget(), how + ": the new page's first stop");
            settle();
            assertFalse(popover.isOpen(), how);
            assertSame(first, scene.focusedWidget(), how + ": and there it stays");
        }
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anOpenComboBoxListOfTheOldPageGivesTheKeyboardToTheNewPagesFirstStop(DisplayMode mode) {
        ComboBox<String> combo = new ComboBox<>(List.of("one", "two", "three"));
        combo.setDisplayMode(mode);
        open(page(combo));
        scene.requestFocus(combo);
        combo.open();
        settle();
        assertTrue(combo.isOpen(), mode.name());
        TextField first = new TextField();

        scene.setRoot(page(first, new TextField()));

        assertSame(first, scene.focusedWidget(), mode + ": the new page's first stop");
        settle();
        assertFalse(combo.isOpen(), mode.name());
        assertSame(first, scene.focusedWidget(), mode + ": and there it stays");
    }

    /** In the scene: the menus of a window whose bar the system does not draw. */
    @Test
    void anOpenMenuBarDropdownOfTheOldPageGivesTheKeyboardToTheNewPagesFirstStop() {
        boolean letters = limn.scene.internal.AccessLetterSystem.setPresentForTests(true); // Alt+F, whatever the host
        try {
            MenuBar bar = new MenuBar().setDisplayMode(DisplayMode.IN_SCENE)
                    .addMenu("&File", new Menu().addItem("Open", () -> { }));
            openOnAWindowThatPlacesNoOther(page(bar, new TextField()));
            drive(scene).press(limn.input.Keys.F, limn.input.Keys.MOD_ALT);
            frames();
            assertTrue(bar.isOpen(), "Alt+F opened the dropdown");
            assertNotNull(scene.focusedWidget(), "the keyboard is in the dropdown");
            TextField first = new TextField();

            scene.setRoot(page(first, new TextField()));

            assertSame(first, scene.focusedWidget(), "the new page's first stop");
            frames();
            assertFalse(bar.isOpen());
            assertSame(first, scene.focusedWidget(), "and there it stays");
        } finally {
            limn.scene.internal.AccessLetterSystem.setPresentForTests(letters);
        }
    }

    /** In the scene, as on a window that cannot place another. */
    @Test
    void anOpenTabListOfTheOldPageGivesTheKeyboardToTheNewPagesFirstStop() {
        TabbedPane pane = new TabbedPane();
        for (String title : List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")) {
            pane.addTab(title, new limn.scene.layout.SizedBox(60, 60));
        }
        openOnAWindowThatPlacesNoOther(page(new limn.scene.layout.SizedBox(200, 120, pane))); // narrower than six tabs
        scene.focusTraverse(false); // the strip, the pane's one tab stop
        drive(scene).keyEvent(limn.input.Keys.DOWN, true, false, limn.input.Keys.MOD_ALT);
        drive(scene).keyEvent(limn.input.Keys.DOWN, false, false, limn.input.Keys.MOD_ALT);
        drive(scene).inputBatchEnded();
        frames();
        assertTrue(pane.isTabListOpen(), "Alt+Down opened the list");
        TextField first = new TextField();

        scene.setRoot(page(first, new TextField()));

        assertSame(first, scene.focusedWidget(), "the new page's first stop");
        frames();
        assertFalse(pane.isTabListOpen());
        assertSame(first, scene.focusedWidget(), "and there it stays");
    }
}
