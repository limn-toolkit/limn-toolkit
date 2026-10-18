package limn.components;

import limn.components.table.Column;
import limn.components.table.Table;
import limn.graphics.Font;
import limn.graphics.Paint;
import limn.graphics.ShapedText;
import limn.i18n.DateFormats;
import limn.i18n.NumberingSystem;
import limn.scene.Scene;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A table shaped like the Showcase's orders, written in Egyptian Arabic, writes every figure in
 * one digit system: its dates, its money, its counts and a size its application formats itself.
 * Before, the dates came out of the platform's date formatter in ASCII digits and the amounts
 * beside them in Arabic-Indic ones, and a size formatted under the locale {@code Column.of}
 * handed over wrote whichever digits the platform picked for that bare locale.
 */
class TableOneDigitSystemTest extends ComponentTestBase {

    record Order(LocalDate placed, LocalDate due, double total, int items, double size) {
    }

    @Test
    void anEgyptianArabicOrdersTableWritesOneDigitSystem() {
        Table<Order> table = new Table<>(List.of(
                Column.<Order, LocalDate>of("Placed", Order::placed, DateFormats.date(FormatStyle.SHORT))
                        .width(120),
                Column.<Order, LocalDate>of("Due", Order::due, DateFormats.date(FormatStyle.SHORT))
                        .width(120),
                Column.<Order>currency("Total", Order::total, Currency.getInstance("EGP")).width(140)
                        .footerSum(),
                Column.<Order>numeric("Items", o -> o.items()).width(80).footerSum(),
                Column.<Order, Double>of("Size", Order::size, (mb, locale) -> String.format(locale, "%.1f MB", mb))
                        .width(100)));
        table.setRows(List.of(
                new Order(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 11, 2), 1234.5, 3, 2.75),
                new Order(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 12), 89.9, 12, 0.5)));
        Scene scene = new Scene(table);
        scene.setTextRuler(RULER);
        scene.setLocale(Locale.forLanguageTag("ar-EG"));
        Ink canvas = new Ink(700, 200);
        scene.layoutPass(700, 200);
        scene.renderFrame(canvas);

        List<String> figures = new ArrayList<>();
        for (String text : canvas.texts) {
            if (hasDigit(text)) {
                figures.add(text);
            }
        }
        assertTrue(figures.size() >= 12, "every cell and footer with a figure was drawn: " + figures);
        for (String figure : figures) {
            for (int i = 0; i < figure.length(); i++) {
                int value = NumberingSystem.digitValue(figure.charAt(i));
                if (value >= 0) {
                    assertEquals(NumberingSystem.ARAB.digit(value), figure.charAt(i),
                            "'" + figure + "' writes a digit of another system: " + figures);
                }
            }
        }
    }

    private static boolean hasDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (NumberingSystem.digitValue(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Records every text drawn. */
    private static final class Ink extends FakeCanvas {
        final List<String> texts = new ArrayList<>();

        Ink(float width, float height) {
            super(width, height);
        }

        @Override
        public void drawText(String text, float x, float y, Font font, Paint paint) {
            texts.add(text);
        }

        @Override
        public void drawText(ShapedText text, float x, float y, Paint paint) {
            texts.add(text.text());
        }
    }
}
