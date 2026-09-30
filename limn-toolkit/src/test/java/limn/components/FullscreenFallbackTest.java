package limn.components;

import limn.backend.Backend;
import limn.backend.spi.FrameCallback;
import limn.backend.spi.FrameInfo;
import limn.backend.spi.GpuRenderer;
import limn.backend.NativeWindow;
import limn.backend.WindowConfig;
import limn.backend.spi.WindowInput;
import limn.components.date.DateField;
import limn.components.date.DatePicker;
import limn.concurrent.UiRuntime;
import limn.graphics.Canvas;
import limn.graphics.Color;
import limn.graphics.Image;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.scene.layout.Column;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import limn.backend.spi.WindowPeer;
import limn.backend.spi.BackendPeer;

/**
 * What a fullscreen owner does to the surfaces that float above it. A fullscreen window is given no
 * second one: at a named mode it is minimized as soon as another window takes the focus, at the
 * mode the display is in it already covers the screen so a surface loses nothing drawn inside it,
 * and on a Mac without a GPU a window is slow to open. So every surface asked for
 * {@link DisplayMode#NATIVE_WINDOW} is drawn in scene there, and each one's {@code displayMode()}
 * says so. The stub's fullscreen is of no particular kind; the rule is the same for both.
 *
 * <p>And what an owner going fullscreen does to one already open as a window of its own: a menu,
 * a list or a calendar closes as if dismissed, since the change resizes the owner under a surface
 * placed against its old geometry, a modal dialog moves into the scene, and a surface already
 * drawn in scene is left where it is.
 *
 * <p>Headless, over windows that refuse to create another: {@link StubWindow#backend()} throws
 * and {@link OverlayHost#createWindow} throws, so a surface that took the native path anyway
 * fails here with an assertion instead of minimizing someone's game. A popup's own window is
 * created a posted turn after it opens, which the first tests never pump, so for the popups the
 * assertion is on the presentation they chose rather than on a window. The tests of a change
 * while open do pump it, over a {@link WindowHost} whose backend makes windows that do nothing.
 */
class FullscreenFallbackTest extends ComponentTestBase {

    private static StubWindow fullscreen(StubWindow window) {
        window.enterFullscreen();
        assertTrue(window.isFullscreen(), "sanity: the stub reports what it was told");
        return window;
    }

    // ------------------------------------------------------------------ menu

    /** The surface that already kept the rule, and the one the others are measured against. */
    @Test
    void aMenuOverAFullscreenWindowIsDrawnInScene() {
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(new StubWindow()));
        scene.layoutPass(400, 300);

        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }));
        popup.showAnchored(scene, 20, 20, 0, 0);
        scene.layoutPass(400, 300);

        assertTrue(popup.isInSceneForTest());
        assertEquals(DisplayMode.IN_SCENE, popup.displayMode());
    }

    // ------------------------------------------------------------------ combo

    @Test
    void aComboOverAFullscreenWindowDropsItsListIntoTheScene() {
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(new StubWindow()));
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);

        assertTrue(combo.isOpen());
        assertTrue(combo.isInSceneForTest(),
                "a list window would minimize an owner fullscreen at a named mode");
        assertNull(combo.popupWindow(), "the in-scene list has no window of its own");
        assertEquals(DisplayMode.IN_SCENE, combo.displayMode(),
                "displayMode() must answer what happened, not what was wished for");
    }

    /**
     * The decision is made on each open, not once for the life of the combo: a window that has
     * left fullscreen can hold a list window again, and the next open asks for one.
     */
    @Test
    void aComboOpenedAfterTheWindowLeavesFullscreenWantsAWindowAgain() {
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        StubWindow window = fullscreen(new StubWindow());
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(window);
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);
        assertTrue(combo.isInSceneForTest());
        combo.close();
        scene.layoutPass(400, 300);

        window.exitFullscreen();
        combo.open();
        assertTrue(combo.isOpen());
        assertFalse(combo.isInSceneForTest(), "nothing forces the overlay any more");
        assertEquals(DisplayMode.NATIVE_WINDOW, combo.displayMode());
    }

    // ------------------------------------------------------------------ date picker

    @Test
    void aDatePickerOverAFullscreenWindowDrawsItsCalendarInScene() {
        DatePicker picker = new DatePicker().setClock(
                Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC));
        Scene scene = new Scene(picker);
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(new StubWindow()));
        scene.layoutPass(400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));

        picker.open();
        scene.layoutPass(400, 320);

        assertTrue(picker.isOpen());
        assertEquals(DisplayMode.IN_SCENE, picker.displayMode(),
                "a calendar window would minimize an owner fullscreen at a named mode");
        assertNotNull(picker.calendar().parent(), "the grid is in the card that is showing");

        picker.close();
        assertEquals(DisplayMode.NATIVE_WINDOW, picker.displayMode(),
                "closed, the forced overlay is over and the request is the answer again");
    }

    // ------------------------------------------------------------------ dialog

    @Test
    void aModalDialogOverAFullscreenWindowIsAnOverlay() {
        OverlayHost window = new OverlayHost();
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(window));
        scene.layoutPass(400, 300);

        Dialog dialog = new Dialog("Unsaved changes", "Save first?")
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancel");
        dialog.show(scene);

        assertNull(dialog.modalWindow(),
                "a dialog window would minimize an owner fullscreen at a named mode");
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode(),
                "displayMode() must answer what happened, not what was wished for");
        assertSame(window, window.sceneModalOwner,
                "the overlay is modal like the window it replaces: its host is the one kept live");
    }

    /** Toolkit scope is kept: the overlay still locks every other window, as the window would. */
    @Test
    void aToolkitModalDialogOverAFullscreenWindowIsAnOverlayToo() {
        OverlayHost window = new OverlayHost();
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(window));
        scene.layoutPass(400, 300);

        Dialog dialog = new Dialog("Quit?", "Every window closes.")
                .addPrimaryButton("Quit", "quit")
                .setCancelResult("cancel");
        dialog.showToolkitModal(scene);

        assertNull(dialog.modalWindow());
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode());
        assertSame(window, window.sceneModalOwner);
    }

    // ------------------------------------------------------------------ colour well

    /**
     * The well raises a modal dialog, so it inherits the dialog's rule; what is under test is that
     * it does, and that the well answers for the picker that is up rather than for the next one.
     */
    @Test
    void theColourPickerOverAFullscreenWindowIsAnOverlay() {
        ColorPickerButton well = new ColorPickerButton(Color.rgb(0xF59E0B));
        Scene scene = new Scene(well);
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(new OverlayHost()));
        scene.layoutPass(400, 300);
        assertEquals(DisplayMode.NATIVE_WINDOW, well.pickerDisplayMode(), "sanity: the default");

        well.openPicker();

        Dialog dialog = well.openDialog();
        assertNotNull(dialog, "the well did not raise a picker");
        assertNull(dialog.modalWindow(),
                "a picker window would minimize an owner fullscreen at a named mode");
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode());
        assertEquals(DisplayMode.IN_SCENE, well.pickerDisplayMode(),
                "while the picker is up the well answers how it is actually presented");
    }

    // ------------------------------------------------------------------ fullscreen while open

    /**
     * A window whose backend makes windows, so a surface can be open as a window of its own when
     * its owner goes fullscreen. What it makes answers like a {@link StubWindow} and remembers being
     * asked to close; the modal calls are recorded, not enforced, except that the owner answers
     * {@link #isModalBlocked()} while a native modal is up, as a real one does.
     *
     * <p>Everything that decides where the desktop sends the focus is written to {@link #calls} in
     * the order it was asked: the owner's focus, a made window's show (a dialog's window takes the
     * focus as it is shown), focus, hide and close, and the native modals pushed and popped. The
     * order is the point: a window holding the focus that is hidden or closed before the owner asks
     * for it back leaves the choice of the next one to the desktop, and an owner the desktop passes
     * over loses the keyboard, and is minimized when it is fullscreen at a named mode.
     */
    private static final class WindowHost extends StubWindow implements BackendPeer {
        /** Every window made, in order. */
        final List<Made> made = new ArrayList<>();
        /** The native modals pushed and not yet popped. */
        final List<NativeWindow> modals = new ArrayList<>();
        /** The window the last in-scene modal named as the one kept live, while it is up. */
        NativeWindow sceneModalOwner;
        /** Whether that in-scene modal locks every window. */
        boolean sceneModalToolkitScope;
        /** What was asked of the windows and the modal stack, in order; cleared by a test. */
        final List<String> calls = new ArrayList<>();

        /** A window of this host's, answering to its backend as a real one does. */
        final class Made extends StubWindow {
            /** Its title, which names it in {@link #calls}. */
            final String name;
            boolean closeRequested;
            /** Whether its input method was told to drop what it was composing. */
            boolean preeditReset;
            /** What the scene bound here handed it, through which a test reports its focus. */
            WindowInput input;
            /** What the scene bound here renders with, run by a test as a backend's loop does. */
            FrameCallback frames;

            Made(String name) {
                this.name = name;
            }

            @Override public Backend backend() { return WindowHost.this; }
            @Override public void requestClose() {
                closeRequested = true;
                calls.add("close " + name);
            }
            @Override public void show() { calls.add("show " + name); }
            @Override public void hide() { calls.add("hide " + name); }
            @Override public void focus() { calls.add("focus " + name); }
            @Override public void resetPreedit() { preeditReset = true; }
            @Override public void setInput(WindowInput newInput) {
                super.setInput(newInput);
                input = newInput;
            }
            @Override public void setFrameCallback(FrameCallback callback) { frames = callback; }
            /** The opacity its scene's fades last set. */
            float opacity = 1;
            @Override public void setOpacity(float value) { opacity = value; }
        }

        private String nameOf(NativeWindow window) {
            return window instanceof Made m ? m.name : window == this ? "owner" : "?";
        }

        @Override public void focus() {
            calls.add("focus owner" + (isModalBlocked() ? " while blocked" : ""));
        }
        @Override public boolean isModalBlocked() { return modalBlocked || !modals.isEmpty(); }
        @Override public Backend backend() { return this; }
        @Override public UiRuntime uiRuntime() { return null; }
        @Override public WindowPeer createWindow(WindowConfig config) {
            Made window = new Made(config.title());
            made.add(window);
            return window;
        }
        @Override public void setLoopLifetime(limn.backend.LoopLifetime lifetime) { }
        @Override public limn.backend.LoopLifetime loopLifetime() { return limn.backend.LoopLifetime.UNTIL_LAST_WINDOW; }
        @Override public void requestQuit() { }
        @Override public void setQuitHandler(limn.backend.QuitHandler handler) { }
        @Override public limn.backend.LoopEnd runEventLoop() { return limn.backend.LoopEnd.STOPPED; }
        @Override public void pushModal(NativeWindow modal, NativeWindow parent) {
            modals.add(modal);
            calls.add("block by " + nameOf(modal));
        }
        @Override public void popModal(NativeWindow modal) {
            modals.remove(modal);
            calls.add("release " + nameOf(modal));
        }
        @Override public SceneModalHandle pushSceneModal(NativeWindow owner, boolean toolkitScope) {
            sceneModalOwner = owner;
            sceneModalToolkitScope = toolkitScope;
            calls.add("scene modal over " + nameOf(owner));
            return () -> sceneModalOwner = null;
        }
        @Override public void signalModalBlocked() { }
        @Override public void stop() { }
    }

    /** The desktop gave {@code window} the focus, as a backend reports it. */
    private static void focusedByTheDesktop(WindowHost.Made window) {
        window.input.windowFocusChanged(true);
        window.input.inputBatchEnded();
    }

    /** The desktop gave the focus to the owner {@code scene} is bound to, as a backend says. */
    private static void focusedByTheDesktop(Scene scene) {
        drive(scene).windowFocusChanged(true);
        drive(scene).inputBatchEnded();
    }

    /** The desktop took the focus from {@code window}, as a backend reports it. */
    private static void blurredByTheDesktop(WindowHost.Made window) {
        window.input.windowFocusChanged(false);
        window.input.inputBatchEnded();
    }

    /**
     * Runs {@code window}'s frames as a backend's loop does, the clock that drives them 20 ms on
     * each time, until {@code done} says so.
     */
    private static void runFrames(WindowHost.Made window, long[] nanos, BooleanSupplier done) {
        GpuRenderer renderer = new GpuRenderer() {
            private final Canvas canvas = new FakeCanvas(400, 300);
            @Override public Canvas canvas() { return canvas; }
            @Override public void clear(float red, float green, float blue, float alpha) { }
            @Override public void captureFramebuffer(Consumer<Image> sink) { }
        };
        for (int frame = 0; frame < 100 && !done.getAsBoolean(); frame++) {
            nanos[0] += TimeUnit.MILLISECONDS.toNanos(20);
            window.frames.onFrame(renderer, new FrameInfo(400, 300, 1f));
        }
    }

    /** Runs {@code window}'s frames until its fade-in has made it opaque, as it is once shown. */
    private static void fadeIn(WindowHost.Made window, long[] nanos) {
        runFrames(window, nanos, () -> window.opacity == 1);
        assertEquals(1, window.opacity, "sanity: the window faded in");
    }

    /** Runs {@code window}'s frames until the fade-out that ends in its close has asked for it. */
    private static void fadeOut(WindowHost.Made window, long[] nanos) {
        runFrames(window, nanos, () -> window.closeRequested);
        assertTrue(window.closeRequested, "sanity: the fade-out ended in a close");
    }

    /** The owner goes fullscreen as a backend reports it: the change, then the end of the batch. */
    private static void goFullscreen(Scene scene, StubWindow window) {
        window.enterFullscreen();
        drive(scene).inputBatchEnded();
    }

    private static void leaveFullscreen(Scene scene, StubWindow window) {
        window.exitFullscreen();
        drive(scene).inputBatchEnded();
    }

    private static Scene sceneOver(StubWindow window, limn.scene.Widget<?> root, float width,
                                   float height) {
        Scene scene = new Scene(root);
        scene.setTextRuler(RULER);
        scene.bind(window);
        scene.layoutPass(width, height);
        return scene;
    }

    /** {@link #sceneOver(StubWindow, limn.scene.Widget, float, float)} on a clock a test moves. */
    private static Scene sceneOver(StubWindow window, limn.scene.Widget<?> root, float width,
                                   float height, long[] nanos) {
        Scene scene = new Scene(root, () -> nanos[0]);
        scene.setTextRuler(RULER);
        scene.bind(window);
        scene.layoutPass(width, height);
        return scene;
    }

    // --- menu

    /** The cascade is one window, and it goes as a press elsewhere would take it: nothing is run. */
    @Test
    void aMenuWindowClosesWhenItsOwnerGoesFullscreenAndRunsNothing() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        AtomicInteger ran = new AtomicInteger();
        PopupMenu popup = new PopupMenu(new Menu()
                .addItem("One", ran::incrementAndGet)
                .addSubmenu("More", new Menu().addItem("Two", ran::incrementAndGet)));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain(); // the menu's window is made a turn after it opens
        assertNotNull(popup.popupWindow(), "sanity: the menu is a window of its own");
        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.RIGHT);
        assertEquals(2, popup.columnCountForTest(), "sanity: the cascade is open");

        goFullscreen(scene, window);

        assertFalse(popup.isOpen(), "a menu window is placed against the geometry the change "
                + "took away, and holding the focus it minimizes an owner fullscreen at a named "
                + "mode");
        assertNull(popup.popupWindow());
        assertEquals(0, ran.get(), "a dismissal runs no item");
    }

    @Test
    void aMenuInSceneIsLeftAloneWhenItsOwnerGoesFullscreen() {
        StubWindow window = new StubWindow();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }))
                .setDisplayMode(DisplayMode.IN_SCENE);
        popup.showAnchored(scene, 20, 20, 0, 0);

        goFullscreen(scene, window);

        assertTrue(popup.isOpen(), "an overlay has nowhere else to be");
        assertTrue(popup.isInSceneForTest());
    }

    /**
     * The window is made a posted turn after the menu opens, and the owner can go fullscreen in
     * between, before the change has been dispatched: the turn that would make it asks again.
     */
    @Test
    void aMenuWindowIsNotMadeOverAnOwnerThatWentFullscreenFirst() {
        StubWindow window = new StubWindow(); // its backend() fails the test if a window is made
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }));
        popup.showAnchored(scene, 20, 20, 0, 0);
        assertFalse(popup.isInSceneForTest(), "sanity: it opened towards a window");

        window.enterFullscreen();
        runtime.drain();

        assertFalse(popup.isOpen());
        assertNull(popup.popupWindow());
    }

    // --- combo

    @Test
    void aComboListWindowClosesWhenItsOwnerGoesFullscreenAndSelectsNothing() {
        WindowHost window = new WindowHost();
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        AtomicInteger selected = new AtomicInteger(-1);
        combo.onSelect(item -> selected.set(combo.selectedIndex()));
        Scene scene = sceneOver(window, combo, 400, 300);
        scene.requestFocus(combo);
        combo.open();
        runtime.drain();
        assertNotNull(combo.popupWindow(), "sanity: the list is a window of its own");
        drive(scene).press(Keys.DOWN);
        assertEquals(1, combo.highlightedIndex(), "sanity: the highlight moved");

        goFullscreen(scene, window);

        assertFalse(combo.isOpen(), "a list window is placed against the field's old position, "
                + "and at a click it minimizes an owner fullscreen at a named mode");
        assertNull(combo.popupWindow());
        assertEquals(0, combo.selectedIndex(), "the highlight is not a choice");
        assertEquals(-1, selected.get(), "and nothing was reported as one");
    }

    /** The other direction: a list the fullscreen owner put in scene stays when the owner leaves. */
    @Test
    void aComboListInSceneIsLeftAloneWhenItsOwnerLeavesFullscreen() {
        StubWindow window = fullscreen(new StubWindow());
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        Scene scene = sceneOver(window, combo, 400, 300);
        combo.open();
        assertTrue(combo.isInSceneForTest(), "sanity: forced in scene");

        leaveFullscreen(scene, window);

        assertTrue(combo.isOpen());
        assertTrue(combo.isInSceneForTest());
    }

    @Test
    void aComboListWindowIsNotMadeOverAnOwnerThatWentFullscreenFirst() {
        StubWindow window = new StubWindow();
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        Scene scene = sceneOver(window, combo, 400, 300);
        combo.open();
        assertFalse(combo.isInSceneForTest(), "sanity: it opened towards a window");

        window.enterFullscreen();
        runtime.drain();

        assertFalse(combo.isOpen());
        assertNull(combo.popupWindow());
    }

    // --- date picker

    private static DatePicker picker() {
        return new DatePicker().setClock(
                Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aCalendarWindowClosesWhenItsOwnerGoesFullscreenAndPicksNothing() {
        WindowHost window = new WindowHost();
        LocalDate chosen = LocalDate.of(2026, 9, 9);
        DatePicker picker = picker().setDate(chosen);
        AtomicInteger picked = new AtomicInteger();
        picker.onSelect(picked::incrementAndGet);
        Scene scene = sceneOver(window, picker, 400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        scene.requestFocus(picker.field());
        picker.open();
        runtime.drain();
        assertEquals(1, window.made.size(), "sanity: the calendar is a window of its own");
        drive(scene).press(Keys.DOWN);
        assertEquals(chosen.plusWeeks(1), picker.calendar().focusedDate(),
                "sanity: the grid's cursor moved");

        goFullscreen(scene, window);

        assertFalse(picker.isOpen(), "a calendar window is placed against the field's old "
                + "position, and at a click it minimizes an owner fullscreen at a named mode");
        assertEquals(chosen, picker.date(), "the cursor is not a pick");
        assertEquals(0, picked.get());
    }

    @Test
    void aCalendarInSceneIsLeftAloneWhenItsOwnerGoesFullscreen() {
        StubWindow window = new StubWindow();
        DatePicker picker = picker().setDisplayMode(DisplayMode.IN_SCENE);
        Scene scene = sceneOver(window, picker, 400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        picker.open();

        goFullscreen(scene, window);

        assertTrue(picker.isOpen());
        assertEquals(DisplayMode.IN_SCENE, picker.displayMode());
    }

    @Test
    void aCalendarWindowIsNotMadeOverAnOwnerThatWentFullscreenFirst() {
        StubWindow window = new StubWindow();
        DatePicker picker = picker();
        Scene scene = sceneOver(window, picker, 400, 320);
        scene.renderFrame(new FakeCanvas(400, 320));
        picker.open();

        window.enterFullscreen();
        runtime.drain();

        assertFalse(picker.isOpen());
    }

    // --- dialog

    /**
     * A modal dialog is a question the application is waiting on, so it is not dismissed: it moves
     * into the scene, as it would have opened there, with what the person had typed, where the
     * keyboard was, and its scope.
     */
    @Test
    void aModalDialogWindowMovesIntoTheSceneWhenItsOwnerGoesFullscreen() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        TextField name = new TextField();
        Dialog dialog = new Dialog("Rename", "A new name.")
                .setContent(name)
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        assertNotNull(dialog.modalWindow(), "sanity: the dialog is a window of its own");
        Scene card = dialog.contentRoot().scene();
        card.requestFocus(name);
        drive(card).type("draft");

        goFullscreen(scene, window);
        focusedByTheDesktop(scene);

        assertNull(dialog.modalWindow(), "a modal dialog over a fullscreen owner is drawn in "
                + "scene, however it opened: the window would minimize an owner fullscreen at a "
                + "named mode when the person reached it");
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode());
        assertTrue(window.made.get(0).closeRequested,
                "its window was asked to go once the owner had the focus");
        assertEquals(List.of(), window.modals, "and let go of the owner");
        assertSame(window, window.sceneModalOwner, "the overlay is modal over the same owner");
        assertFalse(window.sceneModalToolkitScope, "and no wider than the window was");
        assertFalse(dialog.result().toCompletableFuture().isDone(), "nobody answered it");
        assertSame(scene, name.scene(), "the card is in the owner's scene");
        assertSame(name, scene.focusedWidget(), "the keyboard is where the person left it");
        assertEquals("draft", name.text());

        leaveFullscreen(scene, window);
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode(),
                "an overlay is never promoted back into a window");
    }

    /**
     * The keyboard comes with the card as it was, not as if Tab had brought it there. The field is
     * the dialog's first stop, which the dialog focuses on opening, as it did in the live run: the
     * person typed, the owner went fullscreen, and the next key they typed replaced everything.
     */
    @Test
    void aFieldKeepsItsCaretWhenItsDialogMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        TextField name = new TextField();
        Dialog dialog = new Dialog("Rename", "A new name.")
                .setContent(name)
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        Scene card = dialog.contentRoot().scene();
        assertSame(name, card.focusedWidget(), "sanity: the field is the first stop");
        drive(card).type("hello");
        assertEquals(5, name.caretPosition(), "sanity");
        assertEquals(name.selectionStart(), name.selectionEnd(), "sanity: nothing selected");

        goFullscreen(scene, window);

        assertSame(name, scene.focusedWidget());
        assertEquals(5, name.caretPosition(), "the caret stays where the typing left it");
        assertEquals(5, name.selectionStart(), "and nothing is selected");
        assertEquals(5, name.selectionEnd(), "and nothing is selected");
        drive(scene).type("x");
        assertEquals("hellox", name.text(), "the next key goes on from there");
    }

    /**
     * The one thing that stays behind: a composition belongs to the input method of the window
     * being left, which cannot finish it in another, so it ends there, as a loss of focus ends it,
     * and the field keeps what was committed before it.
     */
    @Test
    void aCompositionInTheDialogsWindowEndsWhenItMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        TextField name = new TextField();
        Dialog dialog = new Dialog("Rename", "A new name.")
                .setContent(name)
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        Scene card = dialog.contentRoot().scene();
        drive(card).type("hello");
        drive(card).preeditChanged("か", 1, 0, 1);
        drive(card).inputBatchEnded();
        assertEquals("か", name.composingText(), "sanity: a composition is open");

        goFullscreen(scene, window);

        assertTrue(window.made.get(0).preeditReset, "the window's input method dropped it");
        assertEquals("", name.composingText(), "and the field no longer shows it");
        assertEquals("hello", name.text());
        assertEquals(5, name.caretPosition());
        assertEquals(5, name.selectionStart(), "nothing is selected");
    }

    /** A selection the person made is theirs, in both its ends and its direction. */
    @Test
    void aFieldKeepsItsSelectionWhenItsDialogMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        TextField name = new TextField();
        Dialog dialog = new Dialog("Rename", "A new name.")
                .setContent(name)
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        Scene card = dialog.contentRoot().scene();
        drive(card).type("hello");
        name.select(4, 1); // "ell", made from its end backward

        goFullscreen(scene, window);

        assertEquals(1, name.selectionStart());
        assertEquals(4, name.selectionEnd());
        assertEquals(1, name.caretPosition(), "the caret is still at the end it was dragged to");
    }

    /**
     * An area selects nothing when tabbed into, so there it was the loss of focus in the window
     * being left that took the selection.
     */
    @Test
    void anAreaKeepsItsSelectionWhenItsDialogMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        TextArea notes = new TextArea();
        Dialog dialog = new Dialog("Notes", "")
                .setContent(notes)
                .addPrimaryButton("Save", "save")
                .setCancelResult("cancel");
        dialog.show(scene);
        Scene card = dialog.contentRoot().scene();
        card.requestFocus(notes);
        drive(card).type("first\nsecond");
        notes.select(2, 9);

        goFullscreen(scene, window);

        assertSame(notes, scene.focusedWidget());
        assertEquals(2, notes.selectionStart());
        assertEquals(9, notes.selectionEnd());
        assertEquals(9, notes.caretPosition());
    }

    /**
     * A number half typed into a spinner is still being typed: the move is not the person moving
     * on, which is what commits it, so the entry stays open and goes on from where it was.
     */
    @Test
    void aSpinnerKeepsItsEntryOpenWhenItsDialogMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        TextField name = new TextField();
        Spinner copies = new Spinner(1, 999, 1);
        copies.setValue(5);
        Column content = new Column();
        content.add(name);
        content.add(copies);
        Dialog dialog = new Dialog("Print", "")
                .setContent(content)
                .addPrimaryButton("Print", "print")
                .setCancelResult("cancel");
        dialog.show(scene);
        Scene card = dialog.contentRoot().scene();
        card.requestFocus(copies);
        drive(card).type("12");
        assertTrue(copies.isEditing(), "sanity: typing opened an entry");

        goFullscreen(scene, window);

        assertSame(copies, scene.focusedWidget());
        assertTrue(copies.isEditing(), "the entry is still open");
        assertEquals(5.0, copies.value(), "and nothing was committed from it");
        drive(scene).type("3").press(Keys.ENTER);
        assertEquals(123.0, copies.value(), "the typing went on where it was");
    }

    /**
     * A year half typed is still being typed: the loss of focus is what settles two digits into a
     * century, and the move is not one, so the next two digits go on from the first two.
     */
    @Test
    void aDateFieldKeepsAYearHalfTypedWhenItsDialogMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        DateField due = new DateField()
                .setClock(Clock.fixed(Instant.parse("2026-09-16T12:00:00Z"), ZoneOffset.UTC));
        due.setLocale(Locale.forLanguageTag("pt-BR")); // day first, so the year is typed last
        Dialog dialog = new Dialog("Due", "")
                .setContent(due)
                .addPrimaryButton("Set", "set")
                .setCancelResult("cancel");
        dialog.show(scene);
        Scene card = dialog.contentRoot().scene();
        card.requestFocus(due);
        drive(card).type("311220");
        assertEquals(LocalDate.of(20, 12, 31), due.date(), "sanity: the caret is in the year");

        goFullscreen(scene, window);

        assertSame(due, scene.focusedWidget());
        assertEquals(LocalDate.of(20, 12, 31), due.date(), "nothing settled the year");
        drive(scene).type("26");
        assertEquals(LocalDate.of(2026, 12, 31), due.date(), "the next digits went on from those");
    }

    @Test
    void aToolkitModalDialogKeepsItsScopeWhenItMovesIntoTheScene() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        Dialog dialog = new Dialog("Quit?", "Every window closes.")
                .addPrimaryButton("Quit", "quit")
                .setCancelResult("cancel");
        dialog.showToolkitModal(scene);
        assertNotNull(dialog.modalWindow(), "sanity");

        goFullscreen(scene, window);

        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode());
        assertSame(window, window.sceneModalOwner);
        assertTrue(window.sceneModalToolkitScope, "every other window stays locked");
    }

    /** The documented exception: a non-modal dialog has no in-scene form, so it stays a window. */
    @Test
    void aNonModalDialogStaysAWindowWhenItsOwnerGoesFullscreen() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        Dialog dialog = new Dialog("Find", "").addButton("Close", "close");
        dialog.showNonModal(scene);

        goFullscreen(scene, window);

        assertNotNull(dialog.modalWindow());
        assertEquals(DisplayMode.NATIVE_WINDOW, dialog.displayMode());
        assertFalse(window.made.get(0).closeRequested);
    }

    /**
     * The other limit: a dialog something else is open over stays a window, and so does what is
     * over it, because moving the one underneath would close the window the other belongs to.
     */
    @Test
    void aModalDialogWithAnotherOverItStaysAWindowWhenItsOwnerGoesFullscreen() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        Dialog dialog = new Dialog("Settings", "").addPrimaryButton("Done", "done");
        dialog.show(scene);
        window.made.get(0).modalBlocked = true; // a second dialog is open over this one's window

        goFullscreen(scene, window);

        assertNotNull(dialog.modalWindow());
        assertEquals(DisplayMode.NATIVE_WINDOW, dialog.displayMode());
        assertFalse(window.made.get(0).closeRequested);
    }

    // --- colour well

    @Test
    void theColourPickerWindowMovesIntoTheSceneWhenItsOwnerGoesFullscreen() {
        WindowHost window = new WindowHost();
        Color amber = Color.rgb(0xF59E0B);
        ColorPickerButton well = new ColorPickerButton(amber);
        Scene scene = sceneOver(window, well, 400, 300);
        well.openPicker();
        Dialog dialog = well.openDialog();
        assertNotNull(dialog.modalWindow(), "sanity: the picker is a window of its own");

        goFullscreen(scene, window);

        assertSame(dialog, well.openDialog(), "the same picker, still up");
        assertNull(dialog.modalWindow());
        assertEquals(DisplayMode.IN_SCENE, well.pickerDisplayMode());
        assertEquals(amber, well.color(), "neither a pick nor a cancel reached the well");
    }

    // ------------------------------------------------------------------ the focus on the way out

    // A surface's window that holds the focus and is hidden or closed before its owner has asked
    // for the focus back leaves the desktop to choose the next window, and a desktop may choose
    // itself: on GNOME over X11 it did, which takes the keyboard from the owner, and GLFW
    // minimized the owner that had just gone fullscreen, as it still minimizes one fullscreen at a
    // named mode that loses the focus. Each test reads the order the host was asked in, from the
    // moment before the close to the window's close.

    /** A menu's window takes the focus as it opens, and the change closes it holding it. */
    @Test
    void theOwnerAsksForTheFocusBeforeAMenuWindowHoldingItClosesOnAFullscreenChange() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        goFullscreen(scene, window);
        fadeOut(menu, nanos);

        assertEquals(List.of("focus owner", "close menu"), window.calls,
                "the owner asks for the focus while the menu's window still holds it");
    }

    /**
     * A modal menu lets go of its owner first, since a blocked owner hands the focus straight back
     * to the modal over it. This order was already the menu's; the test keeps it.
     */
    @Test
    void theOwnerAsksForTheFocusBeforeAModalMenuWindowClosesOnAFullscreenChange() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { })).setModal(true);
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        assertEquals(List.of("show menu", "block by menu"), window.calls,
                "sanity: the menu blocks its owner");
        focusedByTheDesktop(menu);
        window.calls.clear();

        goFullscreen(scene, window);
        fadeOut(menu, nanos);

        assertEquals(List.of("release menu", "focus owner", "close menu"), window.calls);
    }

    /**
     * A choice hands the owner the keyboard at once, before the item runs, rather than at the end
     * of the fade: the menu's window, fading for 0.16 s, held the keyboard until then, and a Tab
     * pressed some 80 ms after choosing an item went to it and was lost, on Windows, macOS and X11.
     */
    @Test
    void theOwnerHasTheFocusAsSoonAsAnItemIsChosenFromTheKeyboard() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(
                new Menu().addItem("One", () -> window.calls.add("run One")));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.ENTER);

        assertEquals(List.of("focus owner", "run One"), window.calls,
                "the owner is asked as the item is chosen, before it runs");
        fadeOut(menu, nanos);
        assertEquals(List.of("focus owner", "run One", "close menu"), window.calls,
                "and not again at the end of the fade");
    }

    /** The same at a click on the row. */
    @Test
    void theOwnerHasTheFocusAsSoonAsAnItemIsClicked() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(
                new Menu().addItem("One", () -> window.calls.add("run One")));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        float[] column = popup.columnRectForTest(0);
        SizeTokens t = popup.tokensForTest();
        popup.clickForTest(column[0] + 30, column[1] + t.menuPadV() + t.menuRowHeight() / 2);

        assertEquals(List.of("focus owner", "run One"), window.calls);
        fadeOut(menu, nanos);
        assertEquals(List.of("focus owner", "run One", "close menu"), window.calls);
    }

    /**
     * A modal menu lets go of its lock first, at the choice too, since a blocked owner hands the
     * focus straight back to the modal over it.
     */
    @Test
    void aModalMenuLetsGoOfItsOwnerAsSoonAsAnItemIsChosen() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(
                new Menu().addItem("One", () -> window.calls.add("run One"))).setModal(true);
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.ENTER);
        fadeOut(menu, nanos);

        assertEquals(List.of("release menu", "focus owner", "run One", "close menu"),
                window.calls);
    }

    /**
     * The owner is asked once, at the choice, and never again: an item that opened a window of its
     * own gave that window the focus during the fade, and it keeps it.
     */
    @Test
    void aMenuWindowThatLostTheFocusDoesNotTakeItBackForItsOwner() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(new Menu().addItem("Find", () -> { }));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.ENTER);
        blurredByTheDesktop(menu); // the window the item opened took the focus
        fadeOut(menu, nanos);

        assertEquals(List.of("focus owner", "close menu"), window.calls);
    }

    /**
     * An item that opens a non-modal dialog: the owner is asked before the item runs, so the
     * dialog's window, shown after, is the one left with the focus, whatever the menu's scene last
     * heard of its own.
     */
    @Test
    void aMenuWhoseItemOpenedANonModalDialogLeavesTheFocusWithTheDialog() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(new Menu().addItem("Find", () ->
                new Dialog("Find", "").addButton("Close", "close").showNonModal(scene)));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.ENTER);
        fadeOut(menu, nanos);

        assertEquals(List.of("focus owner", "show Find", "close menu"), window.calls);
    }

    /** Nor one a modal dialog the item opened is holding, over an owner it blocks. */
    @Test
    void aMenuWhoseItemOpenedAModalDialogLeavesTheFocusWithTheDialog() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        PopupMenu popup = new PopupMenu(new Menu().addItem("Rename", () ->
                new Dialog("Rename", "A new name.").addPrimaryButton("Rename", "rename")
                        .setCancelResult("cancel").show(scene)));
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        window.calls.clear();

        popup.keyForTest(Keys.DOWN);
        popup.keyForTest(Keys.ENTER);
        fadeOut(menu, nanos);

        assertEquals(List.of("focus owner", "show Rename", "block by Rename", "close menu"),
                window.calls);
    }

    // ------------------------------------------------------------------ input during the fade

    // A closed menu fades for 0.16 s, and its window, or its overlay in scene, still receives
    // input meanwhile. None of it reaches the menu: an Enter or a click there ran the chosen item
    // a second time. A key that follows a dismissal is given to the owner's scene, as if the menu
    // were already gone; after a choice, or in scene, it is dropped.

    /** A key pressed and released in {@code window}, then the batch ended, as a backend says. */
    private static void keyIn(WindowHost.Made window, int key) {
        window.input.keyEvent(key, true, false, 0);
        window.input.keyEvent(key, false, false, 0);
        window.input.inputBatchEnded();
    }

    /** A menu's window, faded in and holding the focus, over {@code scene}. */
    private WindowHost.Made menuWindowUp(WindowHost window, PopupMenu popup, Scene scene,
                                         long[] nanos) {
        popup.showAnchored(scene, 20, 20, 0, 0);
        runtime.drain();
        WindowHost.Made menu = window.made.get(0);
        fadeIn(menu, nanos);
        focusedByTheDesktop(menu);
        return menu;
    }

    @Test
    void aSecondEnterWhileTheMenuFadesRunsTheItemOnce() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        AtomicInteger ran = new AtomicInteger();
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", ran::incrementAndGet));
        WindowHost.Made menu = menuWindowUp(window, popup, scene, nanos);

        keyIn(menu, Keys.DOWN);
        keyIn(menu, Keys.ENTER);
        assertEquals(1, ran.get(), "sanity: the item ran");
        keyIn(menu, Keys.ENTER); // the fading window still has the keyboard
        fadeOut(menu, nanos);

        assertEquals(1, ran.get(), "a closed menu chooses nothing");
    }

    @Test
    void aSecondClickWhileTheMenuFadesRunsTheItemOnce() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        AtomicInteger ran = new AtomicInteger();
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", ran::incrementAndGet));
        WindowHost.Made menu = menuWindowUp(window, popup, scene, nanos);
        float[] column = popup.columnRectForTest(0);
        SizeTokens t = popup.tokensForTest();
        float x = column[0] + 30;
        float y = column[1] + t.menuPadV() + t.menuRowHeight() / 2;

        popup.clickForTest(x, y);
        assertEquals(1, ran.get(), "sanity: the item ran");
        popup.clickForTest(x, y);
        fadeOut(menu, nanos);

        assertEquals(1, ran.get(), "a closed menu chooses nothing");
    }

    /** In scene too: the overlay keeps the owner's keyboard until its fade has ended. */
    @Test
    void aSecondEnterWhileAMenuInSceneFadesRunsTheItemOnce() {
        StubWindow window = fullscreen(new StubWindow());
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        AtomicInteger ran = new AtomicInteger();
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", ran::incrementAndGet));
        popup.showAnchored(scene, 20, 20, 0, 0);
        scene.layoutPass(400, 300);
        assertTrue(popup.isInSceneForTest(), "sanity: drawn in scene");

        drive(scene).press(Keys.DOWN).press(Keys.ENTER);
        assertEquals(1, ran.get(), "sanity: the item ran");
        drive(scene).press(Keys.ENTER);

        assertEquals(1, ran.get(), "a closed menu chooses nothing");
    }

    /**
     * After a dismissal the owner is where the keyboard goes, and the menu's window holds it only
     * until its fade ends: what is typed meanwhile reaches the owner's scene, as if the menu were
     * already gone.
     */
    @Test
    void keysTypedWhileADismissedMenuFadesReachTheOwner() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        TextField field = new TextField();
        Button next = new Button("Next");
        Column root = new Column();
        root.add(field);
        root.add(next);
        Scene scene = sceneOver(window, root, 400, 300, nanos);
        scene.requestFocus(field);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }));
        WindowHost.Made menu = menuWindowUp(window, popup, scene, nanos);

        keyIn(menu, Keys.ESCAPE);
        assertFalse(popup.isOpen(), "sanity: Escape closed it");
        menu.input.keyEvent(Keys.A, true, false, 0);
        menu.input.charTyped('a');
        menu.input.keyEvent(Keys.A, false, false, 0);
        menu.input.inputBatchEnded();
        keyIn(menu, Keys.TAB);
        fadeOut(menu, nanos);

        assertEquals("a", field.text(), "the character reached the owner's field");
        assertSame(next, scene.focusedWidget(), "and Tab moved the owner's focus");
    }

    /** A modal menu's own lock, held until its fade ends, does not keep the owner from them. */
    @Test
    void keysTypedWhileADismissedModalMenuFadesReachTheOwner() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        TextField field = new TextField();
        Scene scene = sceneOver(window, field, 400, 300, nanos);
        scene.requestFocus(field);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { })).setModal(true);
        WindowHost.Made menu = menuWindowUp(window, popup, scene, nanos);

        keyIn(menu, Keys.ESCAPE);
        assertTrue(window.isModalBlocked(), "sanity: the lock goes at the end of the fade");
        menu.input.charTyped('a');
        menu.input.inputBatchEnded();
        fadeOut(menu, nanos);

        assertEquals("a", field.text());
    }

    /**
     * After a choice the key is dropped: the window that has the keyboard once the menu is gone
     * may be one the item opened, and an Enter given to the owner instead would press whatever
     * the owner has focused, under a dialog the person is looking at.
     */
    @Test
    void keysTypedWhileAChosenMenuFadesAreDropped() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        TextField field = new TextField();
        Button next = new Button("Next");
        Column root = new Column();
        root.add(field);
        root.add(next);
        Scene scene = sceneOver(window, root, 400, 300, nanos);
        scene.requestFocus(field);
        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }));
        WindowHost.Made menu = menuWindowUp(window, popup, scene, nanos);

        keyIn(menu, Keys.DOWN);
        keyIn(menu, Keys.ENTER);
        menu.input.charTyped('a');
        menu.input.inputBatchEnded();
        keyIn(menu, Keys.TAB);
        fadeOut(menu, nanos);

        assertEquals("", field.text());
        assertSame(field, scene.focusedWidget());
    }

    /** A bar of three menus, File, Edit and View, with the first one's window up and focused. */
    private WindowHost.Made menuBarWalkFromFile(WindowHost window, long[] nanos) {
        MenuBar bar = new MenuBar()
                .addMenu("File", new Menu().addItem("New", () -> { }))
                .addMenu("Edit", new Menu().addItem("Copy", () -> { }))
                .addMenu("View", new Menu().addItem("Zoom", () -> { }));
        Scene scene = sceneOver(window, bar, 400, 300, nanos);
        scene.requestFocus(bar);
        drive(scene).press(Keys.DOWN);
        runtime.drain();
        assertEquals(1, window.made.size(), "sanity: Down opened File in a window of its own");
        WindowHost.Made file = window.made.get(0);
        fadeIn(file, nanos);
        focusedByTheDesktop(file);
        return file;
    }

    /**
     * Right twice in quick succession walks a menu bar two menus on. The second Right reaches the
     * window of the menu the first one walked away from, which fades and still holds the focus,
     * here before the next menu's window is even made.
     */
    @Test
    void aSecondRightBeforeTheNextMenusWindowIsMadeWalksOnAgain() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        WindowHost.Made file = menuBarWalkFromFile(window, nanos);

        keyIn(file, Keys.RIGHT);
        keyIn(file, Keys.RIGHT);
        runtime.drain(); // makes Edit's window, which is where the held Right is answered
        runtime.drain(); // and makes View's, asked for as it was

        assertEquals(3, window.made.size(), "the second Right walked from Edit to View");
    }

    /** And once the next menu's window is up, before the desktop has given it the focus. */
    @Test
    void aSecondRightWhileTheWalkedFromMenuFadesWalksOnAgain() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        WindowHost.Made file = menuBarWalkFromFile(window, nanos);

        keyIn(file, Keys.RIGHT);
        runtime.drain();
        assertEquals(2, window.made.size(), "sanity: the first Right opened Edit");
        keyIn(file, Keys.RIGHT);
        runtime.drain();

        assertEquals(3, window.made.size(), "the second Right walked from Edit to View");
    }

    /**
     * A key reaching the walked-from menu's window after the menu walked to was dismissed goes where
     * that menu's own keys go, the owner's scene: the walk passes keys on, it does not decide them.
     */
    @Test
    void aKeyWhileTheWalkedFromMenuFadesFollowsTheWalkedToMenusDismissal() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        TextField field = new TextField();
        MenuBar bar = new MenuBar()
                .addMenu("File", new Menu().addItem("New", () -> { }))
                .addMenu("Edit", new Menu().addItem("Copy", () -> { }));
        Column root = new Column();
        root.add(bar);
        root.add(field);
        Scene scene = sceneOver(window, root, 400, 300, nanos);
        scene.requestFocus(bar);
        drive(scene).press(Keys.DOWN);
        runtime.drain();
        WindowHost.Made file = window.made.get(0);
        fadeIn(file, nanos);
        focusedByTheDesktop(file);

        keyIn(file, Keys.RIGHT);
        keyIn(file, Keys.ESCAPE); // walked on to Edit, and Escape dismisses Edit
        runtime.drain();
        scene.requestFocus(field);
        file.input.charTyped('a');
        file.input.inputBatchEnded();
        fadeOut(file, nanos);

        assertEquals("a", field.text(), "the character reached the owner's field");
    }

    /**
     * A modal dialog's window holds the focus over an owner it blocks, so the move lets go of the
     * owner, moves the card, asks the owner for the focus, and sends the window away only once the
     * owner reports having it. Hidden in the same instant the owner was asked, the window went
     * before the window manager had given the owner the focus, and on GNOME over X11 the owner lost
     * it again some 20 ms later.
     */
    @Test
    void aModalDialogsWindowGoesOnlyOnceTheOwnerHasTheFocus() {
        long[] nanos = {0};
        runtime.setDelayClock(() -> nanos[0]);
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        Dialog dialog = new Dialog("Rename", "A new name.")
                .setContent(new TextField())
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        assertEquals(List.of("show Rename", "block by Rename"), window.calls,
                "sanity: it blocks its owner");
        window.calls.clear();

        goFullscreen(scene, window);

        assertEquals(List.of("release Rename", "scene modal over owner", "focus owner"),
                window.calls, "the window stays up while the owner is asked for the focus");
        WindowHost.Made left = window.made.get(0);
        assertEquals(0, left.opacity, "out of sight meanwhile");

        focusedByTheDesktop(scene);

        assertEquals(List.of("release Rename", "scene modal over owner", "focus owner",
                "hide Rename", "close Rename"), window.calls);

        nanos[0] += TimeUnit.MILLISECONDS.toNanos(Dialog.OWNER_FOCUS_WAIT_MILLIS);
        runtime.drain();
        focusedByTheDesktop(scene);
        assertEquals(5, window.calls.size(), "sent away once: " + window.calls);
    }

    /**
     * An owner that never reports the focus does not keep the window up: it goes when the wait
     * runs out, and a report that comes later sends nothing away twice.
     */
    @Test
    void aModalDialogsWindowGoesAnywayWhenTheOwnerNeverReportsTheFocus() {
        long[] nanos = {0};
        runtime.setDelayClock(() -> nanos[0]);
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        Dialog dialog = new Dialog("Rename", "A new name.")
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        window.calls.clear();

        goFullscreen(scene, window);
        nanos[0] += TimeUnit.MILLISECONDS.toNanos(Dialog.OWNER_FOCUS_WAIT_MILLIS - 1);
        runtime.drain();

        assertFalse(window.made.get(0).closeRequested, "still waiting for the owner");

        nanos[0] += TimeUnit.MILLISECONDS.toNanos(1);
        runtime.drain();

        assertEquals(List.of("release Rename", "scene modal over owner", "focus owner",
                "hide Rename", "close Rename"), window.calls);
        focusedByTheDesktop(scene);
        assertEquals(5, window.calls.size(), "sent away once: " + window.calls);
    }

    /**
     * What the window still hears while it waits reaches nothing: the card answers to the owner's
     * scene now, and a key or a frame there must not reach it through the scene it left.
     */
    @Test
    void aModalDialogsWindowNeitherDrawsNorAnswersWhileItWaits() {
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300);
        AtomicInteger answers = new AtomicInteger();
        Dialog dialog = new Dialog("Rename", "A new name.")
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene).thenAccept(answer -> answers.incrementAndGet());
        WindowHost.Made left = window.made.get(0);

        goFullscreen(scene, window);
        scene.layoutPass(400, 300);
        Widget<?> card = dialog.contentRoot();
        float[] placed = {card.x(), card.y(), card.width(), card.height()};
        left.input.keyEvent(Keys.ESCAPE, true, false, 0);
        left.input.keyEvent(Keys.ESCAPE, false, false, 0);
        left.input.inputBatchEnded();
        left.frames.onFrame(new GpuRenderer() {
            private final Canvas canvas = new FakeCanvas(160, 90);
            @Override public Canvas canvas() { return canvas; }
            @Override public void clear(float red, float green, float blue, float alpha) { }
            @Override public void captureFramebuffer(Consumer<Image> sink) { }
        }, new FrameInfo(160, 90, 1f));

        assertEquals(0, answers.get(), "a key in the window left behind answers nothing");
        assertFalse(dialog.result().toCompletableFuture().isDone());
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode());
        assertEquals(java.util.Arrays.toString(placed), java.util.Arrays.toString(
                new float[] {card.x(), card.y(), card.width(), card.height()}),
                "a frame of the window left behind lays nothing out: the card is the owner's");
    }

    /** The same order on an ordinary answer, after the fade. */
    @Test
    void theOwnerAsksForTheFocusBeforeAnAnsweredDialogsWindowCloses() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        Scene scene = sceneOver(window, new Label("root"), 400, 300, nanos);
        Dialog dialog = new Dialog("Rename", "A new name.")
                .addPrimaryButton("Rename", "rename")
                .setCancelResult("cancel");
        dialog.show(scene);
        fadeIn(window.made.get(0), nanos);
        window.calls.clear();

        drive(dialog.contentRoot().scene()).press(Keys.ESCAPE);
        fadeOut(window.made.get(0), nanos);

        assertEquals("cancel", dialog.result().toCompletableFuture().getNow(null), "sanity");
        assertEquals(List.of("release Rename", "focus owner", "close Rename"), window.calls);
    }

    /**
     * The reference: a list's window, which a click in it made the focused one, hands the focus
     * back before its fade, and so before its close.
     */
    @Test
    void theOwnerAsksForTheFocusBeforeAListWindowHoldingItClosesOnAFullscreenChange() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        Scene scene = sceneOver(window, combo, 400, 300, nanos);
        combo.open();
        runtime.drain();
        WindowHost.Made list = window.made.get(0);
        fadeIn(list, nanos);
        focusedByTheDesktop(list);
        window.calls.clear();

        goFullscreen(scene, window);
        fadeOut(list, nanos);

        assertEquals(List.of("focus owner", "close popup"), window.calls);
    }

    /**
     * And the case the live run met every time: a list opens without taking the focus, so its
     * close leaves nothing for the desktop to choose and the owner is not asked.
     */
    @Test
    void aListWindowThatNeverHeldTheFocusClosesWithoutAskingForIt() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        ComboBox<String> combo = new ComboBox<>(List.of("One", "Two", "Three"));
        Scene scene = sceneOver(window, combo, 400, 300, nanos);
        combo.open();
        runtime.drain();
        fadeIn(window.made.get(0), nanos);
        window.calls.clear();

        goFullscreen(scene, window);
        fadeOut(window.made.get(0), nanos);

        assertEquals(List.of("close popup"), window.calls);
    }

    /** The other reference: a calendar's window, clicked into, the same way as a list's. */
    @Test
    void theOwnerAsksForTheFocusBeforeACalendarWindowHoldingItClosesOnAFullscreenChange() {
        long[] nanos = {0};
        WindowHost window = new WindowHost();
        DatePicker picker = picker();
        Scene scene = sceneOver(window, picker, 400, 320, nanos);
        scene.renderFrame(new FakeCanvas(400, 320));
        picker.open();
        runtime.drain();
        WindowHost.Made calendar = window.made.get(0);
        fadeIn(calendar, nanos);
        focusedByTheDesktop(calendar);
        window.calls.clear();

        goFullscreen(scene, window);
        fadeOut(calendar, nanos);

        assertEquals(List.of("focus owner", "close popup"), window.calls);
    }
}
