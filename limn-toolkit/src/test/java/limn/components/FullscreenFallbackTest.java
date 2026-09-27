package limn.components;

import limn.components.date.DatePicker;
import limn.graphics.Color;
import limn.scene.Scene;
import limn.testing.StubWindow;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a fullscreen owner does to the surfaces that float above it. A fullscreen window has no
 * room for a second one: on a monitor it is minimized when another window takes the focus, and
 * macOS native fullscreen is a Space of its own that a new window opens outside of. So every
 * surface asked for {@link DisplayMode#NATIVE_WINDOW} is drawn in scene there, and each one's
 * {@code displayMode()} says so.
 *
 * <p>Headless, over windows that refuse to create another: {@link StubWindow#backend()} throws
 * and {@link OverlayHost#createWindow} throws, so a surface that took the native path anyway
 * fails here with an assertion instead of minimizing someone's game. A popup's own window is
 * created a posted turn after it opens, which these tests never pump, so for the popups the
 * assertion is on the presentation they chose rather than on a window.
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
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(fullscreen(new StubWindow()));
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);

        assertTrue(combo.isOpen());
        assertTrue(combo.isInSceneForTest(), "a list window would have minimized its owner");
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
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
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
                "a calendar window would have minimized its owner");
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

        assertNull(dialog.modalWindow(), "a dialog window would have minimized its owner");
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
        assertNull(dialog.modalWindow(), "a picker window would have minimized its owner");
        assertEquals(DisplayMode.IN_SCENE, dialog.displayMode());
        assertEquals(DisplayMode.IN_SCENE, well.pickerDisplayMode(),
                "while the picker is up the well answers how it is actually presented");
    }
}
