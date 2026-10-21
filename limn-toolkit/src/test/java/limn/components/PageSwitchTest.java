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
}
