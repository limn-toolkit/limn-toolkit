package limn.components;

import limn.input.Keys;
import limn.scene.Scene;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A scene bound to a headless window has a clipboard, as {@code Scene.clipboard()} promises: the
 * window's, since a window's clipboard is never null. The headless window answered null, so a
 * text field's paste in a scene bound to one threw where a user's paste would have worked.
 */
class HeadlessClipboardTest extends ComponentTestBase {

    @Test
    void aSceneBoundToAHeadlessWindowHasAClipboard() {
        HeadlessWindow window = new HeadlessBackend(runtime).open("form", 300, 60);
        Scene scene = new Scene(new TextField());
        scene.bind(window);

        assertNotNull(window.clipboard(), "a window's clipboard is never null");
        assertNotNull(scene.clipboard(), "the scene hands over the window's");
    }

    @Test
    void copyAndPasteRoundTripInAHeadlessWindow() {
        HeadlessWindow window = new HeadlessBackend(runtime).open("form", 300, 60);
        TextField from = new TextField();
        from.setText("copied");
        Scene scene = new Scene(from);
        scene.setTextRuler(RULER);
        scene.bind(window);
        scene.layoutPass(300, 60);
        scene.requestFocus(from);

        drive(scene).press(Keys.A, Keys.MOD_CONTROL).press(Keys.C, Keys.MOD_CONTROL)
                .press(Keys.END).press(Keys.V, Keys.MOD_CONTROL);

        assertEquals("copiedcopied", from.text());
    }
}
