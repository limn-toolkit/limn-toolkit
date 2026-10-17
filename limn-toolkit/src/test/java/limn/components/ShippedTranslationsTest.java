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
                "limn.components.table.TableStrings", "limn.scene.DragStrings",
                "limn.backend.spi.SystemMenuLabels");
        // And every other class whose source declares a string, found by reading the sources
        // rather than by a list: a domain added after this test, or a widget that declares its
        // strings in its own body, is in the catalog without anyone remembering to name it here.
        initialise(declaringClasses().toArray(String[]::new));
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
                Class<?> loaded =
                        Class.forName(name, true, ShippedTranslationsTest.class.getClassLoader());
                // A nested class is initialised on its own first use, not with its outer one.
                for (Class<?> nested : loaded.getDeclaredClasses()) {
                    initialise(nested.getName());
                }
            } catch (ClassNotFoundException e) {
                throw new AssertionError("a string domain moved or was renamed: " + name, e);
            }
        }
    }

    /** What constructs a translatable string: the two declarations a key can be born in. */
    private static final java.util.regex.Pattern DECLARES = java.util.regex.Pattern.compile(
            "new\\s+I18nString\\s*\\(|PluralString\\.of\\s*\\(");

    /**
     * The binary names of the main classes whose source constructs an {@code I18nString} or a
     * {@code PluralString}, read from the source tree. A match inside a comment only costs the
     * initialisation of a class that declares nothing.
     */
    private static List<String> declaringClasses() {
        Path sources = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            List<String> out = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("package-info.java") || name.equals("module-info.java")
                        || !DECLARES.matcher(Files.readString(file)).find()) {
                    continue;
                }
                String relative = sources.relativize(file).toString();
                out.add(relative.substring(0, relative.length() - ".java".length())
                        .replace(file.getFileSystem().getSeparator(), "."));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + sources.toAbsolutePath(), e);
        }
    }

    /**
     * The declared keys that no shipped file has to translate, because they stay as written.
     *
     * <p>Each is here for a reason a translator would give, and nothing else is: a key left out
     * of a language reads English to a user of that language, on screen and in a screen reader,
     * which is the defect {@link #everyDeclaredKeyIsTranslatedIntoEveryShippedLanguage} exists to
     * refuse. A file may still carry one of these where its language really renames it.
     */
    private static final Set<String> KEPT_AS_WRITTEN = Set.of(
            // The colour picker's notations and channel letters are symbols, read as written: every
            // shipped language but French keeps RGB, HSV and CMYK and their letters, and French
            // renames them in colorpicker_fr (RVB, TSV, CMJN). See ColorPickerStrings.
            "limn.colorPicker.format.rgb", "limn.colorPicker.format.hsv",
            "limn.colorPicker.format.cmyk",
            "limn.colorPicker.channel.r", "limn.colorPicker.channel.g", "limn.colorPicker.channel.b",
            "limn.colorPicker.channel.h", "limn.colorPicker.channel.s", "limn.colorPicker.channel.v",
            "limn.colorPicker.channel.c", "limn.colorPicker.channel.m", "limn.colorPicker.channel.y",
            "limn.colorPicker.channel.k", "limn.colorPicker.channel.alpha",
            // The built-in palettes' own names are proper names, the same in every language: only
            // Light, Dark and High Contrast describe what they are, and those are translated. See
            // ThemeStrings.
            "limn.theme.limn", "limn.theme.limn-light", "limn.theme.darkling",
            "limn.theme.draculite", "limn.theme.nordic", "limn.theme.arch-dark",
            "limn.theme.onyx-dark", "limn.theme.monoko-pro", "limn.theme.grovebox-dark",
            "limn.theme.solaris-light", "limn.theme.solaris-dark", "limn.theme.octo-light");

    /**
     * Every key the toolkit declares is in every shipped language of the file family that carries
     * it, unless it is {@linkplain #KEPT_AS_WRITTEN kept as written}.
     *
     * <p>The other tests here check the files against each other and against the catalog, and
     * none of them could see a key that <b>no</b> file carries: the media transport's six strings
     * and half the colour picker's were declared in English only, and a screen reader under
     * pt-BR, Arabic or Japanese read "Play" and "Mute" inside an interface in its own language.
     * So the catalog is taken from the sources rather than from a list kept beside them: every
     * class that constructs an {@code I18nString} or a {@code PluralString} is initialised, and
     * what it declared is what is checked, keys built by concatenation at run time included.
     *
     * <p>A counted sentence is held to any one of its forms; which forms a language owes is the
     * business of
     * {@link #everyLocaleOfADomainCarriesTheKeysItOwesAndTheFormsItsLanguageUses}.
     */
    @Test
    void everyDeclaredKeyIsTranslatedIntoEveryShippedLanguage() {
        // The catalog is process-wide, and other tests in this JVM declare keys of their own
        // ("limn.test.dialog.first") that no shipped file is meant to carry: only what the main
        // sources spell is the toolkit's.
        Set<String> spelled = keysSpelledInTheSources();
        Set<String> catalog = new TreeSet<>();
        for (String key : declaredKeys()) {
            if (spelledBy(spelled, key)) {
                catalog.add(key);
            }
        }
        Map<String, Map<String, Set<String>>> families = new LinkedHashMap<>();
        for (Path file : shippedFiles()) {
            String name = file.getFileName().toString().replace(".properties", "");
            int split = name.indexOf('_');
            families.computeIfAbsent(name.substring(0, split), f -> new LinkedHashMap<>())
                    .put(name.substring(split + 1), read(file).stringPropertyNames());
        }

        // A counted sentence is owed as its base, ending in ".", and a file carries it with any of
        // its forms: Polish and Russian count whole numbers with one, few and many, and no other.
        Set<String> owed = new TreeSet<>();
        for (String key : catalog) {
            if (!KEPT_AS_WRITTEN.contains(key)) {
                owed.add(formOf(key) == null ? key : key.substring(0, key.lastIndexOf('.') + 1));
            }
        }
        List<String> untranslated = new ArrayList<>();
        for (String key : owed) {
            List<String> carrying = new ArrayList<>();
            Set<String> missing = new TreeSet<>();
            for (var family : families.entrySet()) {
                if (family.getValue().values().stream().noneMatch(keys -> carries(keys, key))) {
                    continue;
                }
                carrying.add(family.getKey());
                for (String tag : LOCALES) {
                    if (!carries(family.getValue().getOrDefault(tag, Set.of()), key)) {
                        missing.add(tag);
                    }
                }
            }
            if (carrying.isEmpty()) {
                untranslated.add(key + " is in no shipped file");
            } else if (!missing.isEmpty()) {
                untranslated.add(key + " (" + String.join(", ", carrying) + ") is missing in "
                        + missing);
            }
        }
        assertEquals(List.of(), untranslated,
                untranslated.size() + " declared keys read English in a shipped language; "
                        + "translate them, or add a symbol that stays as written to KEPT_AS_WRITTEN "
                        + "with the reason");

        Set<String> stale = new TreeSet<>(KEPT_AS_WRITTEN);
        stale.removeAll(catalog);
        assertEquals(Set.of(), stale, "kept-as-written keys that nothing declares any more");
    }

    /** A string literal in the main sources that could be a key, or the prefix one is built on. */
    private static final java.util.regex.Pattern KEY_LITERAL =
            java.util.regex.Pattern.compile("\"(limn\\.[A-Za-z0-9._-]+)\"");

    /** Every key-like string literal the main sources spell; see {@link #spelledBy}. */
    private static Set<String> keysSpelledInTheSources() {
        Path sources = Path.of("src/main/java");
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                java.util.regex.Matcher m = KEY_LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + sources.toAbsolutePath(), e);
        }
        return out;
    }

    /**
     * Whether the main sources spell a declared key: as a literal, as the base of a counted
     * sentence it is one form of, or as a prefix it was built on at run time
     * ({@code "limn.theme." + name}, {@code "limn.role." + role}). A prefix counts only below
     * the first segment, so a stray {@code "limn."} cannot claim the whole catalog.
     */
    private static boolean spelledBy(Set<String> spelled, String key) {
        if (spelled.contains(key)) {
            return true;
        }
        if (formOf(key) != null && spelled.contains(key.substring(0, key.lastIndexOf('.')))) {
            return true;
        }
        for (String literal : spelled) {
            if (literal.endsWith(".") && literal.indexOf('.') < literal.length() - 1
                    && key.startsWith(literal)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a file's keys carry an owed key: the key itself, or a form of a counted base. */
    private static boolean carries(Set<String> keys, String owed) {
        if (!owed.endsWith(".")) {
            return keys.contains(owed);
        }
        for (String key : keys) {
            if (key.startsWith(owed) && key.indexOf('.', owed.length()) < 0 && formOf(key) != null) {
                return true;
            }
        }
        return false;
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

    /**
     * The keys of a file that are not one form of a counted sentence, nor a symbol
     * {@linkplain #KEPT_AS_WRITTEN kept as written}: those a file carries only where its language
     * renames them, which is French alone for the colour notations.
     */
    private static Set<String> plainKeys(Set<String> keys) {
        Set<String> out = new TreeSet<>();
        for (String key : keys) {
            if (formOf(key) == null && !KEPT_AS_WRITTEN.contains(key)) {
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
            for (String domain : List.of("components", "theme", "scene")) {
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
        Theme light = Theme.builtins().stream().filter(t -> t.name().equals("Light")).findFirst()
                .orElseThrow();
        Theme dracula = Theme.builtins().stream().filter(t -> t.name().equals("Draculite"))
                .findFirst().orElseThrow();

        I18n.setLocale(Locale.forLanguageTag("pt-BR"));
        assertEquals("Claro", light.displayName().get());
        assertEquals("Draculite", dracula.displayName().get(),
                "a palette's own name is not a word to translate");
        assertEquals("Light", light.name(), "the identifier never moves");
    }

    @Test
    void frenchIsTheOneLanguageThatRenamesTheColourNotations() {
        I18n.setLocale(Locale.FRENCH);
        assertEquals("RVB", ColorPickerStrings.FORMAT_RGB.get());
        assertEquals("CMJN", ColorPickerStrings.FORMAT_CMYK.get());
        assertEquals("J", ColorPickerStrings.CHANNEL_Y.get());

        I18n.setLocale(Locale.GERMAN);
        assertEquals("RGB", ColorPickerStrings.FORMAT_RGB.get(),
                "German keeps the acronym: its file translates the words, and the symbols fall "
                        + "back to English");
        assertEquals("Farbton", ColorPickerStrings.HUE.get(), "while the words are its own");
    }

    /**
     * A tab strip says "tab" with one word, the word its role says: a Vietnamese reader heard
     * "tab" for the current tab and "thẻ" for its close button and for the role, and a
     * Traditional Chinese one "分頁" for the close button beside "標籤頁" everywhere else. Held in
     * the languages whose word for a tab stays whole in a phrase; the others inflect it ("Закрыть
     * вкладку") or call the role by another word, Portuguese because pt-PT's "separador" also
     * names a separator.
     */
    @Test
    void aTabStripSaysTabWithTheWordItsRoleSays() {
        List<String> differing = new ArrayList<>();
        for (String language : List.of("de", "es", "fr", "hi", "id", "ja", "ko", "nl", "pt-BR", "tr",
                "vi", "zh-Hans", "zh-Hant")) {
            String tab = read(COMPONENT_RESOURCES.resolve("roles_" + language + ".properties"))
                    .getProperty("limn.role.tab");
            Properties strip = read(COMPONENT_RESOURCES.resolve("components_" + language + ".properties"));
            for (String key : List.of("previousTabs", "nextTabs", "allTabs", "currentTab", "closeTab")) {
                String text = strip.getProperty("limn.tabbedPane." + key);
                if (!text.toLowerCase(Locale.ROOT).contains(tab.toLowerCase(Locale.ROOT))) {
                    differing.add(language + " " + key + " \"" + text + "\" without \"" + tab + "\"");
                }
            }
        }
        assertEquals(List.of(), differing);
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
