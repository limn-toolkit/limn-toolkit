package limn.components.chart;

import limn.i18n.I18n;
import limn.i18n.NumberingSystem;

import java.util.function.DoubleFunction;

/**
 * Ready-made number formats for axis ticks and tooltip values: what a chart puts on
 * screen when the application does not supply its own
 * {@link Chart#setValueFormat(DoubleFunction)}.
 *
 * <p>Every format here follows the {@linkplain I18n#locale() UI language}, read at the
 * moment it formats, so a locale switch reaches charts already on screen. A format is a
 * plain {@code DoubleFunction<String>}: anything else you write is equally acceptable.
 *
 * <pre>{@code
 * chart.setValueFormat(ChartFormats.compact());          // 12500 -> "12.5k"
 * chart.valueAxis().setFormat(ChartFormats.unit(" ms")); // 16 -> "16 ms"
 * }</pre>
 */
public final class ChartFormats {

    private ChartFormats() {
    }

    /**
     * Grouped, with up to two decimals and no trailing zeros: {@code 1234.5} reads
     * {@code "1,234.5"} in English and {@code "1.234,5"} in German.
     */
    public static DoubleFunction<String> number() {
        return v -> {
            if (!Double.isFinite(v)) {
                return "-";
            }
            if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                return localized(String.format(I18n.locale(), "%,d", (long) v));
            }
            String text = String.format(I18n.locale(), "%,.2f", v);
            return localized(trimZeros(text));
        };
    }

    /** Grouped with exactly {@code digits} decimals. */
    public static DoubleFunction<String> decimals(int digits) {
        int d = Math.max(0, digits);
        return v -> Double.isFinite(v)
                ? localized(String.format(I18n.locale(), "%,." + d + "f", v))
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
            if (abs >= 1e9) {
                return localized(trimZeros(String.format(I18n.locale(), "%.1f", v / 1e9))) + "B";
            }
            if (abs >= 1e6) {
                return localized(trimZeros(String.format(I18n.locale(), "%.1f", v / 1e6))) + "M";
            }
            if (abs >= 1e3) {
                return localized(trimZeros(String.format(I18n.locale(), "%.1f", v / 1e3))) + "k";
            }
            return body.apply(v);
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

    /** {@link #number()} with a fixed prefix: {@code prefix("$")}, {@code prefix("R$ ")}. */
    public static DoubleFunction<String> prefix(String text) {
        DoubleFunction<String> body = number();
        return v -> text + body.apply(v);
    }

    /**
     * Folds whatever digits the platform formatter wrote back to ASCII, then writes the digits
     * of the active {@linkplain I18n#numberingSystem() numbering system}. The fold is what makes
     * a declared system authoritative: Java's own locale data already writes Arabic-Indic digits
     * under {@code ar}, and an override must win over the locale's formatter as well as over
     * ASCII (ADR 033).
     */
    private static String localized(String text) {
        return I18n.localizeDigits(I18n.toAsciiDigits(text));
    }

    /**
     * Drops a decimal separator with nothing but zeros behind it. Locale-driven: the
     * separator is whatever the formatter just used, which is why this reads it off the
     * formatted text rather than assuming '.' — and why the zero test asks the digit's value
     * rather than comparing against ASCII {@code '0'}, since under {@code ar} the formatter
     * already wrote {@code ٠}.
     */
    private static String trimZeros(String text) {
        char separator = new java.text.DecimalFormatSymbols(I18n.locale()).getDecimalSeparator();
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
