package limn.themeeditor;

import limn.i18n.I18n;
import limn.i18n.PropertyBundle;
import limn.i18n.StringBundle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application translates the editor from a bundle of its own, registered wherever it keeps its
 * catalogs: the editor names no path, because the one it used to, under {@code limn/i18n}, is a
 * package the toolkit owns, which a named application module cannot ship.
 */
class ThemeEditorTranslationTest {

    private StringBundle bundle;

    @AfterEach
    void restore() {
        if (bundle != null) {
            I18n.removeBundle(bundle);
        }
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    void anApplicationsOwnBundleTranslatesTheEditor() {
        bundle = PropertyBundle.of(Locale.forLanguageTag("pt"), Map.of("limn.themeEditor.name", "Nome"));
        I18n.addBundle(bundle);
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Nome", ThemeEditorStrings.NAME.get());
    }

    @Test
    void everyCaptionIsAKeyUnderTheEditorsPrefix() {
        String english = ThemeEditorStrings.NAME.get(); // loads the class, which declares the keys
        assertEquals("Name", english);
        assertTrue(I18n.declaredKeys().keySet().stream().anyMatch(k -> k.startsWith("limn.themeEditor.")),
                "declaredKeys is the starting file the editor's documentation points to");
    }
}
