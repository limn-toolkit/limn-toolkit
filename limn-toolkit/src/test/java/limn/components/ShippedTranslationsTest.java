package limn.components;

import limn.accessibility.Accessible;
import limn.accessibility.RoleNames;
import limn.accessibility.StateNames;
import limn.i18n.I18n;
import limn.i18n.PluralRules;
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
            "ru", "uk", "id", "vi", "ja", "ko", "zh-Hans", "zh-Hant", "hi", "ar", "he");

    private static final Path COMPONENT_RESOURCES = Path.of("src/main/resources/limn/i18n");

    @AfterEach
    void restoreLanguage() {
        I18n.setLocale(Locale.ENGLISH);
    }

    /** Loads the classes whose static fields declare the keys; see {@link I18n#declaredKeys()}. */
    private static Set<String> declaredKeys() {
        touch(ComponentStrings.SEARCH_PLACEHOLDER, ComponentStrings.VIEWPORT3D_NO_BACKEND,
                ColorPickerStrings.FORMAT_RGB, ColorPickerStrings.CHANNEL_ALPHA,
                ThemeStrings.of(Theme.builtins().get(0)),
                // The role catalogue declares its keys the same way, and a domain missing from
                // this list looks like a file full of keys nobody asks for.
                RoleNames.englishOf(Accessible.Role.BUTTON),
                StateNames.englishOf(Accessible.State.BUSY));
        // The two domains whose strings live in a package this test cannot reach, initialised by
        // name. Without it they were declared only when an earlier test in the same JVM happened to
        // build a date widget, so this class passed in the suite and failed on its own, calling
        // every dates key an orphan.
        initialise("limn.components.date.DateStrings", "limn.components.tree.TreeStrings",
                "limn.components.table.TableStrings");
        return I18n.declaredKeys().keySet().stream()
                .filter(key -> key.startsWith("limn."))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static void touch(Object... loaded) {
        // Referencing the constants is the point: it forces class initialisation.
    }

    private static void initialise(String... classNames) {
        for (String name : classNames) {
            try {
                Class.forName(name, true, ShippedTranslationsTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new AssertionError("a string domain moved or was renamed: " + name, e);
            }
        }
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

    /**
     * Every ordinary key in every file of a domain, and — for a counted sentence — exactly the
     * grammatical forms that file's own language uses.
     *
     * <p>Until 2026-09-18 this asserted one set for the whole domain, which a
     * {@link limn.i18n.PluralString} cannot satisfy and should not: how many forms a sentence
     * with a number in it needs <b>is a fact about the language</b>, one for Japanese and six for
     * Arabic. So a plural key is held to its language's own set from
     * {@link PluralRules#integerCategories}, which is a stricter rule and not a looser one: a
     * file with a form its language never reaches fails here too, because a form nobody can hear
     * is an invitation to a translator to invent a distinction.
     */
    @Test
    void everyLocaleOfADomainCarriesTheKeysItOwesAndTheFormsItsLanguageUses() {
        Map<String, Map<String, Set<String>>> byDomain = new LinkedHashMap<>();
        for (Path file : shippedFiles()) {
            String name = file.getFileName().toString().replace(".properties", "");
            int split = name.indexOf('_');
            byDomain.computeIfAbsent(name.substring(0, split), d -> new LinkedHashMap<>())
                    .put(name.substring(split + 1), new TreeSet<>(read(file).stringPropertyNames()));
        }

        for (var domain : byDomain.entrySet()) {
            var files = domain.getValue();
            Set<String> plainReference = plainKeys(files.values().iterator().next());
            Set<String> plurals = new TreeSet<>();
            for (Set<String> keys : files.values()) {
                for (String key : keys) {
                    if (formOf(key) != null) {
                        plurals.add(key.substring(0, key.lastIndexOf('.')));
                    }
                }
            }
            for (var locale : files.entrySet()) {
                assertEquals(plainReference, plainKeys(locale.getValue()),
                        domain.getKey() + " is inconsistent: " + locale.getKey()
                                + " does not carry the same keys as its siblings");
                Locale language = Locale.forLanguageTag(locale.getKey());
                for (String plural : plurals) {
                    Set<String> expected = new TreeSet<>();
                    for (PluralRules.Category category
                            : PluralRules.integerCategories(language)) {
                        expected.add(plural + "." + category.suffix());
                    }
                    Set<String> actual = new TreeSet<>();
                    for (String key : locale.getValue()) {
                        if (key.startsWith(plural + ".") && formOf(key) != null) {
                            actual.add(key);
                        }
                    }
                    assertEquals(expected, actual, locale.getKey() + " carries the wrong forms of "
                            + plural + ": a language owes exactly the forms it uses");
                }
            }
        }
    }

    /**
     * A translation carries the arguments its English carries, and quotes them safely.
     *
     * <p>Two defects this catches, both silent. An argument a translation <b>adds</b> is printed
     * as the literal {@code {1}} to a user, because nobody passes it; an argument a translation
     * <b>drops</b> leaves a sentence with a hole where the name or the number was. And a
     * {@code MessageFormat} pattern treats an ASCII apostrophe as a quote: {@code "d'éléments"}
     * with a placeholder after it silently swallows the rest of the sentence, which is why every
     * shipped value uses the typographic {@code ’} and why this refuses the ASCII one rather than
     * waiting for a language that needs it to be written by somebody in a hurry.
     *
     * <p><b>The one licensed omission is a spelled-out count.</b> Arabic's "عنصر واحد" and
     * Hebrew's "פריט אחד" say "one item" in words, so the count argument is deliberately unused
     * there — the whole point of having a {@code one} form. Only the count may be dropped, and
     * only from a plural form.
     */
    @Test
    void everyTranslationCarriesTheArgumentsOfItsEnglishAndQuotesThemSafely() {
        Map<String, String> declared = I18n.declaredKeys();
        List<String> problems = new ArrayList<>();
        for (Path file : shippedFiles()) {
            Properties translated = read(file);
            for (String key : translated.stringPropertyNames()) {
                String english = declared.get(key);
                if (english == null) {
                    continue; // the orphan test owns this case
                }
                String value = translated.getProperty(key);
                Set<Integer> want = argumentsOf(english);
                Set<Integer> got = argumentsOf(value);
                Set<Integer> added = new TreeSet<>(got);
                added.removeAll(want);
                if (!added.isEmpty()) {
                    problems.add(file.getFileName() + " " + key + " uses " + added
                            + ", which nobody passes: \"" + value + "\"");
                }
                Set<Integer> dropped = new TreeSet<>(want);
                dropped.removeAll(got);
                boolean countOnly = formOf(key) != null && dropped.equals(Set.of(max(want)));
                if (!dropped.isEmpty() && !countOnly) {
                    problems.add(file.getFileName() + " " + key + " drops " + dropped
                            + ": \"" + value + "\"");
                }
                if (!want.isEmpty()) {
                    if (value.replace("\'\'", "").indexOf('\'') >= 0) {
                        problems.add(file.getFileName() + " " + key
                                + " has an unescaped apostrophe, which MessageFormat reads as a "
                                + "quote: \"" + value + "\"");
                    }
                    try {
                        new java.text.MessageFormat(value, Locale.ROOT);
                    } catch (IllegalArgumentException malformed) {
                        problems.add(file.getFileName() + " " + key + " is not a pattern: "
                                + malformed.getMessage());
                    }
                }
            }
        }
        assertEquals(List.of(), problems, "translations that do not match their English");
    }

    /** The {@code {0}}-style argument indexes a pattern reads. */
    private static Set<Integer> argumentsOf(String pattern) {
        Set<Integer> out = new TreeSet<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\{(\\d+)[,}]").matcher(pattern);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static int max(Set<Integer> values) {
        int out = -1;
        for (int v : values) {
            out = Math.max(out, v);
        }
        return out;
    }

    /** The keys of a file that are not one form of a counted sentence. */
    private static Set<String> plainKeys(Set<String> keys) {
        Set<String> out = new TreeSet<>();
        for (String key : keys) {
            if (formOf(key) == null) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * The plural form a key ends in, or {@code null} when it ends in anything else. Read from the
     * suffix rather than from the declaring code, because this test's whole job is to check the
     * files against the code and a shared source would make it agree with itself.
     */
    private static PluralRules.Category formOf(String key) {
        int dot = key.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String suffix = key.substring(dot + 1);
        for (PluralRules.Category category : PluralRules.Category.values()) {
            if (category.suffix().equals(suffix)) {
                return category;
            }
        }
        return null;
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
