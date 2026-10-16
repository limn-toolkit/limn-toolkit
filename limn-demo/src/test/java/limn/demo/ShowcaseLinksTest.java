package limn.demo;

import limn.components.Label;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo's window against what it points at and what it is translated into: every page of
 * the gallery links to a place the site has, and every bundle says every string.
 */
class ShowcaseLinksTest {

    /** The site's sources, from the module the tests run in. */
    private static final Path SITE = Path.of("..", "site", "src");

    @Test
    void everyPageOfTheGalleryLinksToAPlaceTheSiteHas() throws IOException {
        String components = Files.readString(SITE.resolve("generated/gallery.json"));
        List<String> broken = new ArrayList<>();
        int linked = 0;
        for (AppScene.Place group : AppScene.places(new Label(""), () -> new Label(""))) {
            for (AppScene.Place place : group.under) {
                String link = place.documentation;
                if (link == null) {
                    broken.add(place.id() + ": no link");
                    continue;
                }
                linked++;
                String path = link.contains("#") ? link.substring(0, link.indexOf('#')) : link;
                String anchor = link.contains("#") ? link.substring(link.indexOf('#') + 1) : null;
                if (path.equals("components/")) {
                    if (anchor == null || !components.contains("\"id\": \"" + anchor + "\"")) {
                        broken.add(place.id() + ": no component " + anchor);
                    }
                } else if (path.startsWith("docs/")) {
                    Path guide = SITE.resolve("guides/" + path.substring(5, path.length() - 1) + ".md");
                    if (!Files.exists(guide)) {
                        broken.add(place.id() + ": no guide " + guide);
                    } else if (anchor != null && !headings(guide).contains(anchor)) {
                        broken.add(place.id() + ": no heading " + anchor + " in " + guide.getFileName());
                    }
                } else {
                    broken.add(place.id() + ": neither a guide nor a component, " + link);
                }
            }
        }
        assertEquals(List.of(), broken);
        assertTrue(linked > 20, "the gallery's pages were read");
    }

    @Test
    void everyPlaceOfTheNavigationHasAnIconThePackHas() {
        java.util.Set<String> pack = new java.util.HashSet<>(limn.icons.tabler.Tabler.names());
        List<String> wrong = new ArrayList<>();
        for (AppScene.Place group : AppScene.places(new Label(""), () -> new Label(""))) {
            List<AppScene.Place> all = new ArrayList<>(group.under);
            all.add(group);
            for (AppScene.Place place : all) {
                if (place.icon == null || !pack.contains(place.icon)) {
                    wrong.add(place.id() + ": " + place.icon);
                }
            }
        }
        assertEquals(List.of(), wrong, "a name the pack does not have draws nothing beside the page's name");
    }

    /** The anchors a guide's headings get: lower case, punctuation dropped, spaces to hyphens. */
    private static List<String> headings(Path guide) throws IOException {
        List<String> anchors = new ArrayList<>();
        for (String line : Files.readAllLines(guide)) {
            if (line.startsWith("## ") || line.startsWith("### ")) {
                String text = line.substring(line.indexOf(' ') + 1).toLowerCase(Locale.ROOT);
                anchors.add(text.replaceAll("[^a-z0-9 -]", "").trim().replace(' ', '-'));
            }
        }
        return anchors;
    }

    @Test
    void everyBundleSaysEveryStringOfTheWindow() throws Exception {
        List<String> keys = new ArrayList<>();
        List<String> plurals = new ArrayList<>();
        for (Class<?> holder : stringHolders()) {
            for (java.lang.reflect.Field field : holder.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof limn.i18n.I18nString string && string.key() != null) {
                    keys.add(string.key());
                } else if (value instanceof limn.i18n.PluralString plural) {
                    plurals.add(plural.key());
                }
            }
        }
        assertFalse(keys.isEmpty());
        List<String> missing = new ArrayList<>();
        for (Locale locale : Languages.LOCALES) {
            if (locale.equals(Locale.ENGLISH)) {
                continue;
            }
            String resource = "/i18n/showcase_" + locale.toLanguageTag() + ".properties";
            Properties bundle = new Properties();
            try (var in = ShowcaseLinksTest.class.getResourceAsStream(resource)) {
                assertTrue(in != null, resource);
                bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            List<String> wanted = new ArrayList<>(keys);
            // A count says each of the language's forms, and "other", the form every lookup
            // falls back to.
            java.util.Set<String> forms = new java.util.TreeSet<>();
            for (limn.i18n.PluralRules.Category category : limn.i18n.PluralRules.integerCategories(locale)) {
                forms.add(category.suffix());
            }
            forms.add(limn.i18n.PluralRules.Category.OTHER.suffix());
            for (String plural : plurals) {
                for (String form : forms) {
                    wanted.add(plural + "." + form);
                }
            }
            for (String key : wanted) {
                if (bundle.getProperty(key, "").isBlank()) {
                    missing.add(locale.toLanguageTag() + " " + key);
                }
            }
            for (Object key : bundle.keySet()) {
                if (!wanted.contains(key)) {
                    missing.add(locale.toLanguageTag() + " says a string nothing shows: " + key);
                }
            }
        }
        assertEquals(List.of(), missing);
    }

    /**
     * Where the Showcase keeps its strings: the frame's in {@link ShowcaseStrings}, and each page's
     * in a nested {@code Text}, found among the compiled classes so a page added later is held to
     * the bundles without being listed here.
     */
    private static List<Class<?>> stringHolders() throws Exception {
        List<Class<?>> holders = new ArrayList<>();
        holders.add(ShowcaseStrings.class);
        Path dir = Path.of(ShowcaseStrings.class.getResource("ShowcaseStrings.class").toURI()).getParent();
        try (var files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith("$Text.class") || name.endsWith("$NavText.class")) {
                    holders.add(Class.forName("limn.demo." + name.substring(0, name.length() - 6)));
                }
            }
        }
        return holders;
    }
}
