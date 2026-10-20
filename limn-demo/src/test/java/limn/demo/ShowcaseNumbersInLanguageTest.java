package limn.demo;

import limn.components.Button;
import limn.components.Checkbox;
import limn.components.Label;
import limn.components.RadioButton;
import limn.components.TextArea;
import limn.components.TextField;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.ImageDecoder;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.Scene;
import limn.scene.Widget;
import limn.testing.HeadlessBackend;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every number, amount and date a Showcase page shows is written as the language in effect writes
 * it. Three pages wrote their own: the menus page's sizes read {@code 1.3 MB} under Brazilian
 * Portuguese while the tree's read {@code 2,8 MB}, the clipboard page filled its form with
 * {@code 1,250.00} and {@code 2026-11-14}, and the charts asked the platform for whole dollars,
 * which under Arabic put the Arabic grouping sign between the Latin digits the toolkit writes
 * there ({@code 815٬800}). Each page is built in the language and every text it shows, in a label
 * or a field, is read.
 */
class ShowcaseNumbersInLanguageTest {

    private static final ImageDecoder DECODER = bytes -> {
        boolean png = bytes.length >= 24 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        int width = png ? java.nio.ByteBuffer.wrap(bytes, 16, 4).getInt() : 16;
        int height = png ? java.nio.ByteBuffer.wrap(bytes, 20, 4).getInt() : 16;
        return new Image(width, height, new byte[width * height * 4]);
    };
    private static final limn.graphics.SvgRenderer RENDERER =
            limn.testfixtures.FakeSvg.renderer((svg, px) -> new Image(1, 1, new byte[4]));

    /**
     * An English decimal point, which Portuguese writes as a comma: a point with one or two digits
     * after it that ends the number. A Portuguese grouping point has three after it, and a name
     * such as {@code INV-1080.pdf} has letters.
     */
    private static final Pattern ENGLISH_DECIMAL = Pattern.compile("(?<![\\d.])\\d+\\.\\d{1,2}(?![\\d.])");
    /** An English grouping comma before an English decimal point: {@code 1,250.00}. */
    private static final Pattern ENGLISH_GROUPED = Pattern.compile("\\d,\\d{3}\\.\\d");
    /** A day written as ISO 8601 does, which is no language's own way of writing a date. */
    private static final Pattern ISO_DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    /** The Arabic decimal or grouping sign beside a Latin digit: one system's sign, the other's digits. */
    private static final Pattern MIXED_SEPARATOR = Pattern.compile("[0-9][٫٬]|[٫٬][0-9]");

    /**
     * Names with a version in them, which every language writes as their owner does: the sample
     * clip's licence is "CC BY 3.0" in Portuguese too, and is no amount.
     */
    private static final List<String> NAMES = List.of("CC BY 3.0");

    private HeadlessUi ui;
    private HeadlessBackend backend;

    @BeforeEach
    void installRuntime() {
        ui = new HeadlessUi(() -> 0L);
        I18n.setLocale(Locale.ENGLISH);
        Theme.setCurrent(Theme.dark());
        ControlSize.setProcessDefault(ControlSize.MEDIUM);
        TextRulers.install(HeadlessWindow.RULER);
        Images.installDecoder(DECODER);
        limn.graphics.SvgIcon.installRenderer(RENDERER);
        backend = new HeadlessBackend(ui.runtime());
    }

    @AfterEach
    void uninstallRuntime() {
        I18n.setLocale(Locale.ENGLISH);
        limn.graphics.SvgIcon.uninstallRenderer(RENDERER);
        Images.uninstallDecoder(DECODER);
        TextRulers.uninstall(HeadlessWindow.RULER);
        ui.close();
    }

    @Test
    void underPortugueseNoPageWritesAnEnglishNumberOrAnIsoDate() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        List<String> wrong = new ArrayList<>();
        for (AppScene.Place place : pages()) {
            for (String text : texts(shown(place))) {
                String numbers = withoutNames(text);
                if (ENGLISH_DECIMAL.matcher(numbers).find() || ENGLISH_GROUPED.matcher(numbers).find()
                        || ISO_DATE.matcher(numbers).find()) {
                    wrong.add(place.id() + ": " + text);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void underArabicNoPageWritesAnArabicSeparatorBetweenLatinDigits() {
        I18n.setLocale(Locale.forLanguageTag("ar"));
        List<String> wrong = new ArrayList<>();
        for (AppScene.Place place : pages()) {
            for (String text : texts(shown(place))) {
                String numbers = withoutNames(text);
                if (MIXED_SEPARATOR.matcher(numbers).find() || ISO_DATE.matcher(numbers).find()) {
                    wrong.add(place.id() + ": " + text);
                }
            }
        }
        assertEquals(List.of(), wrong);
    }

    private static String withoutNames(String text) {
        for (String name : NAMES) {
            text = text.replace(name, "");
        }
        return text;
    }

    /** Every page of the Showcase but the orders, which a test of its own covers. */
    private static List<AppScene.Place> pages() {
        List<AppScene.Place> pages = new ArrayList<>();
        for (AppScene.Place top : AppScene.places(new Label(""), () -> new Label(""))) {
            pages.addAll(top.under);
        }
        return pages;
    }

    /** {@code place}'s page built now, in a window of its own, after its first frame. */
    private Widget<?> shown(AppScene.Place place) {
        Widget<?> page = place.page.get();
        HeadlessWindow window = backend.open(place.id(), 1100, 760);
        Scene scene = new Scene(page, () -> 0L);
        scene.bind(window);
        window.frame();
        window.frame();
        return page;
    }

    /** The words the page shows, in its labels and in what its fields hold. */
    private static List<String> texts(Widget<?> root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Widget<?> at, List<String> out) {
        if (at instanceof Label label) {
            out.add(label.text());
        } else if (at instanceof Button button) {
            out.add(button.text());
        } else if (at instanceof Checkbox box) {
            out.add(box.text());
        } else if (at instanceof RadioButton radio) {
            out.add(radio.text());
        } else if (at instanceof TextField field) {
            out.add(field.text());
        } else if (at instanceof TextArea area) {
            out.add(area.text());
        }
        for (Widget<?> child : at.children()) {
            collect(child, out);
        }
    }
}
