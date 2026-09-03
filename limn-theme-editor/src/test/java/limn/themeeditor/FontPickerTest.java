package limn.themeeditor;

import limn.components.ComboBox;
import limn.components.Theme;
import limn.components.TokenColumn;
import limn.components.Tokens;
import limn.graphics.Font;
import limn.graphics.FontCatalog;
import limn.graphics.Fonts;
import limn.scene.Scene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        ComboBox before = editor.fontChoice();
        assertEquals(List.of(Font.DEFAULT_FAMILY, "Roboto", "Menlo"), editor.offeredFontFamilies(),
                "a family the machine lacks is still an entry, or the palette could not show it");
        assertEquals(2, before.selectedIndex());

        Fonts.installCatalog(ENUMERATED);

        assertEquals(List.of(Font.DEFAULT_FAMILY, "Arial", "Helvetica Neue", "Menlo", "Roboto"),
                editor.offeredFontFamilies());
        ComboBox after = editor.fontChoice();
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
        ComboBox built = editor.fontChoice();
        Scene scene = new Scene(editor);
        scene.setTextRuler(RULER);
        scene.layoutPass(1000, 700);
        assertSame(built, editor.fontChoice(), "no change, no rebuild");
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

        editor.fontChoice().setSelectedIndex(editor.offeredFontFamilies().indexOf("Helvetica Neue"));

        assertEquals("Helvetica Neue", editor.theme().fontFamily);
        assertTrue(editor.isModified());
    }
}
