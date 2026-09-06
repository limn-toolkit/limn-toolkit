package limn.accessibility;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * What each role is called, in the user's language: the phrase a screen reader speaks after a
 * node's name — "button", "check box", "slider".
 *
 * <p><b>This is the toolkit's word and not the platform's, and it has to be, because two of the
 * three platforms cannot supply one.</b> UI Automation localizes a control type itself and does it
 * well, so Windows uses this for the eight roles it has no word for at all. AppKit has
 * {@code NSAccessibilityRoleDescription()}, which looked like the answer until it was measured:
 * it localizes against the <em>calling process's</em> bundle, and a JVM launched from a jar has
 * none — so on a wholly pt-BR desktop VoiceOver said "checkbox" and "slider" in English inside its
 * own Portuguese sentences. macOS therefore uses this for every role. AT-SPI2 needs none: its role
 * names are a fixed vocabulary that the reader translates.
 *
 * <p><b>Resolved under the node's own locale, not the ambient one</b> (§1.7). A dialog in another
 * language names its own controls in that language, and the bridge answering a platform's question
 * is usually not on the user-interface thread — so this goes through
 * {@link I18n#resolve(String, String, Locale)} rather than {@link limn.i18n.I18nString#get()}.
 *
 * <p><b>The English is the fallback and never a translation.</b> A locale no scene has retained,
 * or a language nobody has written a file for, answers the English word, which is a reader saying
 * "button" in the wrong language rather than saying nothing.
 */
public final class RoleNames {

    private RoleNames() {
    }

    /**
     * The bundle, registered when this class is initialised.
     *
     * <p><b>Which must happen on the user-interface thread</b>, because registering a bundle
     * prepares its tables and {@link I18n} checks the thread for that. A bridge is the wrong first
     * toucher — some of them are called from platform threads — so {@link Accessibility} forces this
     * class's initialisation from the walk, which is user-interface-thread by construction.
     */
    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/roles"));
    }

    /** Nothing: initialising this class is the whole of what a caller wants from it. */
    static void ensureRegistered() {
    }

    private static final Map<Accessible.Role, I18nString> PHRASE =
            new EnumMap<>(Accessible.Role.class);

    /**
     * Declares one role's phrase.
     *
     * <p>An {@link I18nString} and not a bare string, because constructing one <b>declares</b> the
     * key: that is what lets {@code ShippedTranslationsTest} check every translated file against
     * the keys the code actually has, in both directions, and catch a translation for a key nobody
     * asks for as readily as a key nobody translated.
     */
    private static void say(Accessible.Role role, String english) {
        PHRASE.put(role, new I18nString(keyFor(role), english));
    }

    static {
        // The words are the ones the platforms' own controls are described with, because a reader
        // should not be able to tell a Limn button from any other button by the noun it is given.
        say(Accessible.Role.WINDOW, "window");
        say(Accessible.Role.DIALOG, "dialog");
        say(Accessible.Role.ALERT, "alert");
        say(Accessible.Role.GROUP, "group");
        say(Accessible.Role.SCROLL_PANE, "scroll area");
        say(Accessible.Role.SCROLL_BAR, "scroll bar");
        say(Accessible.Role.SPLIT_PANE, "split group");
        say(Accessible.Role.SPLITTER, "splitter");
        say(Accessible.Role.TOOL_BAR, "toolbar");
        say(Accessible.Role.MENU_BAR, "menu bar");
        say(Accessible.Role.MENU, "menu");
        say(Accessible.Role.MENU_ITEM, "menu item");
        say(Accessible.Role.CHECK_MENU_ITEM, "check menu item");
        say(Accessible.Role.RADIO_MENU_ITEM, "radio menu item");
        say(Accessible.Role.SEPARATOR, "separator");
        say(Accessible.Role.BUTTON, "button");
        say(Accessible.Role.TOGGLE_BUTTON, "toggle button");
        say(Accessible.Role.CHECK_BOX, "check box");
        say(Accessible.Role.SWITCH, "switch");
        say(Accessible.Role.RADIO_BUTTON, "radio button");
        say(Accessible.Role.RADIO_GROUP, "radio group");
        // "text" and not "label": it is what both AppKit and UI Automation call a run of static
        // text, and a reader saying "label" would be describing the widget's class rather than
        // what the user is looking at.
        say(Accessible.Role.LABEL, "text");
        say(Accessible.Role.HEADING, "heading");
        say(Accessible.Role.IMAGE, "image");
        say(Accessible.Role.VIDEO, "video");
        say(Accessible.Role.CANVAS, "drawing");
        say(Accessible.Role.CHART, "chart");
        say(Accessible.Role.CHART_SERIES, "series");
        say(Accessible.Role.PROGRESS_BAR, "progress indicator");
        say(Accessible.Role.SLIDER, "slider");
        say(Accessible.Role.SPIN_BUTTON, "stepper");
        say(Accessible.Role.TEXT_FIELD, "text field");
        say(Accessible.Role.TEXT_AREA, "text area");
        say(Accessible.Role.PASSWORD_FIELD, "secure text field");
        say(Accessible.Role.SEARCH_FIELD, "search field");
        say(Accessible.Role.COMBO_BOX, "combo box");
        say(Accessible.Role.LIST, "list");
        say(Accessible.Role.LIST_ITEM, "list item");
        say(Accessible.Role.TAB_LIST, "tab group");
        say(Accessible.Role.TAB, "tab");
        say(Accessible.Role.TAB_PANEL, "tab panel");
        say(Accessible.Role.COLOR_CHOOSER, "color well");
        // Spoken only where a bridge has nothing better, and §12.1 fails a build that publishes it.
        say(Accessible.Role.UNKNOWN, "unknown");
    }

    /**
     * @param role a role
     * @return the key its phrase lives under, {@code limn.role.<lower-case-name>}
     */
    public static String keyFor(Accessible.Role role) {
        return "limn.role." + role.name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param role   a role
     * @param locale the locale to speak in, which is the node's own and not the process's
     * @return the phrase, or the English when nothing translates it
     */
    public static String of(Accessible.Role role, Locale locale) {
        I18nString phrase = PHRASE.get(role);
        if (phrase == null) {
            throw new IllegalStateException("no phrase for " + role
                    + "; §1.12 requires one before a role is added");
        }
        // Not phrase.get(): that resolves under the locale in effect on this thread, and the answer
        // owed here is the NODE's locale, on a thread that is often the platform's.
        return I18n.resolve(phrase.key(), phrase.english(), locale);
    }

    /**
     * @param role a role
     * @return the English phrase, without consulting any bundle. For the tests that check the
     *         catalogue's own shape, and for a caller with no locale to speak of
     */
    public static String englishOf(Accessible.Role role) {
        I18nString phrase = PHRASE.get(role);
        return phrase == null ? null : phrase.english();
    }
}
