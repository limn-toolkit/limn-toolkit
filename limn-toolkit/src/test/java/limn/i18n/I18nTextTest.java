package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 034 at the unit level: order and case are facts about a language, the language is the
 * UI locale until an application declares its content is in another one, and a declaration
 * is a text change.
 */
class I18nTextTest {

    private static final Locale SWEDISH = Locale.forLanguageTag("sv");
    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    private Locale original;

    @BeforeEach
    void rememberLocale() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Process-wide statics: a test that leaks its language breaks every later one. */
    @AfterEach
    void restore() {
        I18n.setTextLocale(null);
        I18n.setLocale(original);
    }

    @Test
    void collationFollowsTheUiLocale() {
        I18n.setLocale(Locale.GERMAN);
        assertTrue(I18n.collator().compare("Ärger", "Zebra") < 0,
                "German sorts ä with a, long before z");
        I18n.setLocale(SWEDISH);
        assertTrue(I18n.collator().compare("Ärger", "Zebra") > 0,
                "Swedish ä is its own letter, after z");
    }

    @Test
    void theCollatorSortsAListTheWayItsCasingAndAccentsRead() {
        I18n.setLocale(Locale.GERMAN);
        List<String> names = new ArrayList<>(List.of("Zebra", "Öl", "apfel", "Ärger"));
        names.sort(I18n.collator());
        assertEquals(List.of("apfel", "Ärger", "Öl", "Zebra"), names,
                "codepoint order would exile every umlaut past Z and 'apfel' past both");
    }

    @Test
    void theOverrideWinsOverTheUiLocaleAndNullReturnsToIt() {
        // An English interface listing Swedish names: the order is the content's language.
        I18n.setTextLocale(SWEDISH);
        assertTrue(I18n.collator().compare("Ärlig", "Zorn") > 0);
        assertEquals(Locale.ENGLISH, I18n.locale(),
                "declaring the content's language does not change the UI's");
        I18n.setTextLocale(null);
        assertTrue(I18n.collator().compare("Ärlig", "Zorn") < 0,
                "null returns to following the UI locale, which sorts ä with a");
    }

    @Test
    void caseMapsInTheTextLocale() {
        assertEquals("ISTANBUL", I18n.toUpperCase("istanbul"));
        I18n.setLocale(TURKISH);
        assertEquals("İSTANBUL", I18n.toUpperCase("istanbul"),
                "Turkish i upper-cases to dotted İ");
        assertEquals("ısparta", I18n.toLowerCase("ISPARTA"),
                "and Turkish I lower-cases to dotless ı");
    }

    /**
     * A code ICU reads as another language orders and case-maps as that language: {@code tur},
     * the ISO 639-2 code for Turkish, upper-cases i to a dotted İ, and {@code swe} puts ä after z
     * as Swedish does. The platform's collator and case mapping choose by the language subtag,
     * and Java's {@link Locale} keeps these codes as they are, so every one of them ordered and
     * case-mapped as no language at all. Asked of every {@code alias} row of
     * {@code cldr-locale-facts.txt}, under each code in turn.
     */
    @Test
    void anAliasOrdersAndCaseMapsAsItsReplacement() throws Exception {
        // Turkish and Azeri dotted and dotless i, and the Lithuanian accented I that keeps its dot.
        String probe = "istanbul ISPARTA ÌÍĨ i̇";
        List<String> wrong = new ArrayList<>();
        for (String[] alias : CldrAliasRows.read()) {
            Locale ours = Locale.forLanguageTag(alias[0]);
            Locale replacement = Locale.forLanguageTag(alias[1]);
            Locale enclosing = I18n.pushScope(ours);
            java.text.Collator ourOrder;
            String ourCase;
            try {
                ourOrder = I18n.collator();
                ourCase = I18n.toUpperCase(probe) + ' ' + I18n.toLowerCase(probe);
            } finally {
                I18n.popScope(enclosing);
            }
            enclosing = I18n.pushScope(replacement);
            try {
                if (!ourOrder.equals(I18n.collator())) {
                    wrong.add(alias[0] + " orders otherwise than " + alias[1]);
                }
                if (!ourCase.equals(I18n.toUpperCase(probe) + ' ' + I18n.toLowerCase(probe))) {
                    wrong.add(alias[0] + " case-maps otherwise than " + alias[1]);
                }
            } finally {
                I18n.popScope(enclosing);
            }
        }
        assertEquals(List.of(), wrong, "an alias orders or case-maps otherwise than its replacement");

        I18n.setTextLocale(Locale.forLanguageTag("tur"));
        assertEquals("İSTANBUL", I18n.toUpperCase("istanbul"), "tur is Turkish");
        I18n.setTextLocale(Locale.forLanguageTag("swe"));
        assertTrue(I18n.collator().compare("Ärlig", "Zorn") > 0, "swe is Swedish");
    }

    @Test
    void aDeclarationIsATextChange() {
        long before = I18n.epoch();
        I18n.setTextLocale(SWEDISH);
        assertNotEquals(before, I18n.epoch(),
                "every order built through collator() just went stale");
        long declared = I18n.epoch();
        I18n.setTextLocale(SWEDISH);
        assertEquals(declared, I18n.epoch(), "re-declaring the same language changes nothing");
    }

    @Test
    void everyCallAnswersAFreshCollator() {
        assertNotSame(I18n.collator(), I18n.collator(),
                "a Collator carries per-comparison state; a shared one across threads is "
                        + "exactly the bug this prevents");
    }
}
