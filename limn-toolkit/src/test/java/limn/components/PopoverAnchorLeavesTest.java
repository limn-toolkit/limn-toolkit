package limn.components;

import limn.input.Keys;
import limn.scene.Change;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A popover goes when what it hangs from goes, as a combo box's list, a tab strip's list and a date
 * picker's calendar do: its anchor taken out of the window, or out with a container or a dialog it
 * was in, a popover it hangs from closing, or the window closing. Each is an adjustment, and none
 * leaves the panel over the window holding the keyboard, nor the popover saying it is open.
 */
class PopoverAnchorLeavesTest extends ComponentTestBase {

    private final long[] now = {TimeUnit.SECONDS.toNanos(1)};
    private final List<String> heard = new ArrayList<>();

    private HeadlessBackend backend;
    private HeadlessWindow owner;
    private Scene scene;

    /** A turn and a frame of every window, enough for a closing window's fade to end. */
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

    /** The owner window holding {@code root}, a desktop window as the popover's own needs one. */
    private void inWindow(Widget<?> root) {
        backend = new HeadlessBackend(runtime);
        owner = backend.open("Orders", 600, 400);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.bind(owner);
        owner.frame();
    }

    private Popover popover(String name, Widget<?> content, DisplayMode mode) {
        return new Popover(content).setAccessibleName(name).setDisplayMode(mode)
                .onClose(origin -> heard.add(name + " " + origin));
    }

    private static boolean inside(Widget<?> widget, Widget<?> subtree) {
        for (Widget<?> w = widget; w != null; w = w.parent()) {
            if (w == subtree) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the dialog it was opened in

    @Test
    void aPopoverWhoseApplyDismissesItsInSceneDialogClosesWithTheDialog() {
        // The shape the record gives a popover, a short form, opened from a dialog's button: its
        // Apply resolves the dialog. The popover's button leaves the window with the dialog, and the
        // popover hung over the window from nothing, holding the keyboard and every press.
        Button base = new Button("Base");
        int[] baseClicks = {0};
        base.onAction(() -> baseClicks[0]++);
        Column root = new Column();
        root.add(base);
        root.add(new SizedBox(300, 250, new Label("Orders")));
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        Button options = new Button("Options");
        Dialog dialog = new Dialog("Export", "Pick the options").setContent(options).addButton("Cancel", "cancel")
                .setDisplayMode(DisplayMode.IN_SCENE).keepInScene();
        Button apply = new Button("Apply and close");
        Column form = new Column();
        form.add(apply);
        Popover popover = popover("Export options", form, DisplayMode.IN_SCENE).attachTo(options);
        apply.onAction(() -> dialog.dismiss("applied"));
        List<String> results = new ArrayList<>();
        dialog.show(scene).thenAccept(results::add);
        runtime.drain();
        scene.layoutPass(400, 300);
        drive(scene).click(options);
        scene.layoutPass(400, 300);
        assertTrue(popover.isOpen(), "sanity: the dialog's button opened it");
        assertSame(apply, scene.focusedWidget(), "sanity: the keyboard is on Apply");

        drive(scene).press(Keys.SPACE);
        runtime.drain();
        runtime.drain();
        scene.layoutPass(400, 300);
        assertEquals(List.of("applied"), results, "sanity: the dialog resolved");
        assertNull(options.scene(), "sanity: its button left with it");
        assertFalse(popover.isOpen(), "the popover went with the dialog its button was in");
        assertEquals(List.of("Export options ADJUSTMENT"), heard);
        assertNull(form.scene(), "its panel is out of the window");
        Widget<?> focused = scene.focusedWidget();
        assertFalse(focused != null && (focused == apply || focused == options),
                "the keyboard is on nothing that left: " + focused);

        drive(scene).click(base);
        assertEquals(1, baseClicks[0], "the first click on the window reaches it, and not a panel left over it");
    }

    // ------------------------------------------------------------------ the anchor taken out

    @Test
    void takingTheAnchorOutClosesItInTheScene() {
        Button filter = new Button("Filter");
        TextField field = new TextField().setAccessibleName("Customer contains");
        Column holder = new Column();
        holder.add(filter);
        Column root = new Column();
        root.add(holder);
        scene = new Scene(root, () -> now[0]);
        scene.setTextRuler(RULER);
        scene.layoutPass(400, 300);
        scene.requestFocus(filter);
        Popover popover = popover("Filter orders", new SizedBox(160, 30, field), DisplayMode.IN_SCENE);
        popover.showFor(filter);
        scene.layoutPass(400, 300);
        assertSame(field, scene.focusedWidget(), "sanity: the field inside has the keyboard");

        holder.remove(filter);
        assertFalse(popover.isOpen(), "its anchor left the window, and it with it");
        assertEquals(List.of("Filter orders ADJUSTMENT"), heard);
        assertNull(field.scene(), "its panel is out of the window");
        assertFalse(scene.focusedWidget() != null && inside(scene.focusedWidget(), field),
                "the keyboard is not in the panel");
        drive(scene).type("k");
        assertEquals("", field.text(), "what is typed no longer goes to the panel's field");

        // Not open any more, so the next showFor opens it, against a new anchor.
        Button again = new Button("Filter");
        root.add(again);
        scene.layoutPass(400, 300);
        popover.showFor(again);
        assertTrue(popover.isOpen(), "it opens again from another anchor");
    }

    @Test
    void takingOutAContainerOfTheAnchorClosesItInAWindowOfItsOwn() {
        Button filter = new Button("Filter");
        TextField own = new TextField();
        Column toolbar = new Column();
        toolbar.add(filter);
        Column root = new Column();
        root.add(own);
        root.add(toolbar);
        inWindow(root);
        scene.requestFocus(own);
        TextField field = new TextField().setAccessibleName("Customer contains");
        Popover popover = popover("Filter orders", new SizedBox(160, 30, field), DisplayMode.NATIVE_WINDOW);
        popover.showFor(filter);
        frames(3);
        assertSame(DisplayMode.NATIVE_WINDOW, popover.displayMode(), "sanity: a window of its own");
        drive(scene).type("a");
        assertEquals("a", field.text(), "sanity: the owner's keys reach the popover's field");

        root.remove(toolbar);
        assertFalse(popover.isOpen(), "its anchor's tool bar left the window, and it with it");
        assertEquals(List.of("Filter orders ADJUSTMENT"), heard);
        drive(scene).type("b");
        assertEquals("a", field.text(), "the owner's keys are no longer routed into it");
        assertEquals("b", own.text(), "they reach the owner's own field again");
        frames(40);
        assertNull(field.scene(), "its window is gone");
        assertEquals(1, backend.windows().stream().filter(w -> !w.isClosed()).count(),
                "the owner's window alone is left");
    }

    // ------------------------------------------------------------------ a popover inside another

    @Test
    void closingAnOuterPopoverClosesTheInnerOneAtOnceInAWindow() {
        closingTheOuterClosesTheInner(DisplayMode.NATIVE_WINDOW);
    }

    @Test
    void closingAnOuterPopoverClosesTheInnerOneAtOnceInTheScene() {
        closingTheOuterClosesTheInner(DisplayMode.IN_SCENE);
    }

    private void closingTheOuterClosesTheInner(DisplayMode mode) {
        Button outerButton = new Button("More");
        TextField own = new TextField();
        Column root = new Column();
        root.add(own);
        root.add(outerButton);
        inWindow(root);
        scene.requestFocus(own);
        Button innerButton = new Button("Columns");
        Column outerContent = new Column();
        outerContent.add(innerButton);
        Popover outer = popover("More", outerContent, mode).attachTo(outerButton);
        TextField innerField = new TextField();
        Popover inner = popover("Columns", new SizedBox(160, 30, innerField), mode).attachTo(innerButton);
        outer.showFor(outerButton);
        frames(3);
        inner.showFor(innerButton);
        frames(3);
        assertTrue(outer.isOpen() && inner.isOpen(), "sanity: both open");
        assertSame(mode, inner.displayMode(), "sanity: the inner one in the mode asked for");

        outer.close();
        assertFalse(inner.isOpen(), "the inner popover went with the panel its button was in, at once");
        assertFalse(outer.isOpen());
        assertEquals(List.of("Columns ADJUSTMENT", "More CODE"), heard,
                "the inner one first, as an adjustment; the outer one as the code's");
        frames(40);
        drive(scene).type("q");
        assertEquals("", innerField.text(), "nothing typed reaches the inner field");
        if (mode == DisplayMode.IN_SCENE) {
            assertSame(outerButton, scene.focusedWidget(),
                    "the keyboard was in the outer one, so it is on its button, as after any popover");
        }
        assertNull(innerField.scene(), "the inner panel is gone");
        inner.showFor(outerButton);
        assertTrue(inner.isOpen(), "the inner popover opens again: it no longer thinks it is open");
    }

    @Test
    void closingTheOuterPopoverInTheSceneTakesTheKeyboardHomeAtOnce() {
        nestedInScene(true);
    }

    @Test
    void escapeTwiceInTheSceneTakesTheKeyboardHomeAtOnce() {
        nestedInScene(false);
    }

    /**
     * The inner popover's layer fades on top, and the outer one could not give the keyboard back
     * under it: for the outer one's fade the keyboard stayed on the inner's button in the panel
     * going away, published focused, and Space opened the inner popover again from there.
     */
    private void nestedInScene(boolean byCode) {
        Button more = new Button("More");
        Column root = new Column();
        root.add(more);
        root.add(new SizedBox(300, 200, new Label("Orders")));
        inWindow(root);
        runtime.setDelayClock(() -> now[0]);
        scene.requestFocus(more);
        Button columns = new Button("Columns");
        Column outerContent = new Column();
        outerContent.add(columns);
        Popover outer = popover("More", outerContent, DisplayMode.IN_SCENE).attachTo(more);
        Popover inner = popover("Columns", new SizedBox(160, 30, new TextField()), DisplayMode.IN_SCENE)
                .attachTo(columns);
        drive(scene).click(more);
        frames(20);
        assertSame(columns, scene.focusedWidget(), "sanity: on the outer one's button");
        drive(scene).press(Keys.SPACE);
        frames(20);
        assertTrue(inner.isOpen(), "sanity: the inner one is open");
        if (byCode) {
            outer.close();
        } else {
            drive(scene).press(Keys.ESCAPE);
            frames(1);
            drive(scene).press(Keys.ESCAPE);
        }
        assertFalse(outer.isOpen() || inner.isOpen());
        assertSame(more, scene.focusedWidget(), "the keyboard is home at once, not in the panel fading out");
        drive(scene).press(Keys.SPACE);
        frames(1);
        assertFalse(inner.isOpen(), "Space during the fade opens nothing from the panel going away");
        assertTrue(outer.isOpen(), "it reached the button at home, which opens the outer one again");
        frames(40);
        assertTrue(outer.isOpen() && !inner.isOpen());
    }

    // ------------------------------------------------------------------ the window closing

    @Test
    void theOwnerWindowClosingClosesItInAWindowOfItsOwn() {
        theOwnerWindowClosingClosesIt(DisplayMode.NATIVE_WINDOW);
    }

    @Test
    void theOwnerWindowClosingClosesItInTheScene() {
        theOwnerWindowClosingClosesIt(DisplayMode.IN_SCENE);
    }

    private void theOwnerWindowClosingClosesIt(DisplayMode mode) {
        Button filter = new Button("Filter");
        Column root = new Column();
        root.add(filter);
        inWindow(root);
        TextField field = new TextField();
        Popover popover = popover("Filter orders", new SizedBox(160, 30, field), mode);
        popover.showFor(filter);
        frames(3);
        assertTrue(popover.isOpen(), "sanity");
        owner.requestClose();
        runtime.drain();
        assertFalse(popover.isOpen(), "the window it hung in closed, and it with it");
        assertEquals(List.of("Filter orders ADJUSTMENT"), heard);
        assertNotSame(field, scene.focusedWidget(), "a closed window focuses nothing of the popover's");
    }
}
