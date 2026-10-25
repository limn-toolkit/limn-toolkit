package limn.themeeditor;

import limn.components.ComboBox;
import limn.components.Theme;
import limn.components.TokenColumn;
import limn.components.Tokens;
import limn.graphics.Color;
import limn.graphics.Font;
import limn.graphics.FontCatalog;
import limn.graphics.FontLoader;
import limn.graphics.Fonts;
import limn.input.Keys;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static limn.testing.SceneDriver.drive;

/**
 * The font picker against a catalog that grows, which is what every real catalog does: a
 * backend offers the bundled families at once and the operating system's some frames later,
 * from a background enumeration that lands as a second {@link Fonts#installCatalog}. The picker
 * has to be built from the first answer and rebuilt from the second, and the second can arrive
 * while the editor is off screen.
 *
 * <p>The catalogs here are hand-made, so the test does not depend on what this machine has
 * installed; the backend module proves that the real enumeration produces one of these.
 */
class FontPickerTest extends EditorTestBase {

    /** What a backend knows before it has looked at the operating system. */
    private static final FontCatalog BUNDLED = () -> List.of("Roboto");
    /** What it knows once the enumeration lands: the bundled family among the machine's own. */
    private static final FontCatalog ENUMERATED =
            () -> List.of("Arial", "Helvetica Neue", "Menlo", "Roboto");

    @AfterEach
    void dropCatalog() {
        Fonts.installCatalog(FontCatalog.EMPTY);
    }

    @Test
    void thePickerOpensOnWhatTheCatalogKnowsAtConstruction() {
        Fonts.installCatalog(BUNDLED);
        ThemeEditor editor = new ThemeEditor(Theme.dark());
        assertEquals(List.of(Font.DEFAULT_FAMILY, "Roboto"), editor.offeredFontFamilies());
    }

    @Test
    void theEnumerationLandingRebuildsThePickerOnScreen() {
        Fonts.installCatalog(BUNDLED);
        ThemeEditor editor = new ThemeEditor(Theme.dark().toBuilder().fontFamily("Menlo").build());
        Scene scene = new Scene(editor);
        scene.setTextRuler(RULER);
        scene.layoutPass(1000, 700);
        ComboBox<?> before = editor.fontChoice();
        assertEquals(List.of(Font.DEFAULT_FAMILY, "Roboto", "Menlo"), editor.offeredFontFamilies(),
                "a family the machine lacks is still an entry, or the palette could not show it");
        assertEquals(2, before.selectedIndex());

        Fonts.installCatalog(ENUMERATED);

        assertEquals(List.of(Font.DEFAULT_FAMILY, "Arial", "Helvetica Neue", "Menlo", "Roboto"),
                editor.offeredFontFamilies());
        ComboBox<?> after = editor.fontChoice();
        assertNotSame(before, after, "a combo's items are fixed; a grown list is a new control");
        assertEquals(3, after.selectedIndex(), "the palette's family stays selected where it moved to");
        scene.layoutPass(1000, 700);
        assertSame(scene, after.scene(), "the new control has to be in the tree the old one left");
        assertTrue(after.width() > 0, "and laid out, or the user sees a row with no picker");
    }

    @Test
    void aCatalogThatMovedWhileOffScreenIsPickedUpOnReturn() {
        Fonts.installCatalog(BUNDLED);
        ThemeEditor editor = new ThemeEditor(Theme.dark());
        TokenColumn page = new TokenColumn(Tokens.Role.MEDIUM);
        page.add(editor);
        Scene scene = new Scene(page);
        scene.setTextRuler(RULER);
        scene.layoutPass(1000, 700);

        page.remove(editor);
        Fonts.installCatalog(ENUMERATED);
        assertEquals(List.of(Font.DEFAULT_FAMILY, "Roboto"), editor.offeredFontFamilies(),
                "off screen, the editor is not listening: that is the gap the attach has to close");

        page.add(editor);
        assertEquals(List.of(Font.DEFAULT_FAMILY, "Arial", "Helvetica Neue", "Menlo", "Roboto"),
                editor.offeredFontFamilies(),
                "a settings page reopened after the enumeration landed must offer what it found");
        scene.layoutPass(1000, 700);
        assertSame(scene, editor.fontChoice().scene());
    }

    @Test
    void anAttachWhereNothingMovedKeepsTheControlTheConstructorBuilt() {
        Fonts.installCatalog(BUNDLED);
        ThemeEditor editor = new ThemeEditor(Theme.dark());
        ComboBox<?> built = editor.fontChoice();
        Scene scene = new Scene(editor);
        scene.setTextRuler(RULER);
        scene.layoutPass(1000, 700);
        assertSame(built, editor.fontChoice(), "no change, no rebuild");
    }

    /** One key press and release, delivered the way the window delivers them. */
    private static void press(Scene scene, int key) {
        drive(scene).keyEvent(key, true, false, 0);
        drive(scene).keyEvent(key, false, false, 0);
        drive(scene).inputBatchEnded();
    }

    @Test
    void pickingAnEnumeratedFamilyWritesItToThePalette() {
        Fonts.installCatalog(BUNDLED);
        ThemeEditor editor = new ThemeEditor(Theme.dark());
        editor.setApplyLive(false);
        Scene scene = new Scene(editor);
        scene.setTextRuler(RULER);
        scene.layoutPass(1000, 700);
        Fonts.installCatalog(ENUMERATED);

        // The user's pick, through the keyboard: a setSelectedIndex from the test would be a
        // caller's write, which the editor's handler no longer answers.
        ComboBox<?> combo = editor.fontChoice();
        int target = editor.offeredFontFamilies().indexOf("Helvetica Neue");
        scene.requestFocus(combo);
        press(scene, Keys.SPACE);
        for (int i = combo.selectedIndex(); i < target; i++) {
            press(scene, Keys.DOWN);
        }
        press(scene, Keys.ENTER);

        assertEquals(target, combo.selectedIndex());
        assertEquals("Helvetica Neue", editor.theme().fontFamily());
        assertTrue(editor.isModified());
    }

    /** A backend's loader that records each family asked of it and lands the reads when told. */
    private static final class HeldLoader implements FontLoader {

        final List<String> asked = new ArrayList<>();
        private final List<Runnable> waiting = new ArrayList<>();

        @Override
        public String load(Path file) {
            throw new UnsupportedOperationException("not a file test");
        }

        @Override
        public void preload(String family, Runnable whenResident) {
            asked.add(family);
            waiting.add(whenResident);
        }

        /** Every read out so far has landed: what the backend does on the UI thread. */
        void land() {
            List<Runnable> answers = List.copyOf(waiting);
            waiting.clear();
            answers.forEach(Runnable::run);
        }
    }

    @Test
    void aPickedFamilyIsWornOnceItsFacesAreReadAndAColourDragAsksForNothingMore() {
        HeldLoader loader = new HeldLoader();
        Fonts.installLoader(loader);
        try {
            Fonts.installCatalog(ENUMERATED);
            ThemeEditor editor = new ThemeEditor(Theme.dark()); // applies live, the default
            Scene scene = new Scene(editor);
            scene.setTextRuler(RULER);
            scene.layoutPass(1000, 700);

            ComboBox<?> combo = editor.fontChoice();
            int target = editor.offeredFontFamilies().indexOf("Menlo");
            scene.requestFocus(combo);
            press(scene, Keys.SPACE);
            for (int i = combo.selectedIndex(); i < target; i++) {
                press(scene, Keys.DOWN);
            }
            press(scene, Keys.ENTER);

            assertEquals("Menlo", editor.theme().fontFamily());
            assertEquals(List.of("Menlo"), loader.asked, "the pick asks for the faces");
            assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily(),
                    "and is not worn before they are read: switching now would read them in a frame");

            // Two frames of a colour drag while the read is out: each pushes the palette.
            editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5));
            editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C6));
            assertEquals(List.of("Menlo"), loader.asked, "a family on its way is not asked for again");
            assertEquals(Font.DEFAULT_FAMILY, Fonts.defaultFamily());

            loader.land();
            assertEquals("Menlo", Fonts.defaultFamily(), "worn from the frame after the read");
            editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C7));
            assertEquals(List.of("Menlo"), loader.asked,
                    "a palette whose family did not move asks for nothing");
        } finally {
            Fonts.uninstallLoader(loader);
            Fonts.setDefaultFamily(null);
        }
    }

    @Test
    void anEditorKeptPastItsBackendAsksTheNextOneForTheFamilyItWasWaitingFor() {
        HeldLoader first = new HeldLoader();
        Fonts.installLoader(first);
        ThemeEditor editor = new ThemeEditor(Theme.dark()); // applies live, the default
        try {
            editor.setTheme(Theme.dark().toBuilder().fontFamily("Menlo").build());
            assertEquals(List.of("Menlo"), first.asked);
        } finally {
            Fonts.uninstallLoader(first); // the backend shuts down with the read still out
        }
        replaceRuntime(); // and a later one starts in the same process

        HeldLoader second = new HeldLoader();
        Fonts.installLoader(second);
        try {
            editor.setToken(Theme.Token.PRIMARY, Color.rgb(0x4FD1C5)); // the palette, Menlo in it
            assertEquals(List.of("Menlo"), second.asked,
                    "the answer from the backend that is gone never comes: this one is asked");
            second.land();
            assertEquals("Menlo", Fonts.defaultFamily());
        } finally {
            Fonts.uninstallLoader(second);
            Fonts.setDefaultFamily(null);
        }
    }
}
