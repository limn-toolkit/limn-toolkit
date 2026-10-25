package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A sentence with arguments that follows the language: {@code format} answered a {@code String} in
 * the language of the moment, which a label kept after the language changed, so every formatted
 * caption of an application stayed in the old language until something set it again.
 */
class TextWithArgumentsTest {

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final I18nString SAVED = new I18nString("test.args.saved", "Saved {0} at {1}");
    private static final I18nString REPORT = new I18nString("test.args.report", "the report");
    private static final PluralString FILES = PluralString.of("test.args.files", "{0} file", "{0} files");

    private final StringBundle bundle = PropertyBundle.of(PT_BR, Map.of(
            "test.args.saved", "{0} salvo às {1}",
            "test.args.report", "o relatório",
            "test.args.files.one", "{0} arquivo",
            "test.args.files.other", "{0} arquivos"));
    private Locale original;

    @BeforeEach
    void register() {
        original = I18n.locale();
        I18n.addBundle(bundle);
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restore() {
        I18n.removeBundle(bundle);
        I18n.setLocale(original);
    }

    @Test
    void aSentenceWithArgumentsFollowsTheLanguage() {
        I18nString text = SAVED.withArguments(REPORT, "10:42");
        assertEquals("Saved the report at 10:42", text.get());
        I18n.setLocale(PT_BR);
        assertEquals("o relatório salvo às 10:42", text.get(), "the pattern and the argument both");
        assertFalse(text.isLiteral());
    }

    @Test
    void aCountFollowsTheLanguageWithItsForm() {
        I18nString one = FILES.forCount(1);
        I18nString many = FILES.forCount(1500);
        assertEquals("1 file", one.get());
        assertEquals("1,500 files", many.get());
        I18n.setLocale(PT_BR);
        assertEquals("1 arquivo", one.get());
        assertEquals("1.500 arquivos", many.get(), "its digits too");
    }

    @Test
    void theSameSentenceMadeTwiceIsOneValue() {
        assertEquals(SAVED.withArguments("a", 1), SAVED.withArguments("a", 1));
        assertEquals(SAVED.withArguments("a", 1).hashCode(), SAVED.withArguments("a", 1).hashCode());
        assertNotEquals(SAVED.withArguments("a", 1), SAVED.withArguments("a", 2));
        assertNotEquals(SAVED, SAVED.withArguments("a", 1));
        assertEquals(FILES.forCount(3), FILES.forCount(3));
        assertNotEquals(FILES.forCount(3), FILES.forCount(4));
    }

    @Test
    void itsWordsDropTheAccessLetterInEachLanguage() {
        I18nString menu = new I18nString("test.args.menu", "&Open {0}").withArguments("x");
        assertEquals("Open x", menu.withoutAccessLetter().get());
    }
}
