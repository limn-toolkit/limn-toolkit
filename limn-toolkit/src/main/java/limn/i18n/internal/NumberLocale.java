package limn.i18n.internal;

import limn.i18n.I18n;
import limn.i18n.NumberingSystem;

import java.text.DecimalFormatSymbols;
import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * The locale a number is written in: the {@linkplain I18n#locale() language in effect}, with the
 * {@linkplain I18n#numberingSystem() numbering system in effect} named on it wherever the
 * platform would otherwise answer for other digits.
 *
 * <p>The platform's decimal and grouping separators, minus sign and exponent sign follow the
 * digits they are written with, and its idea of a language's default digits is not always the
 * toolkit's: for a bare {@code ar} it answers Arabic-Indic digits and the Arabic separators, where
 * the toolkit writes Latin digits, and for {@code uz-AF} it answers Latin digits and Uzbek's
 * decimal comma, where the toolkit writes Extended Arabic-Indic ones. Formatting under the bare
 * locale put one system's separators between the other's digits ({@code 1٫5}). Under this locale
 * Latin digits come with a point, Arabic-Indic ones with the Arabic separator, and a deployment
 * that declared a system gets the separators that go with it. The enum's constants carry the
 * Unicode names of their systems, which is what makes their lower-cased names the keyword's
 * values.
 *
 * <p>Where the platform already writes the digits the toolkit shows, the locale is returned as it
 * came, and that is a correctness rule rather than an economy: naming a system on some locales
 * makes the platform answer another locale's symbols. Serbian in Bosnia ({@code sr-BA}) writes a
 * decimal comma, and {@code sr-BA-u-nu-latn} answers a point.
 */
public final class NumberLocale {

    private NumberLocale() {
    }

    /**
     * The last answer and what it was asked for. Every number a chart axis, a table cell or a
     * caption writes asks, from whichever thread formats it, and nearly all of them ask about the
     * one locale; the answer costs a lookup of the platform's symbols and possibly a locale build,
     * which a hit skips. Immutable and published whole, so a racing reader sees one pair or the
     * other and never half of each.
     */
    private record Answer(Locale locale, NumberingSystem system, Locale numbers) {
    }

    private static volatile Answer last;

    /**
     * The locale a number formatted here is written in: the one to hand a {@code String.format},
     * a {@code NumberFormat} or a {@link DecimalFormatSymbols} that writes a number the toolkit
     * shows.
     */
    public static Locale inEffect() {
        Locale locale = I18n.locale();
        NumberingSystem system = I18n.numberingSystem();
        Answer answer = last;
        if (answer != null && answer.system == system && answer.locale.equals(locale)) {
            return answer.numbers;
        }
        Locale numbers = resolve(locale, system);
        last = new Answer(locale, system, numbers);
        return numbers;
    }

    /**
     * The separators, signs and digits of {@link #inEffect()}, in a fresh instance the caller may
     * change.
     */
    public static DecimalFormatSymbols symbols() {
        return DecimalFormatSymbols.getInstance(inEffect());
    }

    /**
     * {@code locale} with {@code system} named as its numbering system, or {@code locale} itself
     * when the platform already writes that system's digits for it, or when it is a legacy form
     * the builder cannot carry an extension on, in which case the platform's own default system
     * for it decides the separators.
     */
    private static Locale resolve(Locale locale, NumberingSystem system) {
        if (DecimalFormatSymbols.getInstance(locale).getZeroDigit() == system.digit(0)) {
            return locale;
        }
        try {
            return new Locale.Builder().setLocale(locale)
                    .setUnicodeLocaleKeyword("nu", system.name().toLowerCase(Locale.ROOT))
                    .build();
        } catch (IllformedLocaleException legacy) {
            return locale;
        }
    }
}
