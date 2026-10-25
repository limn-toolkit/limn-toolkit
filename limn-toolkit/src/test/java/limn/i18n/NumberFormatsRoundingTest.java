package limn.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a number reads once it is rounded: a compact value that rounds up to a thousand of its
 * unit is written in the next one, and a negative value that rounds to zero is written without its
 * minus. Both were written from the value before it was rounded ("1000k", "-0").
 */
class NumberFormatsRoundingTest {

    private Locale original;

    @BeforeEach
    void english() {
        original = I18n.locale();
        I18n.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void restore() {
        I18n.setLocale(original);
    }

    @Test
    void aCompactValueThatRoundsUpToAThousandIsWrittenInTheNextUnit() {
        assertEquals("1M", NumberFormats.compact().apply(999_950));
        assertEquals("999.9k", NumberFormats.compact().apply(999_940));
        assertEquals("1B", NumberFormats.compact().apply(999_999_999));
        assertEquals("1T", NumberFormats.compact().apply(999_960_000_000.0));
        assertEquals("1.5T", NumberFormats.compact().apply(1.5e12));
        assertEquals("-1M", NumberFormats.compact().apply(-999_950));
        assertEquals("27.6k", NumberFormats.compact().apply(27_600));
        assertEquals("999", NumberFormats.compact().apply(999));
    }

    @Test
    void aNegativeValueThatRoundsToZeroHasNoMinus() {
        assertEquals("0", NumberFormats.number().apply(-0.004));
        assertEquals("0%", NumberFormats.percent(0).apply(-0.001));
        assertEquals("0.0", NumberFormats.decimals(1).apply(-0.01));
        assertEquals("0", NumberFormats.compact().apply(-0.0001));
        assertEquals("$0.00", NumberFormats.currency(Currency.getInstance("USD")).apply(-0.001));
        assertEquals("-0.01", NumberFormats.number().apply(-0.01), "a value that is not zero keeps it");
        assertEquals("0", NumberFormats.number().apply(-0.0));
    }

    @Test
    void underGermanTheSameHolds() {
        I18n.setLocale(Locale.GERMANY);
        assertEquals("0,0", NumberFormats.decimals(1).apply(-0.01));
        assertEquals("1M", NumberFormats.compact().apply(999_950));
    }
}
