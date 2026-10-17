package limn.i18n;

import limn.i18n.internal.NumberLocale;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.function.DoubleFunction;

/**
 * Ready-made number formats: what a chart puts on an axis tick or a tooltip, what a table puts in
 * a numeric column and its footer, and what any caption that shows a number can use.
 *
 * <p>Every format here follows the {@linkplain I18n#locale() language in effect where it
 * formats}, read at that moment: a locale switch reaches widgets already on screen, and a widget
 * inside a subtree that {@linkplain limn.scene.Widget#setLocale declares its own locale} formats
 * in that subtree's language &mdash; separators, digits and a currency's symbol and
 * position alike &mdash; because the widget's passes hold its effective locale in scope. A format
 * is a plain {@code DoubleFunction<String>}: anything else you write is equally acceptable, and
 * reads the same scope for free by asking {@code I18n.locale()} when it formats.
 *
 * <p>The separators are always the ones that go with the digits a number is written in, which are
 * the {@linkplain I18n#numberingSystem() numbering system's} and not necessarily the ones the
 * platform would pick for the language: a bare {@code ar}, which the toolkit writes in Latin
 * digits, reads {@code 1,234.5} rather than putting the Arabic separators between Latin digits,
 * {@code ar-EG} reads {@code ١٬٢٣٤٫٥}, and a {@linkplain I18n#setNumberingSystem declared system}
 * brings its own separators along with its digits.
 *
 * <pre>{@code
 * chart.setValueFormat(NumberFormats.compact());                      // 12500 -> "12.5k"
 * chart.valueAxis().setFormat(NumberFormats.unit(" ms"));             // 16 -> "16 ms"
 * Column.numeric("Price", Item::price, NumberFormats.prefix("R$ "));  // 1234.5 -> "R$ 1.234,5"
 * Column.currency("Total", Order::total, Currency.getInstance("BRL")); // -> "R$ 1.234,50"
 * }</pre>
 *
 * <p>This class was once {@code limn.components.chart.ChartFormats}; the table gave it a second
 * caller and the name moved with it.
 */
public final class NumberFormats {

    private NumberFormats() {
    }

    /**
     * Grouped, with up to two decimals and no trailing zeros: {@code 1234.5} reads
     * {@code "1,234.5"} in English and {@code "1.234,5"} in German.
     */
    public static DoubleFunction<String> number() {
        return number(2);
    }

    /**
     * {@link #number()} with up to {@code maxDecimals} decimals instead of two: grouped, and
     * with no trailing zeros, so {@code number(3)} writes {@code 0.105} as {@code "0.105"} and
     * {@code 0.11} as {@code "0.11"}. The precision a caller wants for values that sit closer
     * together than a hundredth, which is what a chart's axis asks for when its ticks do.
     *
     * @param maxDecimals the most decimals written; a negative count writes none
     * @return the format
     */
    public static DoubleFunction<String> number(int maxDecimals) {
        String pattern = "%,." + Math.max(0, maxDecimals) + "f";
        return v -> {
            if (!Double.isFinite(v)) {
                return "-";
            }
            if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                return localized(String.format(NumberLocale.inEffect(), "%,d", (long) v));
            }
            String text = String.format(NumberLocale.inEffect(), pattern, v);
            return localized(trimZeros(text));
        };
    }

    /** Grouped with exactly {@code digits} decimals. */
    public static DoubleFunction<String> decimals(int digits) {
        int d = Math.max(0, digits);
        return v -> Double.isFinite(v)
                ? localized(String.format(NumberLocale.inEffect(), "%,." + d + "f", v))
                : "-";
    }

    /**
     * Thousands folded into a suffix: {@code 27600} reads {@code "27.6k"},
     * {@code 1_200_000} reads {@code "1.2M"}. Values below 1000 are left alone.
     */
    public static DoubleFunction<String> compact() {
        DoubleFunction<String> body = number();
        return v -> {
            if (!Double.isFinite(v)) {
                return "-";
            }
            double abs = Math.abs(v);
            Locale numbers = NumberLocale.inEffect();
            if (abs >= 1e9) {
                return localized(trimZeros(String.format(numbers, "%.1f", v / 1e9))) + "B";
            }
            if (abs >= 1e6) {
                return localized(trimZeros(String.format(numbers, "%.1f", v / 1e6))) + "M";
            }
            if (abs >= 1e3) {
                return localized(trimZeros(String.format(numbers, "%.1f", v / 1e3))) + "k";
            }
            return body.apply(v);
        };
    }

    /**
     * Scientific notation: a mantissa from 1 up to 10 with up to {@code maxDecimals} decimals and
     * no trailing zeros, then the language's exponent sign and the power of ten. {@code 1.2e25}
     * reads {@code "1.2E25"} in English, {@code "1,2E25"} in German and {@code "1,2×10^25"} in
     * Swedish, {@code 0.00042} reads {@code "4.2E-4"}, and zero reads {@code "0"}. It is what a number too large to write out
     * wants: past about 10<sup>15</sup> a double no longer holds every whole number, so the digits
     * a written-out value shows past there are noise, and there are hundreds of them by 1e300.
     *
     * @param maxDecimals the most decimals the mantissa is written with; a negative count writes
     *                    none
     * @return the format
     */
    public static DoubleFunction<String> scientific(int maxDecimals) {
        int decimals = Math.max(0, maxDecimals);
        String mantissaPattern = "%." + decimals + "f";
        return v -> {
            if (!Double.isFinite(v)) {
                return "-";
            }
            if (v == 0) {
                return localized("0");
            }
            // Rounded to its significant digits first, so a mantissa that rounds up to ten moves
            // the exponent instead: 9.99e5 at one decimal is 1E6, never 10.0E5.
            java.math.BigDecimal rounded = new java.math.BigDecimal(v)
                    .round(new java.math.MathContext(decimals + 1, java.math.RoundingMode.HALF_UP));
            int exponent = rounded.precision() - rounded.scale() - 1;
            double mantissa = rounded.movePointLeft(exponent).doubleValue();
            // The language's own exponent sign and minus for the digits it is written in, from
            // CLDR: "E" in most, "×10^" in Swedish, "اس" in Arabic-Indic digits and "E" in Latin
            // ones, and a true minus (U+2212) where the language writes one.
            Locale numbers = NumberLocale.inEffect();
            java.text.DecimalFormatSymbols symbols = java.text.DecimalFormatSymbols.getInstance(numbers);
            String power = exponent < 0
                    ? symbols.getMinusSign() + Integer.toString(-exponent)
                    : Integer.toString(exponent);
            return localized(trimZeros(String.format(numbers, mantissaPattern, mantissa))
                    + symbols.getExponentSeparator() + power);
        };
    }

    /**
     * A <em>fraction</em> as a percentage: {@code 0.42} reads {@code "42%"}. Values that
     * are already scaled to 0–100 want {@link #unit(String)} with {@code "%"} instead.
     */
    public static DoubleFunction<String> percent(int digits) {
        DoubleFunction<String> body = decimals(digits);
        return v -> body.apply(v * 100) + "%";
    }

    /** {@link #number()} with a fixed suffix: {@code unit(" ms")}, {@code unit("%")}. */
    public static DoubleFunction<String> unit(String suffix) {
        DoubleFunction<String> body = number();
        return v -> body.apply(v) + suffix;
    }

    /**
     * {@link #number()} with a fixed prefix: {@code prefix("$")}, {@code prefix("R$ ")}. The
     * prefix sits before the number in every language; money whose symbol should follow the
     * language's own rules wants {@link #currency(Currency)} instead.
     */
    public static DoubleFunction<String> prefix(String text) {
        DoubleFunction<String> body = number();
        return v -> text + body.apply(v);
    }

    /**
     * An amount of money in a named currency, written as the language in effect writes it:
     * {@code 1234.5} in Brazilian reais reads {@code "R$ 1.234,50"} under {@code pt-BR},
     * {@code "R$ 1,234.50"} under {@code en}, and {@code "1 234,50 R$"} under {@code fr}. The
     * symbol, its position, the decimals and the separators are the locale's; the digits follow
     * the active numbering system as every format here does.
     *
     * @param currency the currency the amounts are in; never {@code null}
     * @return the format
     */
    public static DoubleFunction<String> currency(Currency currency) {
        Objects.requireNonNull(currency, "currency");
        return currency(currency, currency.getDefaultFractionDigits());
    }

    /**
     * {@link #currency(Currency)} with exactly {@code decimals} decimals instead of the
     * currency's own: what a chart's axis wants, whose ticks are whole amounts, or a price list
     * kept to the tenth of a cent. {@code currency(dollars, 0)} writes {@code 815800} as
     * {@code "US$ 815.800"} under {@code pt-BR} and {@code "$815,800"} under {@code en}.
     *
     * <p>Money with other decimals than the currency's is written here rather than with the
     * platform's currency format asked for the language in effect, because that format writes
     * the separators of the language's default digits, which are not always the digits shown:
     * under a bare {@code ar}, whose numbers the toolkit writes in Latin digits, it puts the
     * Arabic grouping sign between them ({@code 815٬800}), where this writes {@code 815,800}.
     *
     * @param currency the currency the amounts are in; never {@code null}
     * @param decimals the decimals every amount is written with; a negative count writes none
     * @return the format
     */
    public static DoubleFunction<String> currency(Currency currency, int decimals) {
        Objects.requireNonNull(currency, "currency");
        int digits = Math.max(0, decimals);
        return v -> {
            if (!Double.isFinite(v)) {
                return "-";
            }
            Locale numbers = NumberLocale.inEffect();
            NumberFormat format = NumberFormat.getCurrencyInstance(numbers);
            format.setCurrency(currency);
            format.setMinimumFractionDigits(digits);
            format.setMaximumFractionDigits(digits);
            return localized(keepSymbolWhole(format.format(v), currency.getSymbol(numbers)));
        };
    }

    /**
     * Keeps a currency symbol in one piece under a right-to-left paragraph.
     *
     * <p>A symbol such as {@code R$} is a strong letter and a neutral sign, and the bidi
     * algorithm resolves the sign from its neighbours: at the end of an Arabic amount the
     * neighbour on the right is the paragraph, so {@code R$} draws as {@code $R}. CLDR's Persian
     * pattern carries the left-to-right mark that prevents it and its Arabic pattern does not, so
     * the platform's output is fixed here rather than trusted: wherever the symbol appears in a
     * text that holds right-to-left characters, an Arabic number, or a bidi mark, it is fenced by
     * left-to-right marks, which are zero-width, and a text without any of those is returned as
     * it came.
     */
    static String keepSymbolWhole(String text, String symbol) {
        if (symbol.isEmpty() || !text.contains(symbol) || !hasRightToLeftContent(text)) {
            return text;
        }
        boolean mixed = false;
        boolean letter = false;
        for (int i = 0; i < symbol.length(); i++) {
            byte d = Character.getDirectionality(symbol.charAt(i));
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                letter = true;
            } else {
                mixed = true;
            }
        }
        if (!letter || !mixed) {
            return text;   // a lone letter or a lone sign cannot be split
        }
        return text.replace(symbol, "\u200E" + symbol + "\u200E");
    }

    private static boolean hasRightToLeftContent(String text) {
        for (int i = 0; i < text.length(); i++) {
            switch (Character.getDirectionality(text.charAt(i))) {
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                     Character.DIRECTIONALITY_ARABIC_NUMBER,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * {@link #currency(Currency)} in the currency of the language in effect: reais under
     * {@code pt-BR}, euros under {@code de-DE}. A locale that names no country &mdash;
     * {@code ja}, {@code ar} &mdash; has no currency of its own, and the platform then writes a
     * generic sign; an application that knows which currency its amounts are in names it.
     *
     * @return the format
     */
    public static DoubleFunction<String> currency() {
        return v -> {
            if (!Double.isFinite(v)) {
                return "-";
            }
            return localized(NumberFormat.getCurrencyInstance(NumberLocale.inEffect()).format(v));
        };
    }

    /**
     * Folds whatever digits the platform formatter wrote back to ASCII, then writes the digits
     * of the active {@linkplain I18n#numberingSystem() numbering system}. The formatter is asked
     * for that system's digits already, with the system named on its locale wherever its own
     * default is another; the fold is what keeps a declared system authoritative where it cannot
     * be named, on a legacy locale that takes no extension, whose formatter writes the platform's
     * own digits.
     */
    private static String localized(String text) {
        return I18n.localizeDigits(I18n.toAsciiDigits(text));
    }

    /**
     * Drops a decimal separator with nothing but zeros behind it. Locale-driven: the
     * separator is whatever the formatter just used, which is why this reads it off the
     * formatted text rather than assuming '.' — and why the zero test asks the digit's value
     * rather than comparing against ASCII {@code '0'}, since under {@code ar-EG} the formatter
     * already wrote {@code ٠}.
     */
    private static String trimZeros(String text) {
        char separator = NumberLocale.symbols().getDecimalSeparator();
        int dot = text.lastIndexOf(separator);
        if (dot < 0) {
            return text;
        }
        int end = text.length();
        while (end > dot && NumberingSystem.digitValue(text.charAt(end - 1)) == 0) {
            end--;
        }
        if (end - 1 == dot) {
            end = dot;
        }
        return text.substring(0, end);
    }
}
