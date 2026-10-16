package limn.demo;

import limn.components.ComboBox;
import limn.components.Theme;
import limn.graphics.Image;
import limn.graphics.Images;
import limn.graphics.TextRulers;
import limn.i18n.I18n;
import limn.scene.ControlSize;
import limn.scene.LayoutDirection;
import limn.scene.Widget;
import limn.testing.HeadlessUi;
import limn.testing.HeadlessWindow;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Words the Showcase showed in English whatever the language: the band's direction and size
 * choices, which listed the enums' names ({@code MEDIUM}, {@code RIGHT_TO_LEFT}), the navigation's
 * entry for the backdrop panel, which no bundle translated, and the save dialog's suggested file
 * name, which six left in English.
 */
class ShowcaseUntranslatedTest {

    @Test
    void theBandsChoicesAreWordsOfTheLanguageShown() {
        assertEquals(LayoutDirection.values().length, ShowcaseStrings.DIRECTIONS.size(),
                "a direction for each, in the enum's order");
        assertEquals(ControlSize.values().length, ShowcaseStrings.SIZES.size(),
                "a size for each, in the enum's order");
        try (HeadlessUi ui = new HeadlessUi(() -> 0L)) {
            I18n.setLocale(Locale.ENGLISH);
            Theme.setCurrent(Theme.dark());
            ControlSize.setProcessDefault(ControlSize.MEDIUM);
            LayoutDirection.setProcessDefault(LayoutDirection.LTR);
            TextRulers.install(HeadlessWindow.RULER);
            limn.graphics.ImageDecoder blank = bytes -> new Image(16, 16, new byte[16 * 16 * 4]);
            Images.installDecoder(blank);
            try {
                Widget<?> root = AppScene.create().scene().root();
                I18n.setLocale(Locale.forLanguageTag("pt-BR"));
                List<String> shown = new ArrayList<>();
                combos(root, shown);
                assertTrue(shown.contains("Da esquerda para a direita"), shown.toString());
                assertTrue(shown.contains("Médio"), shown.toString());
                List<String> enumNames = new ArrayList<>();
                Arrays.stream(LayoutDirection.values()).forEach(each -> enumNames.add(each.name()));
                Arrays.stream(ControlSize.values()).forEach(each -> enumNames.add(each.name()));
                shown.retainAll(enumNames);
                assertEquals(List.of(), shown, "a combo box shows an enum's name");
            } finally {
                I18n.setLocale(Locale.ENGLISH);
                Images.uninstallDecoder(blank);
                TextRulers.uninstall(HeadlessWindow.RULER);
            }
        }
    }

    @Test
    void everyLanguageNamesTheBackdropPanelAndTheSuggestedFile() throws Exception {
        List<String> english = new ArrayList<>();
        for (Locale locale : Languages.LOCALES) {
            if (locale.equals(Locale.ENGLISH)) {
                continue;
            }
            Properties bundle = new Properties();
            String resource = "/i18n/showcase_" + locale.toLanguageTag() + ".properties";
            try (var in = ShowcaseUntranslatedTest.class.getResourceAsStream(resource)) {
                bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (limn.i18n.I18nString string : List.of(ShowcaseStrings.NAV_GLASS, FilesScene.Text.SUGGESTED_NAME)) {
                if (string.english().equals(bundle.getProperty(string.key()))) {
                    english.add(locale.toLanguageTag() + " " + string.key());
                }
            }
        }
        assertEquals(List.of(), english);
    }

    /**
     * Each customer card on the text page captions its note in the customer's language, through
     * the Showcase's string, and the Showcase ships no Thai: the Thai card read the English
     * "Notes" in every language and logged the key as untranslated each time it was drawn.
     */
    @Test
    void everyCustomerCardCaptionsItsNoteInItsOwnLanguage() {
        List<String> unresolved = new ArrayList<>();
        limn.concurrent.Subscription listening = I18n.observeUnresolvedKeys(
                miss -> unresolved.add(miss.locale().toLanguageTag() + " " + miss.key()));
        try (HeadlessUi ui = new HeadlessUi(() -> 0L)) {
            I18n.setLocale(Locale.ENGLISH);
            Theme.setCurrent(Theme.dark());
            TextRulers.install(HeadlessWindow.RULER);
            try {
                Widget<?> page = BidiScene.tabContent();
                limn.testing.HeadlessWindow window =
                        new limn.testing.HeadlessBackend(ui.runtime()).open("text", 1100, 2400);
                new limn.scene.Scene(page, () -> 0L).bind(window);
                window.frame();
                List<String> captions = new ArrayList<>();
                labels(page, captions);
                assertTrue(captions.contains("หมายเหตุ"), "the Thai card's caption");
                assertEquals(List.of(), unresolved);
            } finally {
                TextRulers.uninstall(HeadlessWindow.RULER);
            }
        } finally {
            listening.cancel();
        }
    }

    private static void labels(Widget<?> at, List<String> out) {
        if (at instanceof limn.components.Label label) {
            out.add(label.text());
        }
        for (Widget<?> child : at.children()) {
            labels(child, out);
        }
    }

    private static void combos(Widget<?> at, List<String> out) {
        if (at instanceof ComboBox<?> combo) {
            out.add(combo.text());
        }
        for (Widget<?> child : at.children()) {
            combos(child, out);
        }
    }
}
