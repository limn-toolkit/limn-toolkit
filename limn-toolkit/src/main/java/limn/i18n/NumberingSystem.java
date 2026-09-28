package limn.i18n;

import limn.i18n.internal.LanguageAliases;

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
 * <p><b>Seven systems CLDR names for a language are deliberately absent</b>, and a locale that
 * wants one gets {@link #LATN} until they are added: Bengali ({@code as}, {@code bn},
 * {@code mni}), Burmese ({@code my}), Tibetan ({@code dz}), Chakma ({@code ccp}), N'Ko
 * ({@code nqo}), Ol Chiki ({@code sat}) and Adlam (Fulah written in it, {@code ff-Adlm}). Each is
 * a constant and a table row away, and what gates them is not this class but the glyph coverage
 * test — a numbering system whose digits the vendored fonts cannot draw is worse than Latin ones.
 * {@code CldrLocaleFactsTest} names them as known gaps rather than letting them read as
 * oversights.
 *
 * <p>{@link #DEVA} <em>is</em> chosen automatically, for the languages CLDR gives it to — Marathi,
 * Nepali, Sanskrit, Bhojpuri, Haryanvi and Rajasthani — and not for Hindi, which CLDR writes in
 * Latin digits. Until 2026-09-21 it was reachable only through {@link I18n#setNumberingSystem},
 * on the belief that no language defaulted to it.
 */
public enum NumberingSystem {

    /** ASCII {@code 0–9}: the default, and the system every parse normalizes to. */
    LATN('0'),
    /** Arabic-Indic {@code ٠–٩} (U+0660–0669): Arabic in the twenty-three regions below, Najdi
     * Arabic, Central Kurdish, and Sindhi in Arabic script, which is Sindhi everywhere but
     * India. */
    ARAB('٠'),
    /** Extended Arabic-Indic {@code ۰–۹} (U+06F0–06F9): Persian, Pashto, Mazanderani, Northern
     * Luri, Kashmiri except in Devanagari, Punjabi and Uzbek in Arabic script, and Urdu in
     * India. */
    ARABEXT('۰'),
    /** Devanagari {@code ०–९} (U+0966–096F): Marathi, Nepali, Sanskrit, Bhojpuri, Haryanvi,
     * Rajasthani — and never Hindi. */
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
     * The regions where CLDR writes Arabic in Arabic-Indic digits. Everywhere else, including the
     * Maghreb, the Gulf's {@code AE} and a bare {@code ar} with no region at all, Arabic is written
     * in Latin digits. The dump names two more, codes no country uses any more, {@code NT}, the
     * Neutral Zone, and {@code YD}, South Yemen, which have no key here because the locale is read
     * through {@link LanguageAliases} first and they arrive as {@code SA} and {@code YE}.
     */
    private static final java.util.Set<String> ARABIC_INDIC_REGIONS = java.util.Set.of(
            "BH", "DJ", "EG", "ER", "IL", "IQ", "JO", "KM", "KW", "LB", "MR",
            "OM", "PS", "QA", "SA", "SD", "SO", "SS", "SY", "TD", "YE");

    /**
     * CLDR's default system for {@code locale}, over the systems this enum carries.
     *
     * <p>Every branch is a row of {@code cldr-locale-facts.txt} and none of it is reasoned from
     * the shape of a language: Sindhi writes Arabic-Indic digits at home and Latin ones in India,
     * Punjabi writes Latin digits except in Pakistan, and Urdu writes Latin digits except in
     * India, which no rule about scripts would predict. What CLDR writes in Bengali, Burmese,
     * Tibetan, Chakma, N'Ko, Ol Chiki or Adlam digits answers {@link #LATN}, since this enum does
     * not carry those systems; the class note says why.
     *
     * <p>A script the locale names decides before its region does, where CLDR holds the language
     * in that script, because a region stands for the script the language is written in there
     * and a named script says so outright: Punjabi in Pakistan is Punjabi in Arabic script, and
     * Punjabi in Gurmukhi writes Latin digits in Pakistan too. So Sindhi in Devanagari writes
     * Latin digits and Sindhi in Arabic script Arabic-Indic ones even in India, Kashmiri in
     * Devanagari writes Latin digits, Punjabi and Uzbek in Arabic script write Extended
     * Arabic-Indic digits in every region, and Uzbek in Latin or Cyrillic keeps Latin ones in
     * Afghanistan. A script CLDR does not hold the language in, Arabic in Latin script say, is
     * read as no script at all, and the language and its region decide.
     *
     * <p>A code ICU reads as another language is read as that language before anything is
     * decided, as ICU reads it before it looks anything up: {@code pes}, the ISO 639-3 code for
     * Iranian Persian, writes what {@code fa} writes, and {@code arb-EG}, Standard Arabic in
     * Egypt, what {@code ar-EG} writes. {@link Locale} replaces only {@code iw}, {@code ji} and
     * {@code in} on its own. A replacement that carries a region or a script fills in only what
     * the locale leaves out: {@code prs}, Dari, is {@code fa-AF}, and {@code prs-IR} is
     * {@code fa-IR}. The codes are every one ICU replaces, the same ones the plural rules, the
     * string bundles and the calendars read a locale through. A region named by its UN number is
     * read the same way, as the region it stands for: {@code ar-818} writes what {@code ar-EG}
     * writes, and so is a retired one: {@code ar-YD}, South Yemen, writes what {@code ar-YE}
     * writes.
     *
     * @param locale the locale to classify
     * @return the system CLDR gives it, or {@link #LATN}
     */
    public static NumberingSystem forLocale(Locale locale) {
        Locale read = LanguageAliases.resolve(locale);
        String language = read.getLanguage();
        String script = read.getScript();
        String region = read.getCountry();
        return switch (language) {
            case "ar" -> ARABIC_INDIC_REGIONS.contains(region) ? ARAB : LATN;
            case "ars", "ckb" -> ARAB;
            case "fa", "lrc", "mzn", "ps" -> ARABEXT;
            case "ks" -> script.equals("Deva") ? LATN : ARABEXT;
            case "sd" -> switch (script) {
                case "Arab" -> ARAB;
                case "Deva" -> LATN;
                default -> region.equals("IN") ? LATN : ARAB;
            };
            case "pa" -> switch (script) {
                case "Arab" -> ARABEXT;
                case "Guru" -> LATN;
                default -> region.equals("PK") ? ARABEXT : LATN;
            };
            case "ur" -> region.equals("IN") ? ARABEXT : LATN;
            case "uz" -> switch (script) {
                case "Arab" -> ARABEXT;
                case "Cyrl", "Latn" -> LATN;
                default -> region.equals("AF") ? ARABEXT : LATN;
            };
            case "bgc", "bho", "mr", "ne", "raj", "sa" -> DEVA;
            default -> LATN;
        };
    }
}
