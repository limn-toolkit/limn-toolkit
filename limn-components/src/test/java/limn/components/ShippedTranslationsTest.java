package limn.components;

import limn.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped translations, checked against the catalog they claim to translate.
 *
 * <p>This is what {@link I18n#declaredKeys()} is for: the set of keys is a runtime
 * fact, so a file naming a key nobody declares (a typo, or one left behind by a
 * rename) is a test failure rather than a string that silently never appears.
 */
class ShippedTranslationsTest extends ComponentTestBase {

    /** Every locale this repo ships a translation for. */
    private static final List<String> LOCALES = List.of(
            "pt-BR", "pt", "es", "fr", "de", "it", "nl", "pl", "cs", "tr",
            "ru", "uk", "id", "vi", "ja", "ko", "zh-Hans", "zh-Hant", "hi");

    private static final Path COMPONENT_RESOURCES = Path.of("src/main/resources/limn/i18n");

    @AfterEach
    void restoreLanguage() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Loads the classes whose static fields declare the keys; see {@link I18n#declaredKeys()}. */
    private static Set<String> declaredKeys() {
        touch(ComponentStrings.SEARCH_PLACEHOLDER, ComponentStrings.VIEWPORT3D_NO_BACKEND,
                ColorPickerStrings.FORMAT_RGB, ColorPickerStrings.CHANNEL_ALPHA,
                ThemeStrings.of(Theme.builtins().get(0)));
        return I18n.declaredKeys().keySet().stream()
                .filter(key -> key.startsWith("limn."))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void touch(Object... loaded) {
        // Referencing the constants is the point: it forces class initialisation.
    }

    @Test
    void everyShippedFileTranslatesKeysThatActuallyExist() {
        Set<String> declared = declaredKeys();
        assertFalse(declared.isEmpty(), "the catalog is empty; did the probe stop loading classes?");

        List<String> orphans = new ArrayList<>();
        for (Path file : shippedFiles()) {
            for (String key : read(file).stringPropertyNames()) {
                if (!declared.contains(key)) {
                    orphans.add(file.getFileName() + " → " + key);
                }
            }
        }
        assertEquals(List.of(), orphans, "translated keys that no component declares");
    }

    @Test
    void everyLocaleOfADomainCarriesTheSameKeys() {
        Map<String, Map<String, Set<String>>> byDomain = new LinkedHashMap<>();
        for (Path file : shippedFiles()) {
            String name = file.getFileName().toString().replace(".properties", "");
            int split = name.indexOf('_');
            byDomain.computeIfAbsent(name.substring(0, split), d -> new LinkedHashMap<>())
                    .put(name.substring(split + 1), new TreeSet<>(read(file).stringPropertyNames()));
        }

        for (var domain : byDomain.entrySet()) {
            Set<String> reference = domain.getValue().values().iterator().next();
            for (var locale : domain.getValue().entrySet()) {
                assertEquals(reference, locale.getValue(),
                        domain.getKey() + " is inconsistent: " + locale.getKey()
                                + " does not carry the same keys as its siblings");
            }
        }
    }

    @Test
    void everyDeclaredLocaleHasTheComponentChromeAndTheThemeNames() {
        for (String tag : LOCALES) {
            for (String domain : List.of("components", "theme")) {
                assertTrue(Files.exists(COMPONENT_RESOURCES.resolve(domain + "_" + tag + ".properties")),
                        "missing " + domain + " translation for " + tag);
            }
        }
    }

    @Test
    void aLanguageSwitchIsVisibleInAWidgetWithNoSubscriptionAnywhere() {
        SearchField search = new SearchField();
        assertEquals("Search…", search.placeholder());

        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Pesquisar…", search.placeholder());

        I18n.setLocale(Locale.JAPANESE);
        assertEquals("検索…", search.placeholder());

        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Search…", search.placeholder());
    }

    @Test
    void aMachineReportingZhCnReadsTheSimplifiedFile() {
        SearchField search = new SearchField();
        I18n.setLocale(Locale.forLanguageTag("zh-CN"));
        assertEquals("搜索…", search.placeholder());
        I18n.setLocale(Locale.forLanguageTag("zh-TW"));
        assertEquals("搜尋…", search.placeholder());
    }

    @Test
    void portugueseVariantsDifferWhereTheyShould() {
        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Pesquisar…", new SearchField().placeholder());
        I18n.setLocale(Locale.forLanguageTag("pt-PT"));
        assertEquals("Procurar…", new SearchField().placeholder(),
                "pt-PT has no file of its own, so it must fall through to pt");
    }

    @Test
    void onlyTheDescriptivePalettesAreTranslated() {
        Theme light = Theme.builtins().stream().filter(t -> t.name.equals("Light")).findFirst()
                .orElseThrow();
        Theme dracula = Theme.builtins().stream().filter(t -> t.name.equals("Draculite"))
                .findFirst().orElseThrow();

        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Claro", light.displayName().get());
        assertEquals("Draculite", dracula.displayName().get(),
                "a palette's own name is not a word to translate");
        assertEquals("Light", light.name, "the identifier never moves");
    }

    @Test
    void frenchIsTheOneLanguageThatRenamesTheColourNotations() {
        I18n.setLocale(Locale.FRENCH);
        assertEquals("RVB", ColorPickerStrings.FORMAT_RGB.get());
        assertEquals("CMJN", ColorPickerStrings.FORMAT_CMYK.get());
        assertEquals("J", ColorPickerStrings.CHANNEL_Y.get());

        I18n.setLocale(Locale.GERMAN);
        assertEquals("RGB", ColorPickerStrings.FORMAT_RGB.get(),
                "German keeps the acronym, so it ships no file and falls back to English");
    }

    private static List<Path> shippedFiles() {
        try (Stream<Path> files = Files.list(COMPONENT_RESOURCES)) {
            return files.filter(p -> p.toString().endsWith(".properties")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing " + COMPONENT_RESOURCES.toAbsolutePath(), e);
        }
    }

    private static Properties read(Path file) {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
        return properties;
    }
}
