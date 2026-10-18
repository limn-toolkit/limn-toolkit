package limn.components.table;

import limn.i18n.I18n;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The locale {@code Column.of}'s formatter is handed is the one the toolkit writes numbers in,
 * {@link I18n#numberLocale()} under the table's language: a size written with
 * {@code String.format(locale, "%.1f MB", mb)} beside a numeric column then writes its digits.
 * Handed the table's bare locale, it wrote Arabic-Indic digits under a bare {@code ar}, where the
 * toolkit writes Latin ones.
 */
class ColumnNumberLocaleTest {

    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi();
    }

    @AfterEach
    void closeRuntime() {
        ui.close();
    }

    private static final Column<Double> SIZE = Column.<Double, Double>of("Size", mb -> mb,
            (mb, locale) -> String.format(locale, "%.1f MB", mb));

    private static String size(String tag) {
        return SIZE.text(1234.5, Locale.forLanguageTag(tag));
    }

    @Test
    void theFormatterWritesTheDigitsTheToolkitWrites() {
        assertEquals("1234.5 MB", size("en"));
        assertEquals("1234.5 MB", size("ar"), "a bare Arabic writes Latin digits, as its numbers do");
        assertEquals("١٢٣٤٫٥ MB", size("ar-EG"));
        assertEquals("۱۲۳۴٫۵ MB", size("fa"));
    }

    @Test
    void theLocaleHandedOverIsTheNumberLocaleOfTheTablesLanguage() {
        AtomicReference<Locale> handed = new AtomicReference<>();
        Column<Double> column = Column.<Double, Double>of("Size", mb -> mb, (mb, locale) -> {
            handed.set(locale);
            return "";
        });
        Locale arabic = Locale.forLanguageTag("ar");
        column.text(1.0, arabic);

        Locale enclosing = I18n.pushScope(arabic);
        try {
            assertEquals(I18n.numberLocale(), handed.get());
        } finally {
            I18n.popScope(enclosing);
        }
        assertEquals("ar-u-nu-latn", handed.get().toLanguageTag(), "the digits named on the language");
    }
}
