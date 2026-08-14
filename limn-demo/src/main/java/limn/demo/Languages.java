package limn.demo;

import java.util.List;
import java.util.Locale;

/**
 * The languages the demo can switch between: English plus every locale this repo
 * ships a translation for.
 *
 * <p>Each name is written in its own language, which is what a language picker does
 * everywhere: someone looking for their language is not reading the current one.
 *
 * <p>Hindi is in the list and will render as empty boxes until the text pipeline
 * grows shaping and a Devanagari face (ADR 006 §4). It is listed rather than hidden
 * because the translation is correct and the gap is in rendering; seeing the boxes
 * is a more useful thing for this demo to show than pretending the language is
 * unsupported.
 */
final class Languages {

    static final List<Locale> LOCALES = List.of(
            Locale.ENGLISH,
            Locale.forLanguageTag("pt-BR"),
            Locale.forLanguageTag("pt"),
            Locale.forLanguageTag("es"),
            Locale.forLanguageTag("fr"),
            Locale.forLanguageTag("de"),
            Locale.forLanguageTag("it"),
            Locale.forLanguageTag("nl"),
            Locale.forLanguageTag("pl"),
            Locale.forLanguageTag("cs"),
            Locale.forLanguageTag("tr"),
            Locale.forLanguageTag("ru"),
            Locale.forLanguageTag("uk"),
            Locale.forLanguageTag("id"),
            Locale.forLanguageTag("vi"),
            Locale.forLanguageTag("ja"),
            Locale.forLanguageTag("ko"),
            Locale.forLanguageTag("zh-Hans"),
            Locale.forLanguageTag("zh-Hant"),
            Locale.forLanguageTag("hi"));

    static final List<String> NAMES = List.of(
            "English", "Português (BR)", "Português", "Español", "Français", "Deutsch",
            "Italiano", "Nederlands", "Polski", "Čeština", "Türkçe", "Русский",
            "Українська", "Indonesia", "Tiếng Việt", "日本語", "한국어", "简体中文",
            "繁體中文", "हिन्दी");

    /** The entry a locale selects, matching on language and then on the full tag. */
    static int indexOf(Locale locale) {
        for (int i = 0; i < LOCALES.size(); i++) {
            if (LOCALES.get(i).toLanguageTag().equals(locale.toLanguageTag())) {
                return i;
            }
        }
        for (int i = 0; i < LOCALES.size(); i++) {
            if (LOCALES.get(i).getLanguage().equals(locale.getLanguage())) {
                return i;
            }
        }
        return 0;
    }

    /** The locale named by a {@code --locale} argument, or {@code null} when unknown. */
    static Locale parse(String tag) {
        Locale asked = Locale.forLanguageTag(tag);
        return asked.getLanguage().isEmpty() ? null : asked;
    }

    private Languages() {
    }
}
