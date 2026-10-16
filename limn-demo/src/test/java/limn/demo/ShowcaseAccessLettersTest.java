package limn.demo;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every language gives the Showcase's menus and its customer form their access letters (ADR 070):
 * each string one mark, no two strings of one menu or one form the same letter, and the letters of
 * a page's controls apart from the menu bar's, which a control takes first: the customer form's,
 * and the orders toolbar's New order. The button heading every page marks none, since it stands
 * beside every page's controls. English is held to the same.
 */
class ShowcaseAccessLettersTest {

    /** Strings that share an Alt and a letter: a menu's items, the bar's titles, a form. */
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("bar", List.of("showcase.menu.file", "showcase.menu.edit", "showcase.menu.view",
                "showcase.menu.go", "showcase.menu.help"));
        GROUPS.put("file", List.of("showcase.menu.newOrder", "showcase.orders.refresh", "showcase.menu.quit"));
        GROUPS.put("view", List.of("showcase.menu.performance", "showcase.render.partial",
                "showcase.render.damage", "showcase.menu.fullscreen"));
        GROUPS.put("fullscreen", List.of("showcase.menu.currentResolution"));
        GROUPS.put("help", List.of("showcase.menu.documentation", "showcase.tab.about"));
        GROUPS.put("actions", List.of("showcase.menus.newFolder", "showcase.menus.upload",
                "showcase.menus.sortBy", "showcase.menus.hidden", "showcase.menus.properties"));
        GROUPS.put("sort", List.of("showcase.menus.byName", "showcase.menus.bySize", "showcase.menus.byKind"));
        GROUPS.put("fileMenu", List.of("showcase.menus.open", "showcase.menus.openWith", "showcase.menus.cut",
                "showcase.menus.copy", "showcase.menus.paste", "showcase.menus.rename", "showcase.menus.delete",
                "showcase.menus.sortBy"));
        GROUPS.put("openWith", List.of("showcase.menus.viewer", "showcase.menus.spreadsheet",
                "showcase.menus.textEditor"));
        GROUPS.put("ordersToolbar", List.of("showcase.menu.newOrder"));
        GROUPS.put("tray", List.of("showcase.tray.openWindow", "showcase.tray.newOrder",
                "showcase.tray.preferences", "showcase.tray.pauseSync", "showcase.tray.recent",
                "showcase.tray.quit"));
        GROUPS.put("customerForm", List.of("showcase.inputs.name", "showcase.inputs.email",
                "showcase.inputs.phone", "showcase.inputs.username", "showcase.inputs.coupon",
                "showcase.inputs.serial", "showcase.inputs.password", "showcase.inputs.notes",
                "showcase.inputs.save", "showcase.inputs.saveNew", "showcase.inputs.emailInvoices",
                "showcase.inputs.paperInvoices", "showcase.inputs.company", "showcase.inputs.person"));
    }

    /** The groups of a page's controls, which take Alt and a letter before the menu bar. */
    private static final List<String> CONTROLS = List.of("customerForm", "ordersToolbar");

    /** Controls on every page, beside whatever the page holds: they mark no letter at all. */
    private static final List<String> UNMARKED = List.of("showcase.head.documentation");

    /** An ampersand that marks: not one of a pair, and before a letter or a digit. */
    private static final Pattern MARK = Pattern.compile("(?<!&)&(?!&)([\\p{L}\\p{N}])");

    @Test
    void everyLanguageMarksOneUniqueLetterPerStringOfAGroup() throws Exception {
        Map<String, Map<String, String>> languages = new LinkedHashMap<>();
        languages.put("en", english());
        for (Locale locale : Languages.LOCALES) {
            if (locale.equals(Locale.ENGLISH)) {
                continue;
            }
            Properties bundle = new Properties();
            try (var in = getClass().getResourceAsStream(
                    "/i18n/showcase_" + locale.toLanguageTag() + ".properties")) {
                bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            Map<String, String> strings = new HashMap<>();
            bundle.forEach((k, v) -> strings.put((String) k, (String) v));
            languages.put(locale.toLanguageTag(), strings);
        }
        List<String> problems = new ArrayList<>();
        languages.forEach((tag, strings) -> {
            Map<String, Set<Character>> used = new HashMap<>();
            GROUPS.forEach((group, keys) -> {
                Set<Character> letters = new HashSet<>();
                for (String key : keys) {
                    String text = strings.get(key);
                    Matcher m = MARK.matcher(text == null ? "" : text);
                    List<Character> marks = new ArrayList<>();
                    while (m.find()) {
                        marks.add(Character.toUpperCase(m.group(1).charAt(0)));
                    }
                    if (marks.size() != 1) {
                        problems.add(tag + " " + key + " marks " + marks.size() + ": " + text);
                    } else if (!letters.add(marks.get(0))) {
                        problems.add(tag + " " + group + " repeats " + marks.get(0) + " in " + key + ": " + text);
                    }
                }
                used.put(group, letters);
            });
            for (String controls : CONTROLS) {
                for (char letter : used.get(controls)) {
                    if (used.get("bar").contains(letter)) {
                        problems.add(tag + " " + controls + " takes the bar's " + letter);
                    }
                }
            }
            for (String key : UNMARKED) {
                String text = strings.get(key);
                if (text == null || MARK.matcher(text).find()) {
                    problems.add(tag + " " + key + " must be there and mark no letter: " + text);
                }
            }
        });
        assertEquals(List.of(), problems);
    }

    /** The English the code declares, each key with its marked default. */
    private static Map<String, String> english() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (Class<?> holder : List.of(ShowcaseStrings.class, DialogsScene.Text.class, BasicsScene.Text.class,
                TrayScene.Text.class)) {
            for (java.lang.reflect.Field field : holder.getDeclaredFields()) {
                if (field.getType() == limn.i18n.I18nString.class) {
                    field.setAccessible(true);
                    limn.i18n.I18nString s = (limn.i18n.I18nString) field.get(null);
                    out.put(s.key(), s.english());
                }
            }
        }
        return out;
    }
}
