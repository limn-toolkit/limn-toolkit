package limn.scene;

import limn.input.Keys;
import limn.scene.event.KeyEvent;
import limn.scene.event.MouseEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modifier state on synthesized pointer events. The platform reports modifiers
 * only for key and button events (scroll callbacks carry none, and moves/drags
 * are derived from cursor positions), so the scene mirrors the held modifiers
 * and stamps them. Without this, "Shift constrains the drag" and "Ctrl+wheel
 * zooms" are unwritable against the toolkit.
 */
class InputModifiersTest extends SceneTestBase {

    /** Records the modifier mask of every mouse event it receives. */
    private static final class Recorder extends FixedBox {
        final List<MouseEvent> events = new ArrayList<>();

        Recorder() {
            super(200, 200);
            setFocusable(true);
        }

        @Override
        protected void onMouseEvent(MouseEvent event) {
            events.add(event);
        }

        @Override
        protected void onKeyEvent(KeyEvent event) {
            // Focused so key events (which announce modifiers) reach the scene.
        }

        int lastModifiersOf(MouseEvent.Type type) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).type() == type) {
                    return events.get(i).modifiers();
                }
            }
            throw new AssertionError("no " + type + " event was dispatched");
        }
    }

    private Recorder attach() {
        Recorder recorder = new Recorder();
        Scene scene = new Scene(recorder);
        scene.bind(new RecordingWindow());
        scene.layoutPass(200, 200);
        scene.renderFrame(new NoopCanvas(200, 200));
        this.scene = scene;
        return recorder;
    }

    private Scene scene;

    private void pump() {
        scene.inputBatchEnded();
    }

    @Test
    void dragCarriesModifiersPressedBeforeTheDrag() {
        Recorder recorder = attach();
        scene.mouseButton(Keys.MOUSE_LEFT, true, Keys.MOD_SHIFT, 50, 50);
        scene.mouseMoved(70, 70);
        pump();
        assertEquals(Keys.MOD_SHIFT, recorder.lastModifiersOf(MouseEvent.Type.DRAG),
                "a drag must report the modifiers held at press time");
    }

    @Test
    void dragPicksUpAModifierPressedMidDrag() {
        // The real-world case: start dragging, THEN hold Shift to constrain.
        Recorder recorder = attach();
        scene.mouseButton(Keys.MOUSE_LEFT, true, 0, 50, 50);
        scene.mouseMoved(60, 60);
        pump();
        assertEquals(0, recorder.lastModifiersOf(MouseEvent.Type.DRAG));

        scene.keyEvent(Keys.LEFT_SHIFT, true, false, 0); // GLFW omits the bit here
        scene.mouseMoved(70, 70);
        pump();
        assertEquals(Keys.MOD_SHIFT, recorder.lastModifiersOf(MouseEvent.Type.DRAG),
                "pressing Shift during the drag must be visible to the widget");

        scene.keyEvent(Keys.LEFT_SHIFT, false, false, Keys.MOD_SHIFT); // release still announces it
        scene.mouseMoved(80, 80);
        pump();
        assertEquals(0, recorder.lastModifiersOf(MouseEvent.Type.DRAG),
                "releasing it must clear the bit");
    }

    @Test
    void wheelCarriesModifiersEvenThoughThePlatformOmitsThem() {
        Recorder recorder = attach();
        scene.keyEvent(Keys.LEFT_CONTROL, true, false, 0);
        scene.scrolled(0, 1, 50, 50);
        pump();
        assertEquals(Keys.MOD_CONTROL, recorder.lastModifiersOf(MouseEvent.Type.WHEEL),
                "Ctrl+wheel is the canonical zoom gesture: the mask must arrive");
    }

    @Test
    void moveCarriesHeldModifiers() {
        Recorder recorder = attach();
        scene.keyEvent(Keys.LEFT_ALT, true, false, 0);
        scene.mouseMoved(90, 90);
        pump();
        assertEquals(Keys.MOD_ALT, recorder.lastModifiersOf(MouseEvent.Type.MOVE));
    }

    @Test
    void severalModifiersCombine() {
        Recorder recorder = attach();
        scene.keyEvent(Keys.LEFT_SHIFT, true, false, 0);
        scene.keyEvent(Keys.LEFT_ALT, true, false, Keys.MOD_SHIFT);
        scene.mouseMoved(90, 90);
        pump();
        int mask = recorder.lastModifiersOf(MouseEvent.Type.MOVE);
        assertTrue((mask & Keys.MOD_SHIFT) != 0, "shift still held");
        assertTrue((mask & Keys.MOD_ALT) != 0, "alt added");
    }

    @Test
    void buttonPressResyncsFromTheNativeMask() {
        // The authoritative source when it exists: a press mask wins over the
        // mirror, so a modifier pressed while the window was unfocused heals.
        Recorder recorder = attach();
        scene.mouseButton(Keys.MOUSE_LEFT, true, Keys.MOD_SUPER, 50, 50);
        scene.mouseMoved(60, 60);
        pump();
        assertEquals(Keys.MOD_SUPER, recorder.lastModifiersOf(MouseEvent.Type.DRAG));
    }

    @Test
    void windowBlurClearsStuckModifiers() {
        // Alt-tab: the OS delivers no key-up, so without clearing, the toolkit
        // would believe the modifier is held forever.
        Recorder recorder = attach();
        scene.keyEvent(Keys.LEFT_SHIFT, true, false, 0);
        scene.mouseMoved(60, 60);
        pump();
        assertEquals(Keys.MOD_SHIFT, recorder.lastModifiersOf(MouseEvent.Type.MOVE));

        scene.windowFocusChanged(false);
        pump();
        scene.windowFocusChanged(true);
        scene.mouseMoved(70, 70);
        pump();
        assertEquals(0, recorder.lastModifiersOf(MouseEvent.Type.MOVE),
                "focus loss must not leave a modifier stuck down");
    }

    @Test
    void sceneExposesTheHeldModifiers() {
        attach();
        assertEquals(0, scene.modifiers());
        scene.keyEvent(Keys.LEFT_CONTROL, true, false, 0);
        pump();
        assertEquals(Keys.MOD_CONTROL, scene.modifiers());
    }
}
