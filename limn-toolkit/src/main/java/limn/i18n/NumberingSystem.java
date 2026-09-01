package limn.i18n;

import java.util.Locale;

/**
 * The digits a formatted number is written in: one zero code point and its nine successors,
 * which is all a decimal numbering system is. Resolved from the {@linkplain I18n#locale() UI
 * language} by {@link I18n#numberingSystem()} and applied at <em>format time</em> through
 * {@link I18n#localizeDigits}; nothing at the shaping or drawing layer knows this type exists
 * (ADR 033).
 *
 * <p>{@link #DEVA} is never chosen automatically — CLDR defaults Hindi to Latin digits — and is
 * reachable through {@link I18n#setNumberingSystem}, which is why it is here and in the glyph
 * coverage test rather than in the locale table. Further systems are a constant and a table row
 * away, behind the same coverage test.
 */
public enum NumberingSystem {

    /** ASCII {@code 0–9}: the default, and the system every parse normalizes to. */
    LATN('0'),
    /** Arabic-Indic {@code ٠–٩} (U+0660–0669): Arabic outside the Maghreb. */
    ARAB('٠'),
    /** Extended Arabic-Indic {@code ۰–۹} (U+06F0–06F9): Persian and Pashto. */
    ARABEXT('۰'),
    /** Devanagari {@code ०–९} (U+0966–096F): by explicit override only. */
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
     * CLDR's default system for {@code locale}, over the languages whose script faces the
     * toolkit vendors. Arabic defaults to {@link #ARAB} except in the Maghreb, which writes
     * Latin digits; Persian and Pashto to {@link #ARABEXT}; everything else — Hebrew and Hindi
     * included, per CLDR — to {@link #LATN}.
     */
    public static NumberingSystem forLocale(Locale locale) {
        String language = locale.getLanguage();
        if (language.equals("ar")) {
            return switch (locale.getCountry()) {
                case "MA", "DZ", "TN", "LY", "EH" -> LATN;
                default -> ARAB;
            };
        }
        if (language.equals("fa") || language.equals("ps")) {
            return ARABEXT;
        }
        return LATN;
    }
}
