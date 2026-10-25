package limn.demo.site;

import limn.components.ComboBox;
import limn.components.Label;
import limn.components.Theme;
import limn.graphics.Font;
import limn.graphics.FontCatalog;
import limn.graphics.Fonts;
import limn.i18n.I18nString;
import limn.input.Keys;
import limn.scene.Scene;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    @Test
    void theFontPickerGrowsWithTheCatalogAndSwitchesOnceTheFacesAreIn() {
        try (HeadlessUi ui = new HeadlessUi()) {
            try {
                Fonts.installCatalog(() -> List.of("Roboto"));
                ThemingExample.FontPicker picker = new ThemingExample.FontPicker();
                Scene scene = new Scene(picker);
                scene.setTextRuler(TestRulers.FIXED);
                scene.layoutPass(300, 600);
                ComboBox<?> before = (ComboBox<?>) picker.children().get(0);
                assertEquals(Font.DEFAULT_FAMILY, before.selectedItem(), "the toolkit's own face, in force");

                Fonts.installCatalog(() -> List.of("Arial", "Roboto")); // the system's families land
                scene.layoutPass(300, 600);
                ComboBox<?> combo = (ComboBox<?>) picker.children().get(0);
                assertNotSame(before, combo, "a combo's items are fixed; a grown list is a new one");

                scene.requestFocus(combo);
                drive(scene).press(Keys.SPACE);
                drive(scene).press(Keys.DOWN);
                drive(scene).press(Keys.ENTER);
                assertEquals("Arial", combo.selectedItem());
                assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                        "not inside the frame that picked it: its faces are read first");

                ui.runtime().drain(); // headless, there is nothing to read, and that is answered now
                assertEquals("Arial", Fonts.defaultFamily());
                assertSame(combo, picker.children().get(0), "a switch of the font keeps the list");
            } finally {
                Fonts.setDefaultFamily(null);
                Fonts.installCatalog(FontCatalog.EMPTY);
            }
        }
    }
}
