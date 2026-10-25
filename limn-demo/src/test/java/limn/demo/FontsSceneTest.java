package limn.demo;

import limn.components.ComboBox;
import limn.graphics.Font;
import limn.graphics.FontCatalog;
import limn.graphics.Fonts;
import limn.input.Keys;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.TestRulers;
import org.junit.jupiter.api.Test;

import java.util.List;

import static limn.testing.SceneDriver.drive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The fonts page's UI-font picker switches the way an application's should: a pick is worn once
 * the backend says its faces are resident, never inside the frame that made it, and stepping
 * through the list ends on the last entry. Headless, so the backend has nothing to read and says
 * so on the next turn; the backend module proves the read itself leaves the UI thread.
 */
class FontsSceneTest {

    @Test
    void aPickIsWornOnTheTurnAfterItsFacesAreResidentAndTheLastPickWins() {
        try (HeadlessUi ui = new HeadlessUi()) {
            try {
                Scene scene = new Scene(FontsScene.build().widget());
                scene.setTextRuler(TestRulers.FIXED);
                // The listing lands as a backend's enumeration does, and the picker rebuilds.
                Fonts.installCatalog(() -> List.of("Arial", "Menlo", "Roboto"));
                scene.layoutPass(900, 1400);
                ComboBox<?> picker = firstCombo(scene.root());
                assertNotNull(picker, "the page has its font picker");
                assertEquals("Roboto", picker.selectedItem(), "the family in force is selected");

                scene.requestFocus(picker);
                choose(scene, Keys.UP); // Menlo
                assertEquals("Menlo", picker.selectedItem());
                assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                        "not worn inside the frame that picked it");

                choose(scene, Keys.UP); // Arial, before Menlo's faces were in
                ui.runtime().drain();
                assertEquals("Arial", Fonts.defaultFamily(), "the last pick wins");
                ui.runtime().drain();
                assertEquals("Arial", Fonts.defaultFamily(), "and the overtaken one never switches");
            } finally {
                Fonts.setDefaultFamily(null);
                Fonts.installCatalog(FontCatalog.EMPTY);
            }
        }
    }

    /** Opens the list, moves one entry, and commits it, as a user at the keyboard does. */
    private static void choose(Scene scene, int arrow) {
        drive(scene).press(Keys.SPACE);
        drive(scene).press(arrow);
        drive(scene).press(Keys.ENTER);
    }

    private static ComboBox<?> firstCombo(Widget<?> at) {
        if (at instanceof ComboBox<?> combo) {
            return combo;
        }
        for (Widget<?> child : at.children()) {
            ComboBox<?> found = firstCombo(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
