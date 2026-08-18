package limn.components;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Display names for the built-in palettes, as a theme picker shows them.
 *
 * <p><b>Only the descriptions are translated.</b> Darkling, Draculite, Nordic, Arch
 * Dark, Onyx Dark, Monoko Pro, Grovebox Dark, Solaris and Octo Light are proper names,
 * as is Limn; a palette called <i>Draculite</i> is called that everywhere: the
 * shipped files translate {@code light}, {@code dark} and {@code high-contrast} and
 * leave the rest to fall back to English, which is what a professional translation
 * of this list looks like. Every name is still a key, so an application that
 * disagrees can override any of them.
 *
 * <p>{@code Theme.name} stays the identifier it always was: it is a public field
 * other code compares and stores, and turning it into display text would have made
 * a language change break equality.
 */
final class ThemeStrings {

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/theme"));
    }

    private static final Map<String, I18nString> BY_NAME = new HashMap<>();

    static {
        for (String name : new String[]{"Light", "Dark", "Limn", "Limn Light", "Darkling", "Draculite", "Nordic",
                "Arch Dark", "Onyx Dark", "Monoko Pro", "Grovebox Dark", "Solaris Light",
                "Solaris Dark", "Octo Light", "High Contrast"}) {
            BY_NAME.put(name, new I18nString("limn.theme." + key(name), name));
        }
    }

    /** {@code "Solaris Light"} → {@code "solaris-light"}. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    /**
     * The display name for a palette. A theme built by an application is not in the
     * table, so its own name stands, as a literal, since nothing could translate it.
     */
    static I18nString of(Theme theme) {
        I18nString known = BY_NAME.get(theme.name);
        return known != null ? known : I18nString.literal(theme.name);
    }

    private ThemeStrings() {
    }
}
