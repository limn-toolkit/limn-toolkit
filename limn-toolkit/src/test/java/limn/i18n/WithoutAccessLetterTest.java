package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A marked string quoted in a sentence reads as the words a control shows: the mark goes, an
 * {@code &&} is one ampersand, an {@code &} before anything but a letter or a digit stays, and the
 * parenthesised letter Japanese, Korean and Chinese strings carry goes whole on every system,
 * since a sentence is not a control. The reading follows the language, as the string does.
 */
class WithoutAccessLetterTest {

    private static final Locale JA = Locale.JAPANESE;

    private final List<StringBundle> registered = new ArrayList<>();
    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Process-wide statics: a test that leaks its language breaks every later one. */
    @AfterEach
    void restore() {
        registered.forEach(I18n::removeBundle);
        registered.clear();
        I18n.setLocale(original);
    }

    private static String words(String marked) {
        return I18nString.literal(marked).withoutAccessLetter().get();
    }

    @Test
    void theMarkGoesAndTheLetterStays() {
        assertEquals("Open", words("&Open"));
        assertEquals("Save as", words("Save &as"));
        assertEquals("Page 2", words("Page &2"), "a digit is marked as a letter is");
        assertEquals("Файл", words("&Файл"), "a letter of any script");
        assertEquals("Open", words("Open"), "a text with no mark is its words");
    }

    @Test
    void aDoubledAmpersandIsOneAndALoneOneBeforeAnythingElseIsText() {
        assertEquals("R&D", words("R&&D"));
        assertEquals("R&D Notes", words("R&&D &Notes"), "an escaped one, then a mark");
        assertEquals("Terms & conditions", words("Terms & conditions"));
        assertEquals("Save &", words("Save &"), "one at the end is text");
    }

    @Test
    void theParenthesisedLetterOfACjkStringGoesWholeOnEverySystem() {
        assertEquals("名前", words("名前(&N)"));
        assertEquals("会社", words("会社（&Y）"), "full-width parentheses too");
        assertEquals("ファイル", words("ファイル (&F)"), "a space before it goes with it");
    }

    @Test
    void anEllipsisAfterTheParenthesisedLetterStays() {
        assertEquals("プロパティ…", words("プロパティ(&P)…"));
        assertEquals("名前の変更...", words("名前の変更(&R)..."));
        assertEquals("(P) später", words("(&P) später"),
                "a bracketed letter with words after it is not the trailing CJK letter");
    }

    @Test
    void theWordsFollowTheLanguageAndEachTranslationsOwnMark() {
        I18nString file = new I18nString("test.withoutAccessLetter.file", "&File");
        register(PropertyBundle.of(JA, Map.of("test.withoutAccessLetter.file", "ファイル(&F)")));
        I18nString words = file.withoutAccessLetter();

        assertEquals("File", words.get());
        I18n.setLocale(JA);
        assertEquals("ファイル", words.get(), "resolved at get(), not when it was made");
        assertEquals("ファイル(&F)", file.get(), "the marked string itself is untouched");
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("File", words.get());
        assertEquals("File", words.english());
        assertEquals("&File", file.english());
    }

    @Test
    void aFormatSubstitutesIntoTheWordsAndLeavesItsArgumentsAlone() {
        I18nString chose = new I18nString("test.withoutAccessLetter.chose", "&Chose {0}");

        assertEquals("Chose R&&D", chose.withoutAccessLetter().format("R&&D"));
    }

    @Test
    void itIsAValueAndCallingItAgainReturnsTheSame() {
        I18nString marked = new I18nString("test.withoutAccessLetter.value", "R&&D");
        I18nString words = marked.withoutAccessLetter();

        assertSame(words, words.withoutAccessLetter(), "read once: R&&D is R&D, never RD");
        assertEquals("R&D", words.withoutAccessLetter().get());
        assertEquals(marked.withoutAccessLetter(), words);
        assertEquals(marked.withoutAccessLetter().hashCode(), words.hashCode());
        assertNotEquals(marked, words, "a translation may mark a letter the English does not");
        assertEquals(marked.key(), words.key());
        assertTrue(I18nString.literal("&Open").withoutAccessLetter().isLiteral());
    }

    private void register(StringBundle bundle) {
        registered.add(bundle);
        I18n.addBundle(bundle);
    }
}
