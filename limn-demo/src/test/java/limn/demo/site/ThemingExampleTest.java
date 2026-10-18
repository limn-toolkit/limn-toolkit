package limn.demo.site;

import limn.components.ComboBox;
import limn.components.Label;
import limn.components.Theme;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * What the theming guide says its picker does: the palette chosen is current, and a window the
 * picker was never told of clears to it.
 */
class ThemingExampleTest {

    @Test
    void choosingAPaletteMakesItCurrentAndTheSceneClearsToItsBackground() {
        Theme before = Theme.current();
        try (HeadlessUi ui = new HeadlessUi()) {
            Label content = new Label("hello");
            Scene window = new Scene(content);
            ComboBox<I18nString> picker = ThemingExample.themePicker();
            // The picker in a scene of its own, so that a key reaches it: the choice has to be
            // the user's, which is what the picker answers.
            Scene scene = new Scene(picker);
            scene.setTextRuler(TestRulers.FIXED);
            scene.layoutPass(300, 600);
            picker.setDisplayMode(limn.components.DisplayMode.IN_SCENE);
            scene.requestFocus(picker);
            int was = picker.selectedIndex();

            picker.open();
            scene.layoutPass(300, 600);
            drive(scene).press(was == 0 ? Keys.DOWN : Keys.UP);
            drive(scene).press(Keys.ENTER);

            Theme chosen = Theme.builtins().get(picker.selectedIndex());
            assertNotEquals(was, picker.selectedIndex(), "the arrow chose another palette");
            assertSame(chosen, Theme.current());
            assertEquals(chosen.background(), window.background(),
                    "the canvas behind the window's widgets is the palette's");
        } finally {
            Theme.setCurrent(before);
        }
    }
}
