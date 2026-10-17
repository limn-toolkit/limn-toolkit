package limn.demo;

import limn.i18n.I18n;
import limn.testing.HeadlessUi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.FormatStyle;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Showcase's one way of writing a size, a date and a time of day, in the languages whose
 * separators and digits differ: the menus page wrote {@code 1.3 MB} under Brazilian Portuguese
 * beside a tree that wrote {@code 2,8 MB}, because each page wrote its own.
 */
class FormatsTest {

    private HeadlessUi ui;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> 0L);
    }

    @AfterEach
    void restore() {
        I18n.setLocale(Locale.ENGLISH);
        ui.close();
    }

    @Test
    void aSizeIsWrittenWithTheLanguagesSeparatorAndDigits() {
        long megabytes = 1_331 * 1024L;
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("1.3 MB", Formats.size(megabytes));
        assertEquals("84 KB", Formats.size(84 * 1024L));
        assertEquals("1 KB", Formats.size(12), "a file of a few bytes does not read as empty");
        assertEquals("1 MB", Formats.size(1024 * 1024L - 1),
                "a size that rounds to a whole megabyte is written in megabytes, never 1,024 KB");

        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("1,3 MB", Formats.size(megabytes));
        I18n.setLocale(Locale.forLanguageTag("de"));
        assertEquals("1,3 MB", Formats.size(megabytes));
        I18n.setLocale(Locale.forLanguageTag("ar"));
        assertEquals("1.3 MB", Formats.size(megabytes), "a bare Arabic writes Latin digits, with a point");
        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals("١٫٣ MB", Formats.size(megabytes));
    }

    @Test
    void aDateAndATimeAreWrittenAsTheLanguageWritesThem() {
        LocalDate due = LocalDate.of(2026, 11, 14);
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("14/11/2026", Formats.date(due));
        assertEquals("14:09", Formats.time(LocalTime.of(14, 9)));
        I18n.setLocale(Locale.forLanguageTag("de"));
        assertEquals("14.11.26", Formats.date(due));
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("11/14/26", Formats.date(due));
        assertEquals("Nov 14, 2026", Formats.date(due, FormatStyle.MEDIUM));

        I18n.setLocale(Locale.forLanguageTag("ar-EG"));
        assertEquals("١٤‏/١١‏/٢٠٢٦", Formats.date(due),
                "the platform writes a date's digits in ASCII whatever the language; the page writes "
                        + "the ones its numbers are written in");
    }
}
