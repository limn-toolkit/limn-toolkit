package limn.i18n;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which grammatical form a language uses for a count: "1 item", "2 items", and the four other
 * answers some languages give.
 *
 * <p><b>Why this exists at all.</b> A sentence with a number in it cannot be translated as one
 * string. Russian needs three forms for integers and picks between them by the last <em>two</em>
 * digits — 21 takes the same form as 1, 22 the same as 2, and 11 neither — and Arabic needs six.
 * Nothing in the JDK answers this: {@code MessageFormat} and {@code ChoiceFormat} select by
 * numeric <em>range</em>, which cannot express "ends in 1 but not 11", so a catalog cannot carry
 * the rule on its own however it is written. ICU has the data and is not an option here: this
 * toolkit has no runtime dependencies and gains none for one sentence.
 *
 * <p><b>So the rules are transcribed by hand — and the transcription is checked against CLDR
 * itself.</b> {@code scripts/i18n/dump-cldr-plurals.mjs} reads Node's {@code Intl.PluralRules},
 * which <em>is</em> the CLDR data as ICU carries it, and writes every answer for every integer
 * from 0 to 1000 plus spot checks past a hundred million;
 * {@code PluralRulesTest.theTranscriptionAgreesWithCldr} holds this class to that file, which is
 * checked in with the ICU version that produced it. Nothing in the build runs Node. Regenerate
 * the dump when a language is added, which is when a hand-written rule is most likely to be
 * wrong.
 *
 * <p>The check earned its keep on the day it was written (2026-09-18, ICU 78.3): the first
 * transcription was wrong in five languages at once. Spanish, Italian, French, Portuguese and
 * Brazilian Portuguese have a {@code many} form for an exact multiple of a million — "un millón
 * <b>de</b> elementos" — which no rule written from the first few integers would ever suggest.
 *
 * <p>A language this class does not name falls back to English's {@link Rule#ONE_OTHER}, the
 * shape of the largest family, and that fallback stays a guess by construction: an application
 * shipping a language from another family declares its own rule by carrying only the forms it
 * needs (see {@link #integerCategories}).
 *
 * <p><b>Integers only, deliberately.</b> Every count this toolkit speaks is a number of things —
 * rows, items, results — so {@link #select} takes a {@code long}. CLDR's fraction categories
 * (Czech's {@code many}, which is "2.5 položky") cannot arise, and {@link #integerCategories}
 * leaves them out rather than making a translator fill a form the language never reaches here.
 *
 * @see PluralString
 */
public final class PluralRules {

    private PluralRules() {
    }

    /**
     * CLDR's six cardinal categories. A language uses a subset; every language uses
     * {@link #OTHER} for at least something, except where an integer can never reach it
     * (Polish and Russian, whose {@code other} is a fraction's).
     */
    public enum Category {
        /** Arabic's zero. Not "no items": a form a language uses <em>for</em> the number 0. */
        ZERO,
        /** The singular, where a language has one. */
        ONE,
        /** The dual: Arabic and Hebrew. */
        TWO,
        /** The small plural: Czech's 2–4, Polish's and Russian's 2–4 by last digit. */
        FEW,
        /** The large plural: Polish's and Russian's 5–20 by last digits, Arabic's 11–99. */
        MANY,
        /** Everything else, and the only form in languages that do not inflect for number. */
        OTHER;

        /** The suffix this category takes in a catalog key: {@code …loadedAnnouncement.few}. */
        public String suffix() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The rule a language follows, named for the family rather than for one language. */
    private enum Rule {
        /** No inflection for number: Japanese, Korean, Vietnamese, Chinese, Indonesian. */
        OTHER_ONLY,
        /** One for exactly 1: English, German, Dutch, Turkish. */
        ONE_OTHER,
        /** One for 0 and 1, and nothing above: Hindi. */
        ZERO_OR_ONE_IS_ONE,
        /**
         * One for exactly 1, and a form of its own for a round million: Spanish, Italian.
         *
         * <p>The millions form is not a curiosity. Spanish says "un millón <b>de</b> elementos"
         * and Italian "un milione <b>di</b> elementi", where 999.999 takes no preposition at all,
         * so a sentence built from the plural form reads as broken Spanish at exactly the round
         * numbers a file browser or a search result is most likely to show.
         */
        ONE_OTHER_AND_MILLIONS,
        /** One for 0 and 1, and the same millions form: French, Portuguese. */
        ZERO_OR_ONE_AND_MILLIONS,
        /** One, a dual, and the rest: Hebrew. */
        ONE_TWO_OTHER,
        /** One for 1, few for 2–4, the rest other: Czech. */
        CZECH,
        /** One for exactly 1, few for 2–4 by last digit, everything else many: Polish. */
        POLISH,
        /** One and few by the last two digits, everything else many: Russian, Ukrainian. */
        EAST_SLAVIC,
        /** All six: Arabic. */
        ARABIC
    }

    /**
     * The form {@code count} takes in {@code locale}.
     *
     * @param locale the language to ask; only its language subtag is read
     * @param count  how many, as a whole number; negative counts are read by their magnitude,
     *               because a language inflects for the quantity and not for the sign
     * @return the category, never {@code null}
     */
    public static Category select(Locale locale, long count) {
        long n = Math.abs(count);
        long mod10 = n % 10;
        long mod100 = n % 100;
        return switch (ruleFor(locale)) {
            case OTHER_ONLY -> Category.OTHER;
            case ONE_OTHER -> n == 1 ? Category.ONE : Category.OTHER;
            case ZERO_OR_ONE_IS_ONE -> n <= 1 ? Category.ONE : Category.OTHER;
            case ONE_OTHER_AND_MILLIONS -> n == 1 ? Category.ONE
                    : roundMillion(n) ? Category.MANY : Category.OTHER;
            case ZERO_OR_ONE_AND_MILLIONS -> n <= 1 ? Category.ONE
                    : roundMillion(n) ? Category.MANY : Category.OTHER;
            case ONE_TWO_OTHER -> n == 1 ? Category.ONE : n == 2 ? Category.TWO : Category.OTHER;
            case CZECH -> n == 1 ? Category.ONE
                    : n >= 2 && n <= 4 ? Category.FEW : Category.OTHER;
            // Polish differs from Russian in one place and it is the place that matters: 21 is
            // "21 elementów", the many form, where Russian's 21 is "21 элемент", the one form.
            case POLISH -> n == 1 ? Category.ONE
                    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? Category.FEW
                    : Category.MANY;
            case EAST_SLAVIC -> mod10 == 1 && mod100 != 11 ? Category.ONE
                    : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? Category.FEW
                    : Category.MANY;
            case ARABIC -> n == 0 ? Category.ZERO
                    : n == 1 ? Category.ONE
                    : n == 2 ? Category.TWO
                    : mod100 >= 3 && mod100 <= 10 ? Category.FEW
                    : mod100 >= 11 && mod100 <= 99 ? Category.MANY
                    : Category.OTHER;
        };
    }

    /**
     * Every form {@code locale} can reach with a whole number, which is exactly the set of keys
     * a catalog for that language owes a {@link PluralString}.
     *
     * <p>Read by {@code ShippedTranslationsTest}, which is why it is public: a catalog with a
     * form its language never reaches is as wrong as one missing a form it does — the first
     * invites a translator to invent a distinction, the second drops a number into the wrong
     * sentence.
     *
     * @param locale the language to ask
     * @return its categories, in declaration order, never empty
     */
    public static Set<Category> integerCategories(Locale locale) {
        return switch (ruleFor(locale)) {
            case OTHER_ONLY -> EnumSet.of(Category.OTHER);
            case ONE_OTHER, ZERO_OR_ONE_IS_ONE -> EnumSet.of(Category.ONE, Category.OTHER);
            case ONE_OTHER_AND_MILLIONS, ZERO_OR_ONE_AND_MILLIONS ->
                    EnumSet.of(Category.ONE, Category.MANY, Category.OTHER);
            case ONE_TWO_OTHER -> EnumSet.of(Category.ONE, Category.TWO, Category.OTHER);
            case CZECH -> EnumSet.of(Category.ONE, Category.FEW, Category.OTHER);
            // No OTHER: with v = 0 the two rules above exhaust the integers between them, and
            // asking Polish or Russian for a fourth form would be asking for a fraction's.
            case POLISH, EAST_SLAVIC -> EnumSet.of(Category.ONE, Category.FEW, Category.MANY);
            case ARABIC -> EnumSet.allOf(Category.class);
        };
    }

    /**
     * The rule for a locale's language.
     *
     * <p>Both spellings of the two languages Java rewrote are accepted. {@code new
     * Locale("he")} answers {@code "iw"} on a JVM started with {@code
     * java.locale.useOldISOCodes=true}, and Indonesian answers {@code "in"} the same way, so a
     * table keyed on the modern tag alone would silently drop Hebrew and Indonesian into the
     * fallback on such a JVM — and the fallback is wrong for Hebrew.
     */
    /**
     * A non-zero multiple of a million exactly: CLDR's {@code i % 1000000 = 0} for the Romance
     * {@code many}, which is the form "un millón <b>de</b> elementos" takes.
     */
    private static boolean roundMillion(long n) {
        return n != 0 && n % 1_000_000 == 0;
    }

    private static Rule ruleFor(Locale locale) {
        return switch (locale.getLanguage()) {
            case "ja", "ko", "vi", "zh", "id", "in", "th", "my", "km", "lo" -> Rule.OTHER_ONLY;
            case "hi" -> Rule.ZERO_OR_ONE_IS_ONE;
            case "es", "it", "ca", "gl" -> Rule.ONE_OTHER_AND_MILLIONS;
            case "fr", "pt" -> Rule.ZERO_OR_ONE_AND_MILLIONS;
            case "he", "iw" -> Rule.ONE_TWO_OTHER;
            case "cs", "sk" -> Rule.CZECH;
            case "pl" -> Rule.POLISH;
            case "ru", "uk", "be" -> Rule.EAST_SLAVIC;
            case "ar" -> Rule.ARABIC;
            default -> Rule.ONE_OTHER;
        };
    }
}
