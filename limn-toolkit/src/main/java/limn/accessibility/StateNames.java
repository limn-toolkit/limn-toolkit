package limn.accessibility;

import limn.i18n.I18n;
import limn.i18n.I18nString;
import limn.i18n.PropertyBundle;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a state is called, in the user's language, for the one platform field that carries a state
 * as words rather than as a bit.
 *
 * <p><b>That field is UI Automation's {@code ItemStatus}, and today the only state spoken through
 * it is {@link Accessible.State#BUSY}.</b> AT-SPI2 has a busy bit, and a reader there translates
 * it itself. AppKit has a boolean attribute. UI Automation has neither: an item that is working
 * says so in a status string the client reads out, so the toolkit has to supply the word, just as
 * {@link RoleNames} supplies the eight role phrases UI Automation has no word for. A state with no
 * phrase here is one no platform needs in words, and answers {@code null}.
 *
 * <p><b>Resolved under the node's own locale, not the ambient one</b>, and registered from the
 * walk for {@link RoleNames}'s reason: a bridge answers from the platform's threads, and
 * registering a bundle has to happen on the user-interface thread.
 */
public final class StateNames {

    private StateNames() {
    }

    static {
        I18n.addBundle(PropertyBundle.family("/limn/i18n/states"));
    }

    /** Nothing: initialising this class is the whole of what a caller wants from it. */
    static void ensureRegistered() {
    }

    private static final Map<Accessible.State, I18nString> PHRASE =
            new EnumMap<>(Accessible.State.class);

    /** Declares one state's phrase; an {@link I18nString} so the key is declared, as a role's is. */
    private static void say(Accessible.State state, String english) {
        PHRASE.put(state, new I18nString(keyFor(state), english));
    }

    static {
        // The word readers already use for a live region or a document that is still loading,
        // lower case like the role phrases, because it is spoken inside a reader's own sentence.
        say(Accessible.State.BUSY, "busy");
    }

    /**
     * @param state a state
     * @return the key its phrase lives under, {@code limn.state.<lower-case-name>}
     */
    public static String keyFor(Accessible.State state) {
        return "limn.state." + state.name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param state  a state
     * @param locale the locale to speak in, which is the node's own and not the process's
     * @return the phrase, or the English when nothing translates it, or {@code null} for a state
     *         no platform carries as words
     */
    public static String of(Accessible.State state, Locale locale) {
        I18nString phrase = PHRASE.get(state);
        // Not phrase.get(), for RoleNames.of's reason: the node's locale, on the platform's thread.
        return phrase == null ? null : I18n.resolve(phrase.key(), phrase.english(), locale);
    }

    /**
     * @param state a state
     * @return the English phrase without consulting any bundle, or {@code null} when there is none
     */
    public static String englishOf(Accessible.State state) {
        I18nString phrase = PHRASE.get(state);
        return phrase == null ? null : phrase.english();
    }
}
