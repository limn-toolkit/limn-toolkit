package limn.i18n;

import java.util.Locale;

/**
 * The digits a formatted number is written in: one zero code point and its nine successors,
 * which is all a decimal numbering system is. Resolved from the {@linkplain I18n#locale() UI
 * language} by {@link I18n#numberingSystem()} and applied at <em>format time</em> through
 * {@link I18n#localizeDigits}; nothing at the shaping or drawing layer knows this type exists.
 *
 * <p><b>The table below is CLDR's, checked against it.</b>
 * {@code scripts/i18n/dump-cldr-locale-facts.mjs} reads Node's {@code Intl.NumberFormat}, which
 * is CLDR through ICU, and {@code CldrLocaleFactsTest} holds {@link #forLocale} to the dump.
 * That check found the first hand-written table inverted where it mattered most: it answered
 * {@link #ARAB} for plain {@code ar} and treated the Maghreb as the exception, where CLDR makes
 * <b>Latin the Arabic default</b> and names twenty-three regions that write Arabic-Indic digits.
 * A reader in Dubai or Casablanca was shown digits no application beside ours uses.
 *
 * <p><b>Three systems CLDR names for a language are deliberately absent</b>, and a locale that
 * wants one gets {@link #LATN} until they are added: Bengali ({@code as}, {@code bn}), Burmese
 * ({@code my}) and Tibetan ({@code dz}). Each is a constant and a table row away, and what gates
 * them is not this class but the glyph coverage test — a numbering system whose digits the
 * vendored fonts cannot draw is worse than Latin ones. {@code CldrLocaleFactsTest} names them as
 * known gaps rather than letting them read as oversights.
 *
 * <p>{@link #DEVA} <em>is</em> chosen automatically, for the languages CLDR gives it to — Marathi,
 * Nepali and Sanskrit — and not for Hindi, which CLDR writes in Latin digits. Until 2026-09-21 it
 * was reachable only through {@link I18n#setNumberingSystem}, on the belief that no language
 * defaulted to it.
 */
public enum NumberingSystem {

    /** ASCII {@code 0–9}: the default, and the system every parse normalizes to. */
    LATN('0'),
    /** Arabic-Indic {@code ٠–٩} (U+0660–0669): Arabic in the twenty-three regions below, Sindhi. */
    ARAB('٠'),
    /** Extended Arabic-Indic {@code ۰–۹} (U+06F0–06F9): Persian, Pashto, Kashmiri, and Punjabi,
     * Urdu and Uzbek in one region each. */
    ARABEXT('۰'),
    /** Devanagari {@code ०–९} (U+0966–096F): Marathi, Nepali, Sanskrit — and never Hindi. */
    DEVA('०');

    private final char zero;

    NumberingSystem(char zero) {
        this.zero = zero;
    }

    /** The digit {@code value} (0–9) is written as in this system. */
    public char digit(int value) {
        if (value < 0 || value > 9) {
            throw new IllegalArgumentException("not a decimal digit: " + value);
        }
        return (char) (zero + value);
    }

    /**
     * The numeric value of {@code codepoint} in <b>any</b> system this enum knows, or {@code -1}.
     * Deliberately not restricted to one system: a parse that folded only the active system's
     * digits would reject a pasted value the moment the locale changed under it.
     */
    public static int digitValue(int codepoint) {
        for (NumberingSystem system : values()) {
            if (codepoint >= system.zero && codepoint <= system.zero + 9) {
                return codepoint - system.zero;
            }
        }
        return -1;
    }

    /**
     * The twenty-three regions where CLDR writes Arabic in Arabic-Indic digits. Everywhere else,
     * including the Maghreb, the Gulf's {@code AE} and a bare {@code ar} with no region at all,
     * Arabic is written in Latin digits. Two of these are codes no country uses any more —
     * {@code NT}, the Neutral Zone, and {@code YD}, South Yemen — and they are kept because the
     * dump carries them and a table that quietly dropped rows would not be the dump's any more.
     */
    private static final java.util.Set<String> ARABIC_INDIC_REGIONS = java.util.Set.of(
            "BH", "DJ", "EG", "ER", "IL", "IQ", "JO", "KM", "KW", "LB", "MR", "NT",
            "OM", "PS", "QA", "SA", "SD", "SO", "SS", "SY", "TD", "YD", "YE");

    /**
     * CLDR's default system for {@code locale}, over the systems this enum carries.
     *
     * <p>Every branch is a row of {@code cldr-locale-facts.txt} and none of it is reasoned from
     * the shape of a language: Sindhi writes Arabic-Indic digits at home and Latin ones in India,
     * Punjabi writes Latin digits except in Pakistan, and Urdu writes Latin digits except in
     * India, which no rule about scripts would predict. Bengali, Burmese and Tibetan default to
     * systems this enum does not carry and answer {@link #LATN}; the class note says why.
     *
     * @param locale the locale to classify
     * @return the system CLDR gives it, or {@link #LATN}
     */
    public static NumberingSystem forLocale(Locale locale) {
        String language = locale.getLanguage();
        String region = locale.getCountry();
        return switch (language) {
            case "ar" -> ARABIC_INDIC_REGIONS.contains(region) ? ARAB : LATN;
            case "fa", "ps", "ks" -> ARABEXT;
            case "sd" -> region.equals("IN") ? LATN : ARAB;
            case "pa" -> region.equals("PK") ? ARABEXT : LATN;
            case "ur" -> region.equals("IN") ? ARABEXT : LATN;
            case "uz" -> region.equals("AF") ? ARABEXT : LATN;
            case "mr", "ne", "sa" -> DEVA;
            default -> LATN;
        };
    }
}
