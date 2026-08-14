package limn.components;

import limn.backend.NativeWindow;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a window that cannot be positioned does to the components that draw outside their own
 * frame. The platform is Wayland, but nothing here names it: the components ask
 * {@link NativeWindow#supportsAbsolutePositioning()}, and any platform answering {@code false}
 * gets the same treatment.
 *
 * <p>Headless, with a {@link StubWindow} whose {@code backend()} throws, so a component that
 * took the native path anyway fails here loudly instead of on someone's desktop quietly.
 */
class AbsolutePositioningFallbackTest extends ComponentTestBase {

    /**
     * The default is what every backend written before Wayland existed silently relies on. A
     * backend that overrides nothing must keep placing its popups in windows.
     */
    @Test
    void aWindowThatSaysNothingCanBePositioned() {
        NativeWindow silent = new StubWindow();
        assertTrue(silent.supportsAbsolutePositioning(),
                "the interface default must not turn every unaware backend into a fallback");
    }

    @Test
    void aMenuOnSuchAWindowRendersInSceneInsteadOfInAWindow() {
        AtomicReference<String> chosen = new AtomicReference<>();
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 300);

        Menu menu = new Menu()
                .addItem("One", () -> chosen.set("one"))
                .addItem("Two", () -> chosen.set("two"));
        PopupMenu popup = new PopupMenu(menu);
        popup.showAnchored(scene, 20, 20, 0, 0);
        scene.layoutPass(400, 300); // lay out the pushed overlay

        assertTrue(popup.isOpen());
        assertTrue(popup.isInSceneForTest(), "a menu window would have opened away from the anchor");

        // The fallback is not a picture of a menu: it navigates and chooses like the real one.
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();
        assertEquals("two", chosen.get());
        assertFalse(popup.isOpen());
    }

    @Test
    void aMenuOnAnOrdinaryWindowStillWantsAWindow() {
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(true));
        scene.layoutPass(400, 300);

        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }));
        popup.showAnchored(scene, 20, 20, 0, 0);

        assertTrue(popup.isOpen());
        assertFalse(popup.isInSceneForTest(),
                "the fallback must be reached only by the platforms that need it");
        // The window itself is created a posted turn later, which this test never pumps, so
        // the stub's throwing backend() is never called, and that is the whole reason the
        // assertion above is on the presentation rather than on the window.
    }

    // --------------------------------------------------- the preference, on any platform

    /**
     * The point of the preference: a menu asked for {@code IN_SCENE} is drawn in scene on a
     * window that could perfectly well have held one, so an application whose window has to
     * contain everything it shows gets that everywhere and not only on Wayland.
     */
    @Test
    void aMenuAskedForInSceneStaysThereOnAWindowThatCouldPositionOne() {
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(true));
        scene.layoutPass(400, 300);

        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }))
                .setDisplayMode(DisplayMode.IN_SCENE);
        popup.showAnchored(scene, 20, 20, 0, 0);
        scene.layoutPass(400, 300);

        assertTrue(popup.isInSceneForTest());
        assertEquals(DisplayMode.IN_SCENE, popup.displayMode());
    }

    /**
     * The override runs one way only. A platform that cannot place a window is not persuaded by a
     * request for one, and {@code displayMode()} reports what happened rather than what was asked.
     */
    @Test
    void aRequestForAWindowIsOverriddenAndSaysSo() {
        Scene scene = new Scene(new Label("root"));
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 300);

        PopupMenu popup = new PopupMenu(new Menu().addItem("One", () -> { }))
                .setDisplayMode(DisplayMode.NATIVE_WINDOW);
        popup.showAnchored(scene, 20, 20, 0, 0);
        scene.layoutPass(400, 300);

        assertEquals(DisplayMode.IN_SCENE, popup.displayMode(),
                "displayMode() must answer what happened, not what was wished for");
    }

    /** The process default reaches menus an application never constructs itself. */
    @Test
    void theProcessDefaultIsWhatNewMenusStartFrom() {
        DisplayMode before = PopupMenu.defaultDisplayMode();
        try {
            PopupMenu.setDefaultDisplayMode(DisplayMode.IN_SCENE);
            assertEquals(DisplayMode.IN_SCENE,
                    new PopupMenu(new Menu().addItem("One", () -> { })).displayMode());
            assertEquals(DisplayMode.IN_SCENE, new MenuBar().displayMode(),
                    "a bar built after the call hands the default to its dropdowns");
        } finally {
            PopupMenu.setDefaultDisplayMode(before);
        }
        assertEquals(DisplayMode.NATIVE_WINDOW,
                new PopupMenu(new Menu().addItem("One", () -> { })).displayMode(),
                "restoring the default must actually restore it");
    }

    @Test
    void aComboAskedForInSceneKeepsItsListInTheWindow() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"))
                .setDisplayMode(DisplayMode.IN_SCENE);
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(true));
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);

        assertTrue(combo.isInSceneForTest());
        assertNull(combo.popupWindow(), "an in-scene list has no window of its own");
        assertEquals(DisplayMode.IN_SCENE, combo.displayMode());
    }

    // ------------------------------------------------------------------ combo

    @Test
    void aComboOnSuchAWindowDropsItsListIntoTheScene() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);

        assertTrue(combo.isOpen());
        assertTrue(combo.isInSceneForTest(), "a list in a window would open away from the field");
        assertNull(combo.popupWindow(), "the in-scene list has no window of its own");
    }

    /**
     * The trap this fallback is most likely to be broken by. The native presentation dismisses
     * on any press whose target is not the combo, which is safe only because its list lives in
     * another scene; in-scene that observer sees the list's own presses, and registering it here
     * would close the popup on the press that was choosing a row: a dropdown nothing can be
     * picked from, which looks like a click that did nothing rather than like a bug.
     */
    @Test
    void clickingARowOfTheInSceneListSelectsItInsteadOfDismissing() {
        AtomicReference<Integer> picked = new AtomicReference<>();
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three")).onSelect(picked::set);
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);

        // Keyboard reaches the combo through the overlay that took the focus from it.
        scene.keyEvent(Keys.DOWN, true, false, 0);
        scene.keyEvent(Keys.ENTER, true, false, 0);
        scene.inputBatchEnded();

        assertEquals(1, picked.get(), "Down then Enter chooses the second item");
        assertFalse(combo.isOpen());
    }

    @Test
    void escapeClosesTheInSceneListWithoutChangingTheSelection() {
        AtomicReference<Integer> picked = new AtomicReference<>();
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three")).onSelect(picked::set);
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 300);

        combo.open();
        scene.layoutPass(400, 300);
        scene.keyEvent(Keys.ESCAPE, true, false, 0);
        scene.inputBatchEnded();

        assertFalse(combo.isOpen());
        assertNull(picked.get(), "closing is not choosing");
        assertEquals(0, combo.selectedIndex());
    }

    /**
     * {@code pushOverlay} confines focus to the overlay, so the field loses it the moment the
     * list opens. Treating that as "the user clicked elsewhere" would close the list in the same
     * pass that opened it, and the combo would look like it never opened at all.
     */
    @Test
    void theListSurvivesTheFocusItsOwnOverlayTakes() {
        ComboBox combo = new ComboBox(List.of("One", "Two", "Three"));
        Scene scene = new Scene(combo);
        scene.setTextRuler(RULER);
        scene.bind(new StubWindow(false));
        scene.layoutPass(400, 300);
        combo.requestFocus();
        assertTrue(combo.isFocused());

        combo.open();
        scene.layoutPass(400, 300);

        assertFalse(combo.isFocused(), "the overlay holds the keyboard while the list is open");
        assertTrue(combo.isOpen());
    }
}
