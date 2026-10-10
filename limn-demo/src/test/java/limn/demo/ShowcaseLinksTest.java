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
    void everyBundleSaysEveryStringOfTheWindow() throws IOException {
        List<String> keys = new ArrayList<>();
        for (java.lang.reflect.Field field : ShowcaseStrings.class.getDeclaredFields()) {
            if (field.getType() == limn.i18n.I18nString.class) {
                try {
                    keys.add(((limn.i18n.I18nString) field.get(null)).key());
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
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
            for (String key : keys) {
                if (bundle.getProperty(key, "").isBlank()) {
                    missing.add(locale.toLanguageTag() + " " + key);
                }
            }
            for (Object key : bundle.keySet()) {
                if (!keys.contains(key)) {
                    missing.add(locale.toLanguageTag() + " says a string nothing shows: " + key);
                }
            }
        }
        assertEquals(List.of(), missing);
    }
}
